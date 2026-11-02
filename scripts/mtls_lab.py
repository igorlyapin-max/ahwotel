#!/usr/bin/env python3
"""Generate fresh, local-only test credentials. Never overwrite existing keys."""
import ipaddress
import os
from pathlib import Path
import secrets
import subprocess


def generate(directory, address):
    ipaddress.ip_address(address)
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise ValueError('mtls_directory_not_empty')
    def run(*args):
        subprocess.run(['openssl', *args], cwd=directory, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    old_umask = os.umask(0o077)
    try:
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
    generate(args.directory.resolve(), args.address)
