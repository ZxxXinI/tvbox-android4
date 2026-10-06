#!/usr/bin/env bash
# Use only with an explicitly selected test device. Leaves the app installed for manual acceptance.
set -euo pipefail
if [[ $# != 2 ]]; then
  echo '用法: bash scripts/device_smoke.sh <adb序列号> <APK路径>' >&2
  exit 2
fi
device_serial=$1
test_apk=$2
adb -s "$device_serial" get-state
device_api=$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')
if [[ ! "$device_api" =~ ^[0-9]+$ ]] || (( device_api < 19 )); then
  echo '需要 API 19 或以上测试设备' >&2
  exit 1
fi
adb -s "$device_serial" install -r "$test_apk"
adb -s "$device_serial" shell am force-stop com.tvbox.android44
adb -s "$device_serial" shell am start -W -n com.tvbox.android44/.feature.main.MainActivity
echo '安装和启动命令已执行；请按 docs/13-当前版本基线与验收清单.md 完成遥控器、播放与 OTA 验收。'
