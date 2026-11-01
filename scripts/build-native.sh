#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
project_dir=$(pwd)
version=8.0.1
archive="$project_dir/build/native-src/occt-$version.tar.gz"
source_dir="$project_dir/build/native-src/OCCT-8_0_1"
prefix="$project_dir/build/occt-install"
mkdir -p "$project_dir/build/native-src"
if [ ! -f "$archive" ]; then
  curl -fL https://github.com/Open-Cascade-SAS/OCCT/archive/refs/tags/V8_0_1.tar.gz -o "$archive.download"
  mv "$archive.download" "$archive"
fi
actual=$(shasum -a 256 "$archive" | cut -d ' ' -f 1)
[ "$actual" = 0d6913eae4bcc09a3653ceced6dda1aec11c35a1513d4c06762c9b002092c68a ] || { echo 'OpenCascade source checksum mismatch' >&2;exit 1; }
[ -d "$source_dir" ] || tar -xzf "$archive" -C "$project_dir/build/native-src"
cmake -S "$source_dir" -B build/occt -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix" \
  -DBUILD_MODULE_Visualization=OFF -DBUILD_MODULE_ApplicationFramework=OFF -DBUILD_MODULE_DataExchange=OFF -DBUILD_MODULE_Draw=OFF \
  -DUSE_TK=OFF -DUSE_FREETYPE=OFF -DUSE_OPENGL=OFF -DUSE_XLIB=OFF -DUSE_TBB=OFF -DBUILD_DOC_Overview=OFF \
  -DINSTALL_DIR_LAYOUT=Unix -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0 -DCMAKE_INSTALL_RPATH=@loader_path -DCMAKE_EXPORT_NO_PACKAGE_REGISTRY=ON
cmake --build build/occt --parallel "${CAD_NATIVE_JOBS:-8}"
cmake --install build/occt
eigen_prefix="${CAD_EIGEN_PREFIX:-$(brew --prefix eigen)}"
cmake -S native -B build/native -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="$prefix;$eigen_prefix" -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0
cmake --build build/native --parallel "${CAD_NATIVE_JOBS:-8}"
cmake --install build/native --prefix "$project_dir/build/native-runtime"
python3 scripts/bundle_native.py "$project_dir/build/native-runtime" "$prefix" "$source_dir"
"$project_dir/build/native-runtime/cad-kernel" --self-test
