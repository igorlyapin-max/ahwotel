#!/usr/bin/env python3
"""Validate local test PKI and the optional collector TLS configuration."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
import mtls_lab
import otel_lab


class MtlsLabTest(unittest.TestCase):
    def test_generated_credentials_and_collector_fail_closed(self):
        with tempfile.TemporaryDirectory() as path:
            state = Path(path)
            certs = state/'mtls'
            mtls_lab.generate(certs, '127.0.0.1')
            for purpose, name in (('sslclient', 'client'), ('sslserver', 'server')):
                subprocess.run(['openssl', 'verify', '-purpose', purpose, '-CAfile', str(certs/'ca.crt'), str(certs/(name+'.crt'))], check=True, stdout=subprocess.DEVNULL)
            self.assertEqual(0o600, (certs/'client.key').stat().st_mode & 0o777)
            self.assertEqual(0o600, (certs/'client-password.txt').stat().st_mode & 0o777)
            self.assertEqual(0o640, (certs/'server.key').stat().st_mode & 0o777)
            with self.assertRaisesRegex(ValueError, 'not_empty'): mtls_lab.generate(certs, '127.0.0.1')
            mtls_lab.configure(otel_lab.LAB, state, {'OTLP_AUTH_MODE': 'mtls'})
            config = (state/'collector-mtls.yaml').read_text()
            self.assertEqual(1, config.count('endpoint: 0.0.0.0:4318'))
            self.assertIn('client_ca_file:', config)
            self.assertIn('min_version: "1.2"', config)
            self.assertNotIn('insecure:', config)
            (certs/'server.key').unlink()
            with self.assertRaisesRegex(ValueError, 'missing_mtls_material'): mtls_lab.configure(otel_lab.LAB, state, {'OTLP_AUTH_MODE': 'mtls'})

    def test_none_needs_no_keys_and_unknown_mode_is_rejected(self):
        with tempfile.TemporaryDirectory() as path:
            state = Path(path)
            mtls_lab.configure(otel_lab.LAB, state, {'OTLP_AUTH_MODE': 'none'})
            self.assertFalse((state/'collector-mtls.yaml').exists())
            with self.assertRaisesRegex(ValueError, 'invalid_OTLP_AUTH_MODE'): mtls_lab.configure(otel_lab.LAB, state, {'OTLP_AUTH_MODE': 'optional'})


if __name__ == '__main__': unittest.main()
