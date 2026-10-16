#!/bin/sh
# Usage: sh scripts/android-refactor-log.sh [serial] [output.log]
set -eu
adb_bin=${ADB:-adb}
serial=${1:-}
output=${2:-/tmp/torinos-refactor-device-$(date +%Y%m%d-%H%M%S).log}
if [ -z "$serial" ]; then
    devices=$("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    count=$(printf '%s\n' "$devices" | awk 'NF { n++ } END { print n+0 }')
    if [ "$count" -ne 1 ]; then
        printf '%s\n' '接続済み端末を1台にするか、第1引数でserialを指定してください。' >&2
        exit 1
    fi
    serial=$devices
fi
"$adb_bin" -s "$serial" shell setprop log.tag.ToriNosRefactor DEBUG
printf '保存先: %s\n終了はCtrl+C。診断ログを終了時に無効化します。\n' "$output"
capture_pid=
cleanup() {
    if [ -n "$capture_pid" ]; then kill "$capture_pid" 2>/dev/null || true; fi
    "$adb_bin" -s "$serial" shell setprop log.tag.ToriNosRefactor INFO || true
}
trap cleanup EXIT
trap 'exit 130' INT TERM
# 既存logcatの消去はしない。今回の記録開始後とクラッシュのみを収集する。
"$adb_bin" -s "$serial" logcat -v threadtime -T 1 ToriNosRefactor:D AndroidRuntime:E '*:S' > "$output" &
capture_pid=$!
"$adb_bin" -s "$serial" shell am start -W -n com.nostr.torinos/com.nostr.torinos.MainActivity
wait "$capture_pid"
