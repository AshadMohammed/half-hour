#!/bin/bash
# Builds build/halfhour.apk with the bare Android tools, no Android Studio or Gradle.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export PATH="$JAVA_HOME/bin:$PATH"
VERSION_CODE=${VERSION_CODE:-$(date +%s)}

rm -rf build
mkdir -p build/classes build/dex build/gen

# Resources first: they produce the R class the code refers to. -A packs assets/app.html into the apk.
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
  --java build/gen --min-sdk-version 31 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "0.1" -A assets build/res.zip

# The lambda stubs stand in for Java pieces Android's library leaves out; d8 rewrites lambdas anyway.
javac -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -Xlint:-options -Xlint:unchecked \
  -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 31 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

# One local signing key, kept so later builds install over the old one.
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias halfhour -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Half Hour, O=Personal, C=IN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/halfhour.apk build/aligned.apk
"$BT/apksigner" verify build/halfhour.apk

echo "Built $(pwd)/build/halfhour.apk"
