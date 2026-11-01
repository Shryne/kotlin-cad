#!/bin/sh
set -eu
app_binary="${KOTLIN_CAD_APP:-$HOME/Applications/Kotlin CAD.app}/Contents/MacOS/Kotlin CAD"
if [ ! -x "$app_binary" ]; then
  echo "Kotlin CAD: Mac-App fehlt. Zuerst package-macos.sh ausführen." >&2
  exit 1
fi
exec "$app_binary" --mcp "$@"
