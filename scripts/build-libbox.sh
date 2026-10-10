#!/usr/bin/env bash
#
# 构建 V2compose 内嵌的 sing-box 核心（libbox.aar）。
#
# sing-box 核心是 Go 代码，通过 gomobile 绑定为 Android AAR。
# 产物体积较大（数十 MB），因此不纳入版本控制，需要本地/CI 生成：
#
#   ./scripts/build-libbox.sh
#
# 依赖：Go >= 1.24、JDK 17、Android SDK + NDK（ANDROID_HOME / ANDROID_NDK_HOME）。
# 国内网络建议设置 GOPROXY，例如：
#   GOPROXY=https://goproxy.cn,direct ./scripts/build-libbox.sh
#
set -euo pipefail

SING_BOX_VERSION="${SING_BOX_VERSION:-v1.14.3}"
GOMOBILE_VERSION="${GOMOBILE_VERSION:-v0.1.13}"
# 目标 ABI，默认只编 arm64-v8a（现代 Android 设备）。
# 注意：Go 1.26 下编 386 / amd64 会触发编译器 ICE，因此不建议直接用 android（全 ABI）。
SING_BOX_PLATFORM="${SING_BOX_PLATFORM:-android/arm64}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_DIR="${SING_BOX_BUILD_DIR:-/tmp/v2compose-sing-box-build}"
OUTPUT="${PROJECT_DIR}/shared/libs/libbox.aar"

: "${ANDROID_HOME:?请先设置 ANDROID_HOME}"
: "${ANDROID_NDK_HOME:?请先设置 ANDROID_NDK_HOME}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="${JAVA_HOME}/bin:${PATH}"
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"

echo "==> sing-box ${SING_BOX_VERSION} / gomobile ${GOMOBILE_VERSION}"

if [ ! -d "${BUILD_DIR}/sing-box" ]; then
  git clone --depth 1 --branch "${SING_BOX_VERSION}" \
    https://github.com/SagerNet/sing-box.git "${BUILD_DIR}/sing-box"
else
  echo "==> 复用已有源码目录 ${BUILD_DIR}/sing-box"
fi

go install -v "github.com/sagernet/gomobile/cmd/gomobile@${GOMOBILE_VERSION}"
go install -v "github.com/sagernet/gomobile/cmd/gobind@${GOMOBILE_VERSION}"
export PATH="$(go env GOPATH)/bin:${PATH}"

cd "${BUILD_DIR}/sing-box"
# -platform 指定 ABI；产物为 libbox.aar 与 libbox-legacy.aar
echo "==> 构建 ABI: ${SING_BOX_PLATFORM}"
go run ./cmd/internal/build_libbox -target android -platform "${SING_BOX_PLATFORM}"

mkdir -p "$(dirname "${OUTPUT}")"
cp libbox.aar "${OUTPUT}"

echo "==> 已生成 ${OUTPUT}"
ls -lh "${OUTPUT}"
