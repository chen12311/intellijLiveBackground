#!/usr/bin/env bash
# 把 GitHub Release 里的 downloads.properties（地址、大小、SHA-256）拷进插件，插件按它下载、核对 ffmpeg。
# 用法：ffmpeg/update-manifest.sh ffmpeg-9.0.2-1
set -euo pipefail

TAG=${1:?用法：ffmpeg/update-manifest.sh <Release 标签，比如 ffmpeg-9.0.2-1>}
REPO=chen12311/intellijLiveBackground
HERE=$(cd "$(dirname "$0")" && pwd)
DEST=$HERE/../src/main/resources/ffmpeg/downloads.properties

mkdir -p "$(dirname "$DEST")"
curl -fL --retry 3 -o "$DEST.part" "https://github.com/$REPO/releases/download/$TAG/downloads.properties"
grep -q "^version=${TAG#ffmpeg-}$" "$DEST.part" || { echo "下载到的不是 $TAG 的清单" >&2; rm -f "$DEST.part"; exit 1; }
mv "$DEST.part" "$DEST"
cat "$DEST"
