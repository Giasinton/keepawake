#!/usr/bin/env bash
#
# 在隔离环境里把项目编译成可安装的 APK（不依赖 Gradle，不写 ~/.gradle）。
#
# 复用纪念日(CountDay)的构建环境（只读使用，不做任何修改）：
#   $KEEPAWAKE_ENV/jdk                      JDK 17
#   $KEEPAWAKE_ENV/sdk/build-tools/34.0.0   aapt2 / d8 / zipalign / apksigner
#   $KEEPAWAKE_ENV/sdk/platforms/android-34/android.jar
#   $KEEPAWAKE_ENV/debug.keystore           调试签名（沿用 CountDay 那把，只读）
#
# 用法： ./build.sh [--clean]
#   DEBUGGABLE=1 ./build.sh   额外打上 android:debuggable（便于 run-as 调试）
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DIR="${KEEPAWAKE_ENV:-${COUNTDAY_ENV:-$HOME/.countday-env}}"
JAVA_HOME="${JAVA_HOME:-$ENV_DIR/jdk}"
SDK_ROOT="${ANDROID_SDK_ROOT:-$ENV_DIR/sdk}"

API_LEVEL="${API_LEVEL:-34}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-34.0.0}"
MIN_SDK="${MIN_SDK:-26}"
VERSION_CODE="${VERSION_CODE:-2}"
VERSION_NAME="${VERSION_NAME:-1.1}"
APP_ID="${APP_ID:-io.giasinton.keepawake}"

BUILD_TOOLS="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_ROOT/platforms/android-$API_LEVEL/android.jar"
KEYSTORE="$ENV_DIR/debug.keystore"

BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
MANIFEST_SRC="$PROJECT_DIR/app/src/main/AndroidManifest.xml"
JAVA_SRC="$PROJECT_DIR/app/src/main/java"
RES_DIR="$PROJECT_DIR/app/src/main/res"

die() { echo "错误：$*" >&2; exit 1; }

[ -x "$JAVA_HOME/bin/javac" ] || die "找不到 JDK：$JAVA_HOME（可设置 JAVA_HOME 或 KEEPAWAKE_ENV/COUNTDAY_ENV）"
[ -x "$BUILD_TOOLS/aapt2" ] || die "找不到 build-tools：$BUILD_TOOLS（运行 $COUNTDAY_ENV/setup-sdk.sh 或 $HOME/.countday-env/setup-sdk.sh）"
[ -f "$ANDROID_JAR" ] || die "找不到 android.jar：$ANDROID_JAR"
# 环境里的签名证书只读复用，绝不修改环境目录
[ -f "$KEYSTORE" ] || die "找不到签名证书：$KEYSTORE"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ "${1:-}" = "--clean" ]; then
  rm -rf "$BUILD_DIR" "$DIST_DIR"
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$DIST_DIR"

echo "==> 1/7 编译资源 (aapt2 compile)"
"$BUILD_TOOLS/aapt2" compile --dir "$RES_DIR" -o "$BUILD_DIR/res.zip"

echo "==> 2/7 生成清单 + 资源表 (aapt2 link)"
# 清单里不写 package（与 AGP 8 的 namespace 习惯一致），aapt2 link 又必须有，
# 所以只在构建产物里临时注入。
sed "s|<manifest |<manifest package=\"$APP_ID\" |" "$MANIFEST_SRC" > "$BUILD_DIR/AndroidManifest.xml"
# 调试构建：加 android:debuggable，便于用 run-as 往私有目录塞测试数据
if [ "${DEBUGGABLE:-0}" = "1" ]; then
  sed -i '0,/<application/s|<application|<application android:debuggable="true"|' "$BUILD_DIR/AndroidManifest.xml"
  grep -q 'debuggable' "$BUILD_DIR/AndroidManifest.xml" || die "注入 android:debuggable 失败"
fi

"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD_DIR/app-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$API_LEVEL" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$BUILD_DIR/res.zip"

echo "==> 3/7 编译 Java (javac)"
find "$JAVA_SRC" "$BUILD_DIR/gen" -name '*.java' > "$BUILD_DIR/sources.txt"
"$JAVA_HOME/bin/javac" \
  -encoding UTF-8 \
  -source 8 -target 8 \
  -Xlint:-options \
  -bootclasspath "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar" \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/sources.txt"

echo "==> 4/7 转换 dex (d8)"
find "$BUILD_DIR/classes" -name '*.class' > "$BUILD_DIR/classes.txt"
"$BUILD_TOOLS/d8" \
  --release \
  --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output "$BUILD_DIR/dex" \
  @"$BUILD_DIR/classes.txt"

echo "==> 5/7 打包 APK"
# resources.arsc 必须不压缩，否则 Android 11+ (targetSdk 30+) 拒绝安装
python3 - "$BUILD_DIR/app-unsigned.apk" "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/app-packaged.apk" <<'PY'
import sys, zipfile

src, dex_path, dst = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == 'resources.arsc':
            stored = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            stored.compress_type = zipfile.ZIP_STORED
            zout.writestr(stored, data)
        else:
            zout.writestr(item, data)
    with open(dex_path, 'rb') as fh:
        entry = zipfile.ZipInfo('classes.dex', date_time=(2026, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_STORED
        zout.writestr(entry, fh.read())
PY

echo "==> 6/7 对齐 (zipalign)"
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/app-packaged.apk" "$BUILD_DIR/app-aligned.apk"

echo "==> 7/7 签名 (apksigner)"
OUT_APK="$DIST_DIR/keepawake-$VERSION_NAME${DEBUGGABLE_SUFFIX:-}.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --v4-signing-enabled false \
  --out "$OUT_APK" \
  "$BUILD_DIR/app-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$OUT_APK" | head -6
echo
echo "完成： $OUT_APK  ($(du -h "$OUT_APK" | cut -f1))"
echo "安装： adb install -r \"$OUT_APK\""
