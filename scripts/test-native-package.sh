#!/bin/bash
set -euo pipefail
app=${1:?Provide absolute app path}
worker="$app/Contents/app/native/cad-kernel"
[[ -x "$worker" ]]
report=$(mktemp)
trap 'rm -f "$report"' EXIT
# The launcher must find its private worker without developer/runtime overrides.
env -i PATH=/usr/bin:/bin LANG=en_US.UTF-8 "$app/Contents/MacOS/Kotlin CAD" --kernel-check > "$report"
/usr/bin/grep -q '"kernel":"8.0.1"' "$report"
/usr/bin/grep -q '"conflictPreservesInput":true' "$report"
/usr/bin/grep -q 'cross-drilled-hollow-profile' "$report"
while IFS= read -r -d '' file; do
  if /usr/bin/otool -L "$file" | /usr/bin/tail -n +2 | /usr/bin/grep -E '/opt/homebrew|/Users/|/private/tmp/'; then
    echo 'External native dependency remains' >&2;exit 1
  fi
  /usr/bin/codesign --verify "$file"
done < <(/usr/bin/find "$app/Contents/app/native" -type f \( -name '*.dylib' -o -name 'cad-kernel' \) -print0)
if /usr/bin/find "$app/Contents/app/native" -iname '*python*' -o -iname '*vtk*' | /usr/bin/grep -q .; then
  echo 'Unexpected interpreter or VTK in native bundle' >&2;exit 1
fi
echo 'NATIVE_APP_PACKAGE_OK privateKernel=8.0.1 python=false externalPaths=false signed=true'
