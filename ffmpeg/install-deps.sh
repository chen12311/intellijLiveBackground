#!/bin/sh
# 装 ffmpeg/build.sh 要用的工具。CI 在容器里先跑这个：alpine 编 linux-*，debian 编 windows-x64。
# mac 上用 Homebrew：brew install nasm meson ninja pkgconf
set -eu

if command -v apk >/dev/null; then
    apk add --no-cache bash build-base linux-headers nasm meson ninja pkgconf zlib-dev zlib-static curl xz tar zip
else
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y --no-install-recommends bash build-essential gcc-mingw-w64-x86-64 libz-mingw-w64-dev \
        nasm meson ninja-build pkgconf curl ca-certificates xz-utils zip
fi
