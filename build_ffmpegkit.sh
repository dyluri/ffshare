#!/bin/sh
set -eu

OUTPUT="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)/app/libs"
PREBUILT=false
if [ "${1:-}" = "--prebuilt" ]; then
  PREBUILT=true
  shift
fi
if [ -n "${1:-}" ]; then
  OUTPUT="$1"
fi
mkdir -p "$OUTPUT"

FFMPEG_KIT_TAG_VERSION=v8.1.1

if [ "$PREBUILT" = true ]; then
  # Community full-GPL build of the upstream v8.1.1 tag, not an Arthenica binary.
  ARCHIVE="$(mktemp -d)"
  trap 'rm -rf "$ARCHIVE"' EXIT HUP INT TERM
  curl --fail --location --retry 3 \
    "https://github.com/XiaoHao560/ffmpeg-kit-next/releases/download/$FFMPEG_KIT_TAG_VERSION/ffmpeg-kit-next-maven.zip" \
    --output "$ARCHIVE/ffmpeg-kit-next-maven.zip"
  echo "ee31b07422d78c86ccc23c842dae676ba30693333c91c2f5f3f36e817ee7f491  $ARCHIVE/ffmpeg-kit-next-maven.zip" | sha256sum --check -
  unzip -p "$ARCHIVE/ffmpeg-kit-next-maven.zip" \
    com/arthenica/ffmpeg-kit-next/8.1.1/ffmpeg-kit-next-8.1.1.aar \
    > "$ARCHIVE/ffmpeg-kit-next-8.1.1.aar"
  install -m 644 "$ARCHIVE/ffmpeg-kit-next-8.1.1.aar" "$OUTPUT/ffmpeg-kit-next-8.1.1.aar"
  exit 0
fi

rm -rf "./ffmpeg-kit-next"

git clone --branch $FFMPEG_KIT_TAG_VERSION --depth=1 "https://github.com/arthenica/ffmpeg-kit-next"


# future
# --enable-libaom

cd ffmpeg-kit-next
./nix-android.sh -p android-r27d \
  --jobs="$(nproc)" \
  --disable-x86 --disable-x86-64 --disable-arm-v7a-neon \
  --enable-dav1d \
  --enable-fontconfig \
  --enable-freetype \
  --enable-fribidi \
  --enable-gmp \
  --enable-kvazaar \
  --enable-lame \
  --enable-libass \
  --enable-libiconv \
  --enable-libilbc \
  --enable-libtheora \
  --enable-libvorbis \
  --enable-libvpx \
  --enable-libwebp \
  --enable-libxml2 \
  --enable-opencore-amr \
  --enable-opus \
  --enable-shine \
  --enable-snappy \
  --enable-soxr \
  --enable-speex \
  --enable-twolame \
  --enable-vo-amrwbenc \
  --enable-vvenc \
  --enable-zimg \
  --enable-gpl \
  --enable-libvidstab \
  --enable-x264 \
  --enable-x265 \
  --enable-xvidcore \
  --enable-libjxl
cd ..

mv ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven/com/arthenica/ffmpeg-kit-next/8.1.1/ffmpeg-kit-next-8.1.1.aar "$OUTPUT"
