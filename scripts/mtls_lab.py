#!/usr/bin/env python3
"""Generate fresh, local-only test credentials. Never overwrite existing keys."""
import ipaddress
import os
from pathlib import Path
import secrets
import stat
import subprocess


def private_output_directory(directory):
    # This CLI deliberately accepts an operator-selected output directory. Inspect
    # the original components: resolve() would hide a symlink before validation.
    directory = Path(directory)
    if '..' in directory.parts:
        raise ValueError('mtls_parent_path_not_allowed')
    directory = directory.absolute()
    components = list(reversed(directory.parents)) + [directory]
    for path in components:
        if path.is_symlink() or (hasattr(path, 'is_junction') and path.is_junction()):
            raise ValueError('mtls_symlink_not_allowed')
        if path.exists() and not path.is_dir():
            raise ValueError('mtls_path_not_directory')
    for path in components:
        if not path.exists():
            path.mkdir(mode=0o700)
    info = directory.lstat()
    if os.name == 'posix' and (info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) & 0o077):
        raise ValueError('mtls_directory_not_private')
    if any(directory.iterdir()):
        raise ValueError('mtls_directory_not_empty')
    return directory


def generate(directory, address):
    ipaddress.ip_address(address)
    directory = private_output_directory(directory)
    def run(*args):
        subprocess.run(['openssl', *args], cwd=directory, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    old_umask = os.umask(0o077)
    lock = None
    try:
        # Exclusive creation also prevents two cooperative generator invocations
        # from both passing the empty-directory check and overwriting keys.
        lock = (directory/'.generation.lock').open('x')
        if any(path.name != '.generation.lock' for path in directory.iterdir()):
            raise ValueError('mtls_directory_not_empty')
        run('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '30',
            '-keyout', 'ca.key', '-out', 'ca.crt', '-subj', '/CN=AHWOTel Local Test CA',
            '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
        for kind in ('server', 'client'):
            run('req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', kind+'.key', '-out', kind+'.csr',
                '-subj', '/CN=AHWOTel Local Test '+kind)
            extension = 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n'
            extension += 'extendedKeyUsage='+('serverAuth' if kind=='server' else 'clientAuth')+'\n'
            extension += ('subjectAltName=IP:'+address+',DNS:localhost,IP:127.0.0.1\n' if kind=='server' else 'subjectAltName=URI:urn:ahwotel:test-device\n')
            (directory/(kind+'.ext')).write_text(extension)
            run('x509', '-req', '-in', kind+'.csr', '-CA', 'ca.crt', '-CAkey', 'ca.key', '-CAcreateserial',
                '-out', kind+'.crt', '-days', '14', '-sha256', '-extfile', kind+'.ext')
        (directory/'client-password.txt').write_text(secrets.token_urlsafe(24))
        run('pkcs12', '-export', '-inkey', 'client.key', '-in', 'client.crt', '-certfile', 'ca.crt',
            '-name', 'ahwotel-client', '-out', 'client.p12', '-passout', 'file:client-password.txt')
        for name in ('ca.crt', 'server.crt', 'client.crt'):
            (directory/name).chmod(0o644)
        (directory/'server.key').chmod(0o640)
    finally:
        try:
            if lock is not None:
                lock.close()
                (directory/'.generation.lock').unlink()
        finally:
            os.umask(old_umask)


def configure(lab, state, env):
    mode = env.get('OTLP_AUTH_MODE', 'none')
    if mode not in ('none', 'mtls'):
        raise ValueError('invalid_OTLP_AUTH_MODE')
    if mode == 'none':
        return
    for name in ('server.crt', 'server.key', 'ca.crt'):
        path = state/'mtls'/name
        if not path.is_file() or not os.access(path, os.R_OK):
            raise ValueError('missing_mtls_material:'+name)
    material = state/'mtls'
    try:
        for name in ('server.crt', 'ca.crt'):
            subprocess.run(['openssl', 'x509', '-in', str(material/name), '-checkend', '0', '-noout'],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        public_key = subprocess.check_output(['openssl', 'pkey', '-in', str(material/'server.key'), '-pubout'], stderr=subprocess.PIPE, timeout=10)
        certificate_key = subprocess.check_output(['openssl', 'x509', '-in', str(material/'server.crt'), '-pubkey', '-noout'], stderr=subprocess.PIPE, timeout=10)
        if public_key != certificate_key: raise ValueError('mtls_server_key_mismatch')
    except subprocess.SubprocessError:
        raise ValueError('mtls_material_invalid_or_expired') from None
    text = (lab/'collector.yaml').read_text()
    marker = '        endpoint: 0.0.0.0:4318'
    if text.count(marker) != 1:
        raise ValueError('unexpected_collector_tls_template')
    text = text.replace(marker, marker+'\n        tls:\n          min_version: "1.2"\n          cert_file: /etc/ahwotel-tls/server.crt\n          key_file: /etc/ahwotel-tls/server.key\n          client_ca_file: /etc/ahwotel-tls/client-ca.crt')
    (state/'collector-mtls.yaml').write_text(text)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('address')
    args = parser.parse_args()
    generate(args.directory, args.address)
