#!/usr/bin/env python3
"""Validate local test PKI and the optional collector TLS configuration."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import mtls_lab
import otel_lab


class MtlsLabTest(unittest.TestCase):
    def test_generated_credentials_and_collector_fail_closed(self):
        with tempfile.TemporaryDirectory() as path:
            state = Path(path)
            certs = state/'mtls'
            mtls_lab.generate(certs, '127.0.0.1')
            self.assertEqual(0o700, certs.stat().st_mode & 0o777)
            for purpose, name in (('sslclient', 'client'), ('sslserver', 'server')):
                subprocess.run(['openssl', 'verify', '-purpose', purpose, '-CAfile', str(certs/'ca.crt'), str(certs/(name+'.crt'))], check=True, stdout=subprocess.DEVNULL)
            self.assertEqual(0o600, (certs/'client.key').stat().st_mode & 0o777)
            self.assertEqual(0o600, (certs/'client-password.txt').stat().st_mode & 0o777)
            self.assertEqual(0o640, (certs/'server.key').stat().st_mode & 0o777)
            original = {p.name: p.read_bytes() for p in certs.iterdir()}
            with self.assertRaisesRegex(ValueError, 'not_empty'): mtls_lab.generate(certs, '127.0.0.1')
            self.assertEqual(original, {p.name: p.read_bytes() for p in certs.iterdir()})
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

    def test_unsafe_paths_are_rejected_before_openssl_or_writes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            target = root/'target'; target.mkdir(mode=0o700)
            link = root/'link'; link.symlink_to(target, target_is_directory=True)
            dangling = root/'dangling'; dangling.symlink_to(root/'absent')
            file = root/'file'; file.write_text('sentinel')
            occupied = root/'occupied'; occupied.mkdir(mode=0o700)
            (occupied/'ca.key').write_text('sentinel')
            cases = [(link, 'symlink'), (link/'new', 'symlink'), (dangling, 'symlink'),
                     (file/'new', 'not_directory'), (occupied, 'not_empty'),
                     (root/'missing'/'..'/'new', 'parent_path')]
            with patch('mtls_lab.subprocess.run') as openssl:
                for path, error in cases:
                    with self.subTest(path=path):
                        with self.assertRaisesRegex(ValueError, error): mtls_lab.generate(path, '127.0.0.1')
                openssl.assert_not_called()
            self.assertEqual([], list(target.iterdir()))
            self.assertFalse((root/'absent').exists())
            self.assertFalse((root/'missing').exists())
            self.assertEqual('sentinel', file.read_text())
            self.assertEqual('sentinel', (occupied/'ca.key').read_text())

    @unittest.skipUnless(os.name == 'posix', 'POSIX owner/mode policy')
    def test_insecure_directory_or_wrong_owner_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder)/'output'; target.mkdir(mode=0o700)
            with patch('mtls_lab.subprocess.run') as openssl:
                for mode in (0o755, 0o750, 0o707):
                    target.chmod(mode)
                    with self.assertRaisesRegex(ValueError, 'not_private'): mtls_lab.generate(target, '127.0.0.1')
                target.chmod(0o700)
                with patch('mtls_lab.os.geteuid', return_value=os.geteuid()+1):
                    with self.assertRaisesRegex(ValueError, 'not_private'): mtls_lab.generate(target, '127.0.0.1')
                openssl.assert_not_called()
            self.assertEqual([], list(target.iterdir()))

    def test_cli_keeps_symlink_visible_to_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); target = root/'target'; target.mkdir(mode=0o700)
            link = root/'link'; link.symlink_to(target, target_is_directory=True)
            import sys
            result = subprocess.run([sys.executable, str(Path(mtls_lab.__file__)), str(link), '127.0.0.1'],
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('mtls_symlink_not_allowed', result.stderr)
            self.assertEqual([], list(target.iterdir()))

    def test_cli_and_lab_entrypoints_and_existing_private_temp_directory(self):
        import sys
        with tempfile.TemporaryDirectory() as folder:
            # mtls_lab_smoke passes an already-created private TemporaryDirectory.
            mtls_lab.generate(Path(folder), '127.0.0.1')
            self.assertTrue((Path(folder)/'client.p12').is_file())
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            script = Path(mtls_lab.__file__)
            subprocess.run([sys.executable, str(script), str(root/'cli'), '127.0.0.1'],
                           check=True, capture_output=True)
            lab = root/'lab'; lab.mkdir(mode=0o700)
            (lab/'.env.example').write_bytes((otel_lab.ROOT/'deploy/otel-lab/.env.example').read_bytes())
            env = dict(os.environ, AHWOTEL_LAB_DIR=str(lab), LAB_BIND_ADDRESS='127.0.0.1')
            result = subprocess.run([sys.executable, str(script.with_name('otel_lab.py')), 'certs'],
                                    check=True, capture_output=True, text=True, env=env)
            self.assertIn('Test credentials generated', result.stdout)
            self.assertTrue((lab/'.runtime/mtls/client.p12').is_file())

    def test_lab_cli_rejects_symlink_before_creating_material(self):
        import sys
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); real = root/'real'; real.mkdir(mode=0o700)
            (real/'.env.example').write_bytes((otel_lab.ROOT/'deploy/otel-lab/.env.example').read_bytes())
            link = root/'link'; link.symlink_to(real, target_is_directory=True)
            runtime = real/'.runtime'; runtime.symlink_to(root/'target', target_is_directory=True)
            (root/'target').mkdir(mode=0o700)
            for lab in (link, real):
                env = dict(os.environ, AHWOTEL_LAB_DIR=str(lab), LAB_BIND_ADDRESS='127.0.0.1')
                result = subprocess.run([sys.executable, str(Path(otel_lab.__file__)), 'certs'], env=env, capture_output=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root/'target'/'mtls').exists())
            runtime.unlink()
            env['AHWOTEL_LAB_DIR'] = str(link)
            result = subprocess.run([sys.executable, str(Path(otel_lab.__file__)), 'certs'], env=env, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((real/'.runtime/mtls').exists())


if __name__ == '__main__': unittest.main()
