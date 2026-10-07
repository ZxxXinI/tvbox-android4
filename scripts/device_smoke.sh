#!/usr/bin/env bash
# Use only with an explicitly selected test device. Leaves the app installed for manual acceptance.
set -euo pipefail
if [[ $# != 2 ]]; then
  echo 'Usage: bash scripts/device_smoke.sh <device-serial> <apk-path>' >&2
  exit 2
fi
device_serial=$1
test_apk=$2
adb -s "$device_serial" get-state
device_api=$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')
if [[ ! "$device_api" =~ ^[0-9]+$ ]] || (( device_api < 16 )); then
  echo 'Requires Android API 16 or later' >&2
  exit 1
fi
adb -s "$device_serial" install -r "$test_apk"
adb -s "$device_serial" shell am force-stop com.tvbox.android44
adb -s "$device_serial" shell am start -W -n com.tvbox.android44/.feature.main.MainActivity
echo 'Install and launch commands completed; see docs/13 and docs/17 for device acceptance.'
