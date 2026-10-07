#!/usr/bin/env python3
"""Verify an API 16+ APK and generate its OTA manifest. Does not upload or publish."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlparse


def run(*args):
    result = subprocess.run(args, check=True, text=True, capture_output=True)
    return result.stdout


def inspect_apk(apk, tools_dir):
    badging = run(str(tools_dir / ('aapt.exe' if os.name == 'nt' else 'aapt')), 'dump', 'badging', str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    min_sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    target_sdk = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    if not package or not min_sdk or not target_sdk:
        raise ValueError('无法读取 APK 包名、版本或 SDK 信息')
    app_id, code, name = package.groups()
    # API 19 is accepted for published historical APKs used in upgrade comparisons.
    minimum = int(min_sdk[1])
    if app_id != 'com.tvbox.android44' or minimum not in (16, 19) or int(target_sdk[1]) != 28 or int(code) <= 0:
        raise ValueError('APK 必须符合 com.tvbox.android44 / minSdk 16（历史版本 19）/ targetSdk 28 基线')
    signature = run(str(tools_dir / ('apksigner.bat' if os.name == 'nt' else 'apksigner')),
                    'verify', '--verbose', '--print-certs', '--min-sdk-version', str(minimum), str(apk))
    if 'Verified using v1 scheme (JAR signing): true' not in signature:
        raise ValueError('旧 Android 发布必须包含有效 v1 签名')
    cert = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})(?![0-9a-fA-F])', signature)
    if not cert:
        raise ValueError('无法读取有效签名证书摘要')
    return dict(applicationId=app_id, minSdk=minimum, targetSdk=int(target_sdk[1]), versionCode=int(code), versionName=name,
                certificateSha256=cert[1].lower(), debug='android debug' in signature.lower(),
                signature=signature)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--apk-url', required=True, help='Public HTTPS URL of these exact APK bytes')
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, help='Android SDK build-tools directory')
    parser.add_argument('--changelog', action='append', default=[])
    signing_mode = parser.add_mutually_exclusive_group()
    signing_mode.add_argument('--allow-debug-signing', action='store_true', help='For local/CI validation only')
    signing_mode.add_argument('--allow-legacy-debug-upgrade', action='store_true',
                             help='Explicitly reuse a previously published debug certificate; requires the same certificate and a higher version')
    parser.add_argument('--previous-apk', type=Path, help='Previously published APK; checks version and signing continuity')
    parser.add_argument('--allow-certificate-change', action='store_true', help='Explicit signing migration; old installations cannot upgrade in place')
    args = parser.parse_args()
    apk = args.apk.resolve(strict=True)
    url = urlparse(args.apk_url)
    if url.scheme != 'https' or not url.hostname or url.username or url.password:
        raise ValueError('apk-url 必须为不含凭据的 HTTPS 公共下载地址')
    tools_dir = args.build_tools
    if tools_dir is None:
        sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
        if not sdk:
            raise ValueError('请设置 ANDROID_HOME 或传入 --build-tools')
        tools_dir = Path(sdk) / 'build-tools' / '35.0.0'
    current = inspect_apk(apk, tools_dir)
    app_id, code, name = current['applicationId'], current['versionCode'], current['versionName']
    signature, debug = current['signature'], current['debug']
    if debug and not (args.allow_debug_signing or args.allow_legacy_debug_upgrade):
        raise ValueError('检测到调试签名；验证构建可显式 --allow-debug-signing，沿用原调试证书需 --previous-apk 和 --allow-legacy-debug-upgrade')
    if args.allow_legacy_debug_upgrade:
        if not args.previous_apk:
            raise ValueError('沿用原调试证书必须提供 --previous-apk')
        if args.allow_certificate_change:
            raise ValueError('沿用原调试证书不能同时允许签名迁移')
    if args.allow_certificate_change and not args.previous_apk:
        raise ValueError('签名迁移必须提供 --previous-apk，核对原版本后才能明确记录升级限制')
    previous = inspect_apk(args.previous_apk.resolve(strict=True), tools_dir) if args.previous_apk else None
    can_upgrade = previous is not None and previous['certificateSha256'] == current['certificateSha256']
    if previous:
        if code <= previous['versionCode']:
            raise ValueError('新 APK 的版本码必须大于上一版')
        if args.allow_legacy_debug_upgrade and not can_upgrade:
            raise ValueError('签名证书与上一版不同；沿用原证书模式不允许更换证书，无法覆盖升级')
        if not can_upgrade and not args.allow_certificate_change:
            raise ValueError('签名证书与上一版不同，无法覆盖升级；确认迁移后才可使用 --allow-certificate-change')
    if args.allow_legacy_debug_upgrade and not (debug and previous['debug']):
        raise ValueError('沿用原调试证书要求新旧 APK 都使用原调试证书')
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    manifest = dict(versionCode=int(code), versionName=name, apkUrl=args.apk_url,
                    apkSha256=digest.hexdigest(), apkSize=apk.stat().st_size,
                    force=False, changelog=args.changelog)
    signing = 'legacy-debug-release' if args.allow_legacy_debug_upgrade else ('debug-validation-only' if debug else 'release')
    metadata = dict(applicationId=app_id, minSdk=current['minSdk'], targetSdk=current['targetSdk'], apkFile=apk.name,
                    signing=signing,
                    certificateSha256=current['certificateSha256'], **manifest)
    if previous:
        metadata.update(previousVersionCode=previous['versionCode'],
                        previousCertificateSha256=previous['certificateSha256'],
                        canUpgradePreviousInstallation=can_upgrade)
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    target = output / apk.name
    if target != apk:
        shutil.copy2(apk, target)
    for filename, content in [('update.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n'),
                              ('apk-info.json', json.dumps(metadata, ensure_ascii=False, indent=2) + '\n'),
                              ('signature.txt', signature),
                              ('SHA256SUMS', f'{digest.hexdigest()}  {apk.name}\n')]:
        temp = output / (filename + '.tmp')
        # Chinese JSON/text uses BOM; keep the ASCII checksum file consumable by sha256sum.
        encoding = 'utf-8-sig' if any('\u4e00' <= c <= '\u9fff' for c in content) else 'utf-8'
        with temp.open('w', encoding=encoding, newline='\n') as output_stream:
            output_stream.write(content)
        temp.replace(output / filename)
    print(f"已验证 {app_id} v{name} (code {code})，minSdk {current['minSdk']}，v1 签名有效")
    signing_description = ('沿用原调试证书的兼容发布（证书一致）' if args.allow_legacy_debug_upgrade
                           else ('调试，仅供验证' if debug else '发布（升级前仍需与上一版证书比对）'))
    print('签名类型：' + signing_description)
    if previous:
        print('旧版升级：' + ('同证书，可覆盖安装' if can_upgrade else '证书变更，不能覆盖旧版；重新安装前须备份设置与历史'))
    print('已生成 APK、update.json、apk-info.json、signature.txt、SHA256SUMS；尚未发布')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Do not print complete command output, URLs or injected configuration.
        print('发布材料生成失败：' + (str(error) if isinstance(error, ValueError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)
