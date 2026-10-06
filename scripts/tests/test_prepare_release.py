import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('prepare_release', Path(__file__).parents[1] / 'prepare_release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class PrepareReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.current = self.root / 'app-release.apk'
        self.previous = self.root / 'old.apk'
        self.current.write_bytes(b'fixture signed APK bytes')
        self.previous.write_bytes(b'previous signed APK bytes')
        self.code = 2
        self.certificate = 'a' * 64
        self.old_certificate = self.certificate
        self.v1 = True
        self.debug = False
        self.output = self.root / 'output'

    def binary_output(self, *args):
        old = args[-1] == str(self.previous)
        if Path(args[0]).name == 'aapt':
            return "package: name='com.tvbox.android44' versionCode='%d' versionName='fixture'\nsdkVersion:'19'\ntargetSdkVersion:'28'\n" % (1 if old else self.code)
        return ('Verified using v1 scheme (JAR signing): %s\nSigner #1 certificate DN: CN=%s\nSigner #1 certificate SHA-256 digest: %s\n'
                % ('true' if old or self.v1 else 'false', 'Android Debug' if self.debug and not old else 'Fixture',
                   self.old_certificate if old else self.certificate))

    def invoke(self, *extra):
        args = ['prepare_release', '--apk', str(self.current), '--apk-url', 'https://example.com/app-release.apk',
                '--output-dir', str(self.output), '--build-tools', str(self.root), '--previous-apk', str(self.previous), *extra]
        with patch.object(sys, 'argv', args), patch.object(release, 'run', side_effect=self.binary_output), contextlib.redirect_stdout(io.StringIO()):
            release.main()

    def test_same_certificate_generates_verified_upgrade_material(self):
        self.invoke()
        manifest = json.loads((self.output / 'update.json').read_text())
        metadata = json.loads((self.output / 'apk-info.json').read_text())
        self.assertEqual(2, manifest['versionCode'])
        self.assertEqual(hashlib.sha256(self.current.read_bytes()).hexdigest(), manifest['apkSha256'])
        self.assertEqual(self.current.stat().st_size, manifest['apkSize'])
        self.assertTrue(metadata['canUpgradePreviousInstallation'])
        self.assertEqual(1, metadata['previousVersionCode'])

    def test_nonincreasing_version_refuses_output(self):
        self.code = 1
        with self.assertRaisesRegex(ValueError, '版本码'):
            self.invoke()
        self.assertFalse(self.output.exists())

    def test_changed_certificate_refuses_output_by_default(self):
        self.certificate = 'b' * 64
        with self.assertRaisesRegex(ValueError, '无法覆盖升级'):
            self.invoke()
        self.assertFalse(self.output.exists())

    def test_explicit_certificate_migration_records_installation_limit(self):
        self.certificate = 'b' * 64
        self.invoke('--allow-certificate-change')
        metadata = json.loads((self.output / 'apk-info.json').read_text())
        self.assertFalse(metadata['canUpgradePreviousInstallation'])
        self.assertEqual(self.old_certificate, metadata['previousCertificateSha256'])

    def test_missing_v1_signature_refuses_api19_release(self):
        self.v1 = False
        with self.assertRaisesRegex(ValueError, 'v1'):
            self.invoke()
        self.assertFalse(self.output.exists())

    def test_debug_certificate_is_still_rejected(self):
        self.debug = True
        with self.assertRaisesRegex(ValueError, '调试签名'):
            self.invoke()
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
