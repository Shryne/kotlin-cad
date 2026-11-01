#!/bin/sh
set -eu
cd "$(dirname "$0")"
project_dir=$(pwd)
python3 scripts/check_secrets.py
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
gradle_command="${CAD_GRADLE:-$project_dir/gradlew}"
"$gradle_command" installDist
./scripts/build-native.sh
mkdir -p build/install/kotlin-cad/lib/licenses
cp LICENSE THIRD_PARTY_NOTICES.md build/install/kotlin-cad/lib/licenses/
mkdir -p build/install/kotlin-cad/lib/native
cp -R build/native-runtime/. build/install/kotlin-cad/lib/native/
mkdir -p "$project_dir/build"
stage_dir=$(mktemp -d "$project_dir/build/package.XXXXXX")
trap 'rm -rf "$stage_dir"' EXIT HUP INT TERM
"$JAVA_HOME/bin/jpackage" \
  --type app-image \
  --name "Kotlin CAD" \
  --app-version 1.48.0 \
  --vendor Local \
  --input build/install/kotlin-cad/lib \
  --main-jar kotlin-cad.jar \
  --main-class cad.MainKt \
  --dest "$stage_dir" \
  --mac-package-identifier local.kotlincad.viewer \
  --java-options "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED"
output="$project_dir/build/releases/Kotlin CAD.app"
if [ -e "$output" ]; then
  echo 'Release bundle already exists; move it aside before packaging again.' >&2
  exit 1
fi
mkdir -p "$project_dir/build/releases"
mv "$stage_dir/Kotlin CAD.app" "$output"
echo "Built: $output"
if [ "${1:-}" = --install ]; then
  python3 scripts/install_macos.py "$output" "${2:-$HOME/Applications/Kotlin CAD.app}"
fi
