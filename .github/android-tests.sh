#!/usr/bin/env bash
set -euo pipefail

reports=build/emulator-tests/api-$1-pages-$2
mkdir -p "$reports"
api=$(adb shell getprop ro.build.version.sdk)
abi=$(adb shell getprop ro.product.cpu.abi)
pages=$(adb shell getconf PAGESIZE)
selinux=$(adb shell getenforce)
printf 'API=%s ABI=%s page_size=%s SELinux=%s\n' "$api" "$abi" "$pages" "$selinux" | tee "$reports/device.txt"
[[ $api == "$1" && $abi == x86_64 && $pages == "$2" && $selinux == Enforcing ]] ||
    { echo "Expected an SELinux Enforcing x86_64 device with API $1 and $2-byte pages" >&2; exit 1; }
adb shell svc wifi enable
adb shell svc data enable

save_reports() {
    for module in tsnet demo; do
        mkdir -p "$reports/$module"
        if [[ -d $module/build/reports/androidTests ]]; then
            cp -R "$module/build/reports/androidTests" "$reports/$module/"
        fi
        if [[ -d $module/build/outputs/androidTest-results ]]; then
            find "$module/build/outputs/androidTest-results" -name 'TEST-*.xml' -exec cp {} "$reports/$module/" \;
        fi
    done
}
trap save_reports EXIT
./gradlew --continue :tsnet:connectedDebugAndroidTest :demo:connectedReleaseAndroidTest
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

for module in ('tsnet', 'demo'):
    suites = [ET.parse(p).getroot() for p in Path(module, 'build/outputs/androidTest-results').rglob('TEST-*.xml')]
    assert sum(int(s.get('tests')) for s in suites) > 0, f'{module}: no tests ran'
    assert all(int(s.get(k)) == 0 for s in suites for k in ('failures', 'errors', 'skipped')), f'{module}: incomplete tests'
PY
