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
        self.minimum = 16
        self.certificate = 'a' * 64
        self.old_certificate = self.certificate
        self.v1 = True
        self.debug = False
        self.old_debug = False
        self.output = self.root / 'output'

    def binary_output(self, *args):
        old = args[-1] == str(self.previous)
        if Path(args[0]).stem == 'aapt':
            return "package: name='com.tvbox.android44' versionCode='%d' versionName='fixture'\nsdkVersion:'%d'\ntargetSdkVersion:'28'\n" % (1 if old else self.code, 19 if old else self.minimum)
        return ('Verified using v1 scheme (JAR signing): %s\nSigner #1 certificate DN: CN=%s\nSigner #1 certificate SHA-256 digest: %s\n'
                % ('true' if old or self.v1 else 'false', 'Android Debug' if (self.old_debug if old else self.debug) else 'Fixture',
                   self.old_certificate if old else self.certificate))

    def invoke(self, *extra):
        args = ['prepare_release', '--apk', str(self.current), '--apk-url', 'https://example.com/app-release.apk',
                '--output-dir', str(self.output), '--build-tools', str(self.root), '--previous-apk', str(self.previous), *extra]
        with patch.object(sys, 'argv', args), patch.object(release, 'run', side_effect=self.binary_output), contextlib.redirect_stdout(io.StringIO()):
            release.main()

    def test_same_certificate_generates_verified_upgrade_material(self):
        self.invoke()
        manifest = json.loads((self.output / 'update.json').read_text(encoding='utf-8-sig'))
        metadata = json.loads((self.output / 'apk-info.json').read_text(encoding='utf-8-sig'))
        self.assertEqual(16, metadata['minSdk'])
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
        metadata = json.loads((self.output / 'apk-info.json').read_text(encoding='utf-8-sig'))
        self.assertFalse(metadata['canUpgradePreviousInstallation'])
        self.assertEqual(self.old_certificate, metadata['previousCertificateSha256'])

    def test_missing_v1_signature_refuses_legacy_release(self):
        self.v1 = False
        with self.assertRaisesRegex(ValueError, 'v1'):
            self.invoke()
        self.assertFalse(self.output.exists())

    def test_debug_certificate_is_still_rejected(self):
        self.debug = True
        with self.assertRaisesRegex(ValueError, '调试签名'):
            self.invoke()
        self.assertFalse(self.output.exists())

    def test_legacy_debug_upgrade_records_original_certificate_release(self):
        self.debug = self.old_debug = True
        self.invoke('--allow-legacy-debug-upgrade')
        metadata = json.loads((self.output / 'apk-info.json').read_text(encoding='utf-8-sig'))
        self.assertEqual('legacy-debug-release', metadata['signing'])
        self.assertTrue(metadata['canUpgradePreviousInstallation'])
        self.assertEqual(self.old_certificate, metadata['certificateSha256'])

    def test_legacy_debug_upgrade_rejects_another_debug_certificate(self):
        self.debug = self.old_debug = True
        self.certificate = 'b' * 64
        with self.assertRaisesRegex(ValueError, '无法覆盖升级'):
            self.invoke('--allow-legacy-debug-upgrade')
        self.assertFalse(self.output.exists())

    def test_legacy_debug_upgrade_requires_previous_apk(self):
        self.debug = self.old_debug = True
        args = ['prepare_release', '--apk', str(self.current), '--apk-url', 'https://example.com/app-release.apk',
                '--output-dir', str(self.output), '--build-tools', str(self.root), '--allow-legacy-debug-upgrade']
        with patch.object(sys, 'argv', args), patch.object(release, 'run', side_effect=self.binary_output):
            with self.assertRaisesRegex(ValueError, '必须提供 --previous-apk'):
                release.main()
        self.assertFalse(self.output.exists())

    def test_legacy_debug_upgrade_rejects_certificate_migration_flag(self):
        self.debug = self.old_debug = True
        with self.assertRaisesRegex(ValueError, '不能同时允许签名迁移'):
            self.invoke('--allow-legacy-debug-upgrade', '--allow-certificate-change')
        self.assertFalse(self.output.exists())

    def test_legacy_debug_upgrade_still_requires_higher_version(self):
        self.debug = self.old_debug = True
        self.code = 1
        with self.assertRaisesRegex(ValueError, '版本码'):
            self.invoke('--allow-legacy-debug-upgrade')
        self.assertFalse(self.output.exists())

    def test_legacy_debug_upgrade_rejects_non_debug_certificate(self):
        with self.assertRaisesRegex(ValueError, '新旧 APK 都使用原调试证书'):
            self.invoke('--allow-legacy-debug-upgrade')
        self.assertFalse(self.output.exists())

    def test_legacy_debug_upgrade_cannot_be_mixed_with_validation_mode(self):
        self.debug = self.old_debug = True
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                self.invoke('--allow-legacy-debug-upgrade', '--allow-debug-signing')
        self.assertEqual(2, error.exception.code)
        self.assertFalse(self.output.exists())


    def test_unsupported_minimum_is_rejected(self):
        self.minimum = 14
        with self.assertRaisesRegex(ValueError, 'minSdk'):
            self.invoke()
        self.assertFalse(self.output.exists())

    def test_historical_api19_metadata_keeps_actual_sdk(self):
        self.minimum = 19
        self.invoke()
        metadata = json.loads((self.output / 'apk-info.json').read_text(encoding='utf-8-sig'))
        self.assertEqual(19, metadata['minSdk'])


if __name__ == '__main__':
    unittest.main()
