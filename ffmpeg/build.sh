#!/usr/bin/env bash
# 编译插件自动下载用的精简版 ffmpeg：只解码插件用得到的格式，LGPL，静态链接，单个可执行文件。
# CI（.github/workflows/ffmpeg.yml）每个平台跑一次，打好的 zip 发到 GitHub Release，
# 插件按 src/main/resources/ffmpeg/downloads.properties 里的地址和校验值下载。
#
# 用法：ffmpeg/build.sh <target> [输出目录，默认 ffmpeg/out]
#   macos-arm64 / macos-x64  在 Apple 芯片的 mac 上跑，x64 是交叉编译
#   linux-x64 / linux-arm64  在对应架构的 alpine 里跑：musl 全静态，不挑发行版
#   windows-x64              在 debian 里用 mingw-w64 交叉编译
#   sources                  只把源码包放进输出目录，和二进制一起发布（LGPL 要求提供源码）
# 要装的工具见 ffmpeg/install-deps.sh。
set -euo pipefail

FFMPEG_VERSION=9.0.2
FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
DAV1D_VERSION=1.5.4
DAV1D_SHA256=686616b7c69eb88d44459391ab25cac13b6647a3b288835c5784e71c1514a5c5

# 插件只让 ffmpeg 做一件事：把视频 / 图片解码、缩放、模糊、压暗，按 bgr0 写进管道（见 FfmpegFrameSource.command）。
# 格式对应 MediaFiles 里的扩展名；依赖的组件 configure 会自己补上（比如 webp 要用 vp8 解码器）。
DEMUXERS=mov,matroska,avi,asf,flv,mpegts,gif,apng,image2,image_jpeg_pipe,image_png_pipe,image_webp_pipe,image_bmp_pipe,image_tiff_pipe
# AV1 用 dav1d：ffmpeg 自带的 av1 解码器只能走硬件
DECODERS=libdav1d,h264,hevc,vp8,vp9,mpeg4,msmpeg4v1,msmpeg4v2,msmpeg4v3,wmv1,wmv2,wmv3,vc1,flv,h263,mpeg1video,mpeg2video,prores,mjpeg,png,apng,gif,webp,bmp,tiff
PARSERS=h264,hevc,vp8,vp9,av1,mpeg4video,mpegvideo,vc1,h263,mjpeg,png,gif,webp,bmp
# 设置里的帧率、缩放、模糊、压暗；后几个是 ffmpeg 给带旋转信息的视频（手机竖拍）自动加的。
# 压暗不能用 eq，它要 --enable-gpl
FILTERS=fps,scale,avgblur,lutyuv,format,null,transpose,hflip,vflip,rotate
# 「硬件解码」选项（只在 mac 上生效）
VT_HWACCELS=h264_videotoolbox,hevc_videotoolbox,vp9_videotoolbox,mpeg1_videotoolbox,mpeg2_videotoolbox,mpeg4_videotoolbox,h263_videotoolbox,prores_videotoolbox

TARGET=${1:?用法：ffmpeg/build.sh <target> [输出目录]}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${2:-$HERE/out}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
SRC=$HERE/work/src
WORK=$HERE/work/$TARGET
JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

# 下载到 work/src，校验过的不重复下
fetch() {
    local url=$1 sum=$2 file=$SRC/${1##*/}
    mkdir -p "$SRC"
    if [ ! -f "$file" ] || [ "$(sha256 "$file")" != "$sum" ]; then
        curl -fL --retry 5 --retry-all-errors -o "$file.part" "$url"
        mv "$file.part" "$file"
        if [ "$(sha256 "$file")" != "$sum" ]; then
            echo "校验不对：$file" >&2
            exit 1
        fi
    fi
    echo "$file"
}

FFMPEG_TAR=$(fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "$FFMPEG_SHA256")
DAV1D_TAR=$(fetch "https://downloads.videolan.org/pub/videolan/dav1d/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.xz" "$DAV1D_SHA256")

if [ "$TARGET" = sources ]; then
    cp "$FFMPEG_TAR" "$DAV1D_TAR" "$OUT/"
    exit 0
fi

EXE=ffmpeg
DAV1D_CROSS=()
FFMPEG_ARGS=()
case $TARGET in
    macos-arm64)
        export MACOSX_DEPLOYMENT_TARGET=11.0
        FFMPEG_ARGS=(--enable-videotoolbox "--enable-hwaccel=$VT_HWACCELS")
        ;;
    macos-x64)
        export MACOSX_DEPLOYMENT_TARGET=11.0
        DAV1D_CROSS=(--cross-file "$WORK/cross.ini")
        CROSS_C="['clang', '-arch', 'x86_64']" CROSS_SYSTEM=darwin CROSS_PREFIX=""
        FFMPEG_ARGS=(--enable-cross-compile --target-os=darwin --arch=x86_64 --cc=clang
            "--extra-cflags=-arch x86_64" "--extra-ldflags=-arch x86_64" --x86asmexe=nasm
            --enable-videotoolbox "--enable-hwaccel=$VT_HWACCELS")
        ;;
    linux-x64 | linux-arm64)
        # musl 线程的默认栈只有 128K，解码线程不够用；链接时写进程序头，musl 会按它给新线程分配栈
        FFMPEG_ARGS=("--extra-ldexeflags=-static -Wl,-z,stack-size=2097152")
        ;;
    windows-x64)
        EXE=ffmpeg.exe
        DAV1D_CROSS=(--cross-file "$WORK/cross.ini")
        CROSS_C="'x86_64-w64-mingw32-gcc'" CROSS_SYSTEM=windows CROSS_PREFIX=x86_64-w64-mingw32-
        FFMPEG_ARGS=(--enable-cross-compile --target-os=mingw32 --arch=x86_64
            --cross-prefix=x86_64-w64-mingw32- --extra-ldexeflags=-static)
        ;;
    *)
        echo "不认识的 target：$TARGET" >&2
        exit 1
        ;;
esac

rm -rf "$WORK"
mkdir -p "$WORK"
PREFIX=$WORK/prefix
# 只认自己编的库，免得找到系统里（比如 Homebrew）装的动态库
export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig
export PKG_CONFIG_PATH=

if [ ${#DAV1D_CROSS[@]} -gt 0 ]; then
    cat > "$WORK/cross.ini" <<EOF
[binaries]
c = $CROSS_C
ar = '${CROSS_PREFIX}ar'
strip = '${CROSS_PREFIX}strip'
nasm = 'nasm'

[host_machine]
system = '$CROSS_SYSTEM'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
EOF
fi

echo "== dav1d $DAV1D_VERSION"
tar -xf "$DAV1D_TAR" -C "$WORK"
meson setup "$WORK/dav1d-build" "$WORK/dav1d-$DAV1D_VERSION" ${DAV1D_CROSS[@]+"${DAV1D_CROSS[@]}"} \
    --prefix="$PREFIX" --libdir=lib --buildtype=release --default-library=static \
    -Denable_tools=false -Denable_tests=false
ninja -C "$WORK/dav1d-build" install

echo "== ffmpeg $FFMPEG_VERSION"
tar -xf "$FFMPEG_TAR" -C "$WORK"
CONFIGURE=(
    --pkg-config=pkg-config --pkg-config-flags=--static
    --enable-static --disable-shared
    --disable-everything --disable-autodetect
    --disable-doc --disable-debug --disable-network --disable-avdevice --disable-ffplay --disable-ffprobe
    --enable-zlib --enable-libdav1d
    --enable-protocol=file,pipe
    "--enable-demuxer=$DEMUXERS" "--enable-decoder=$DECODERS" "--enable-parser=$PARSERS" "--enable-filter=$FILTERS"
    --enable-encoder=rawvideo --enable-muxer=rawvideo
    "${FFMPEG_ARGS[@]}"
)
(
    cd "$WORK/ffmpeg-$FFMPEG_VERSION"
    ./configure "${CONFIGURE[@]}" || { tail -50 ffbuild/config.log; exit 1; }
    # 没开 --enable-gpl / --enable-nonfree，这里再确认一遍
    grep -q '^#define CONFIG_GPL 0' config.h
    grep -q '^#define CONFIG_NONFREE 0' config.h
    make -j"$JOBS"
)

echo "== 打包"
STAGE=$WORK/stage
mkdir -p "$STAGE"
# 编译目录里的 ffmpeg 是 strip 过的（ffmpeg_g 才带调试信息）。不 make install，免得 -version 里带上本机路径
cp "$WORK/ffmpeg-$FFMPEG_VERSION/$EXE" "$STAGE/"
# Apple 芯片只跑有签名的程序。链接器会自动加临时签名，strip 之后再签一次保险
case $TARGET in macos-*) codesign --force --sign - "$STAGE/$EXE" ;; esac
{
    echo "ffmpeg $FFMPEG_VERSION, built for Live Background (https://github.com/chen12311/intellijLiveBackground)."
    echo "It is licensed under the GNU LGPL 2.1 or later; dav1d is licensed under the BSD 2-clause license."
    echo "Source code: ffmpeg-$FFMPEG_VERSION.tar.xz and dav1d-$DAV1D_VERSION.tar.xz in the same GitHub release."
    echo
    echo "===== ffmpeg: COPYING.LGPLv2.1 ====="
    cat "$WORK/ffmpeg-$FFMPEG_VERSION/COPYING.LGPLv2.1"
    echo
    echo "===== dav1d: COPYING ====="
    cat "$WORK/dav1d-$DAV1D_VERSION/COPYING"
} > "$STAGE/LICENSE.txt"
{
    echo "target: $TARGET"
    echo "ffmpeg: $FFMPEG_VERSION  https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz  sha256 $FFMPEG_SHA256"
    echo "dav1d: $DAV1D_VERSION  https://downloads.videolan.org/pub/videolan/dav1d/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.xz  sha256 $DAV1D_SHA256"
    echo "build script: ffmpeg/build.sh in https://github.com/chen12311/intellijLiveBackground"
    echo "configure: ${CONFIGURE[*]}"
} > "$STAGE/BUILD.txt"
rm -f "$OUT/ffmpeg-$TARGET.zip"
(cd "$STAGE" && zip -9 -X "$OUT/ffmpeg-$TARGET.zip" "$EXE" LICENSE.txt BUILD.txt)
ls -l "$STAGE/$EXE" "$OUT/ffmpeg-$TARGET.zip"
