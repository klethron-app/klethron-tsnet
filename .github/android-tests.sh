#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 || ! $1 =~ ^[0-9]+$ || ! $2 =~ ^(4096|16384)$ ]]; then
    echo "Usage: ANDROID_SERIAL=<serial> $0 <api> <4096|16384>" >&2
    exit 2
fi

reports=build/emulator-tests/api-$1-pages-$2
rm -rf "$reports"
mkdir -p "$reports"
# Clear old results even if device validation fails before Gradle runs.
for module in tsnet demo; do
    rm -rf "$module/build/reports/androidTests" "$module/build/outputs/androidTest-results"
done

# Resolve once so adb validation and Gradle use the same device, even with several connected.
ANDROID_SERIAL=$(adb get-serialno)
export ANDROID_SERIAL
api=$(adb shell getprop ro.build.version.sdk)
abi=$(adb shell getprop ro.product.cpu.abi)
pages=$(adb shell getconf PAGESIZE)
selinux=$(adb shell getenforce)
printf 'serial=%s API=%s ABI=%s page_size=%s KB SELinux=%s\n' "$ANDROID_SERIAL" "$api" "$abi" "$((pages / 1024))" "$selinux" | tee "$reports/device.txt"
[[ $api == "$1" && $abi == x86_64 && $pages == "$2" && $selinux == Enforcing ]] ||
    { echo "Expected an SELinux Enforcing x86_64 device with API $1 and $(($2 / 1024)) KB pages" >&2; exit 1; }
adb shell svc wifi enable
adb shell svc data enable

status=0
# The timing experiment is opt-in and runs separately; its assumption is otherwise a skipped test.
./gradlew --continue :tsnet:connectedDebugAndroidTest :demo:connectedReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=com.klethron.tsnet.StartupTimingTest || status=$?
for module in tsnet demo; do
    mkdir -p "$reports/$module"
    if [[ -d $module/build/reports/androidTests ]]; then
        cp -R "$module/build/reports/androidTests" "$reports/$module/"
    fi
    if [[ -d $module/build/outputs/androidTest-results ]]; then
        find "$module/build/outputs/androidTest-results" -name 'TEST-*.xml' -exec cp {} "$reports/$module/" \;
    fi
done
python3 - "$api" "$pages" "$reports" <<'PY'
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

lines = [f'### API {sys.argv[1]} / {int(sys.argv[2]) // 1024} KB pages / x86_64', '',
         '| Module | Tests | Failures | Errors | Skipped |', '| --- | ---: | ---: | ---: | ---: |']
complete = True
for module in ('tsnet', 'demo'):
    suites = [ET.parse(p).getroot() for p in Path(sys.argv[3], module).glob('TEST-*.xml')]
    counts = [sum(int(s.get(k, '0')) for s in suites) for k in ('tests', 'failures', 'errors', 'skipped')]
    complete &= counts[0] > 0 and not any(counts[1:])
    lines.append(f'| {module} | ' + ' | '.join(map(str, counts)) + ' |')
lines += ['', "HTML reports and JUnit XML are in this job's reports artifact.", '']
summary = '\n'.join(lines)
print(summary)
Path(sys.argv[3], 'summary.md').write_text(summary)
if path := os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(path, 'a') as output:
        output.write(summary)
if not complete:
    sys.exit('Android tests failed, were skipped or produced no results; see the counts above.')
PY
exit "$status"
