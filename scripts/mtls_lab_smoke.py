#!/usr/bin/env python3
"""Real mTLS admission checks plus APK protobuf -> stored Prometheus samples."""
import json
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from otel_lab import ROOT, STATE, environment
from mtls_lab import generate


def main():
    env = environment()
    if env.get('OTLP_AUTH_MODE') != 'mtls': raise ValueError('mtls_smoke_requires_mtls')
    material = STATE/'mtls'
    endpoint = f"https://{env['LAB_BIND_ADDRESS']}:{env['OTLP_PORT']}/v1/metrics"
    checks = []
    def context(ca, cert=None, key=None):
        result = ssl.create_default_context(cafile=str(ca))
        if cert: result.load_cert_chain(str(cert), str(key))
        return result
    def send(ctx, url=endpoint):
        http = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ctx))
        request = urllib.request.Request(url, b'', {'Content-Type': 'application/x-protobuf'}, method='POST')
        with http.open(request, timeout=5) as response:
            assert response.status == 200 and response.read() in (b'', b'\x0a\x00')
    def rejected(name, operation):
        try: operation()
        except (OSError, urllib.error.URLError): checks.append(name)
        else: raise AssertionError(name+'_was_accepted')

    send(context(material/'ca.crt', material/'client.crt', material/'client.key'))
    checks.append('valid_client_accepted')
    rejected('missing_client_rejected', lambda: send(context(material/'ca.crt')))
    rejected('wrong_client_eku_rejected', lambda: send(context(material/'ca.crt', material/'server.crt', material/'server.key')))
    with tempfile.TemporaryDirectory(prefix='ahwotel-mtls-smoke-') as folder:
        other = Path(folder)
        generate(other, '127.0.0.1')
        rejected('unknown_client_ca_rejected', lambda: send(context(material/'ca.crt', other/'client.crt', other/'client.key')))
        rejected('unknown_server_ca_rejected', lambda: send(context(other/'ca.crt', material/'client.crt', material/'client.key')))
    rejected('plaintext_rejected', lambda: send(context(material/'ca.crt'), endpoint.replace('https://', 'http://', 1)))
    try:
        ctx = context(material/'ca.crt', material/'client.crt', material/'client.key')
        with socket.create_connection((env['LAB_BIND_ADDRESS'], int(env['OTLP_PORT'])), timeout=5) as raw:
            with ctx.wrap_socket(raw, server_hostname='wrong-host.invalid'): pass
    except ssl.SSLCertVerificationError: checks.append('wrong_server_hostname_rejected')
    else: raise AssertionError('wrong_server_hostname_was_accepted')
    subprocess.run([sys.executable, str(ROOT/'scripts/otel_lab_smoke.py')], env=env, check=True)
    output = ROOT/'artifacts/otel-lab/mtls-security-smoke.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'passed': True, 'authMode': 'mtls', 'checks': checks, 'storedSamplesEvidence': 'wire-smoke-mtls.json'}, indent=2)+'\n')
    print('PASS: mTLS admission, server trust/hostname, no plaintext bypass, stored APK metrics')


if __name__ == '__main__': main()
