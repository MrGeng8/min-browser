#!/usr/bin/env bash
#
# Min Browser —— 手动构建脚本（不依赖 Gradle）
#
# 依赖：
#   * JDK 17 或更高（需要 javac / keytool）
#   * Android SDK：任意较新的 build-tools + platforms/android-28
#
# 用法：
#   bash app/build.sh
#   ANDROID_SDK_ROOT=/path/to/sdk bash app/build.sh
#
# 产物：app/build/Min.apk
#
set -euo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$APP/build"

# ---------------------------------------------------------------- 定位 SDK
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  for c in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
           /opt/android-sdk /usr/lib/android-sdk /opt/homebrew/share/android-commandlinetools; do
    if [ -d "$c" ]; then SDK="$c"; break; fi
  done
fi
[ -n "$SDK" ] || { echo "错误：未找到 Android SDK，请设置 ANDROID_SDK_ROOT" >&2; exit 1; }

BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
BT="${BT%/}"
if [ -z "$BT" ] || [ ! -x "$BT/aapt2" ]; then
  echo "错误：$SDK/build-tools 下没有可用的 aapt2" >&2; exit 1
fi

AJAR="$SDK/platforms/android-28/android.jar"
[ -f "$AJAR" ] || {
  echo "错误：缺少 $AJAR" >&2
  echo "请执行：sdkmanager \"platforms;android-28\"" >&2
  exit 1
}

for t in javac keytool zip python3; do
  command -v "$t" >/dev/null 2>&1 || { echo "错误：缺少命令 $t" >&2; exit 1; }
done

KS="$APP/min.keystore"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "SDK        : $SDK"
echo "build-tools: $BT"
echo

echo "==> 1/7 生成图标资源"
python3 "$APP/make_icon.py"

echo
echo "==> 2/7 aapt2 compile 资源"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo
echo "==> 3/7 aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 21 \
  --target-sdk-version 28 \
  "$OUT/res.zip"

echo
echo "==> 4/7 javac"
javac -source 8 -target 8 -nowarn \
  -classpath "$AJAR" \
  -d "$OUT/classes" \
  $(find "$APP/src" -name '*.java') 2>&1 | grep -v "bootstrap class path" || true

echo
echo "==> 5/7 d8 -> classes.dex"
"$BT/d8" --release --min-api 21 --lib "$AJAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex)

echo
echo "==> 6/7 zipalign"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo
echo "==> 7/7 签名"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias minkey \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Min, O=Min, C=CN" >/dev/null 2>&1
  echo "  已生成自签名密钥库 app/min.keystore"
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-key-alias minkey \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/Min.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify "$OUT/Min.apk" >/dev/null && echo "  签名校验通过"

echo
echo "================ 完成 ================"
printf "APK: %s  (%s 字节)\n" "$OUT/Min.apk" "$(wc -c < "$OUT/Min.apk" | tr -d ' ')"
echo
echo "安装： adb install -r $OUT/Min.apk"
