#!/usr/bin/env bash
# Undoes the debloat: re-enables every package listed in disabled-packages.txt (they were
# disabled with "pm disable-user --user 0", never uninstalled) and sets Private DNS back to
# automatic. Usage: ./restore.sh <tv-address>:5555
set -u
DEV="${1:?Usage: $0 <tv-address>:5555}"
DIR="$(cd "$(dirname "$0")" && pwd)"
adb connect "$DEV"
while read -r p; do
  [ -n "$p" ] || continue
  adb -s "$DEV" shell pm enable "$p"
done < "$DIR/disabled-packages.txt"
adb -s "$DEV" shell settings put global private_dns_mode opportunistic
echo "Restored. Reboot the Fire TV to finish: adb -s $DEV reboot"
