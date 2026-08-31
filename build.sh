#!/usr/bin/env bash
set -euo pipefail

echo "=========================================="
echo "      MultiBooter F-Droid BUILD"
echo "=========================================="
echo

ANDROID_API="${ANDROID_API:-34}"
NDK_VERSION="${NDK_VERSION:-26.1.10909125}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-34.0.0}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"

PLATFORM="$ANDROID_HOME/platforms/android-$ANDROID_API/android.jar"
BUILD_TOOLS="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
NDK_BIN="$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$NDK_BIN/clang"

AAPT2="$BUILD_TOOLS/aapt2"
AAPT="$BUILD_TOOLS/aapt"
ZIPALIGN="$BUILD_TOOLS/zipalign"

R8_JAR="${R8_JAR:-$ANDROID_HOME/r8/r8.jar}"
PROGUARD="$(pwd)/proguard-rules.pro"

echo "[0/12] Ortam kontrol ediliyor..."

[[ -f "$PLATFORM" ]] || { echo "[ERROR] android.jar bulunamadi: $PLATFORM"; exit 1; }
[[ -x "$CLANG" ]] || { echo "[ERROR] NDK clang bulunamadi: $CLANG"; exit 1; }
[[ -x "$AAPT2" ]] || { echo "[ERROR] aapt2 bulunamadi: $AAPT2"; exit 1; }
[[ -x "$AAPT" ]] || { echo "[ERROR] aapt bulunamadi: $AAPT"; exit 1; }
[[ -x "$ZIPALIGN" ]] || { echo "[ERROR] zipalign bulunamadi: $ZIPALIGN"; exit 1; }
[[ -f "$PROGUARD" ]] || { echo "[ERROR] proguard-rules.pro bulunamadi."; exit 1; }

command -v javac >/dev/null 2>&1 || { echo "[ERROR] javac bulunamadi."; exit 1; }
command -v jar >/dev/null 2>&1 || { echo "[ERROR] jar bulunamadi."; exit 1; }
command -v java >/dev/null 2>&1 || { echo "[ERROR] java bulunamadi."; exit 1; }

R8_BIN=""

if [[ ! -f "$R8_JAR" ]]; then
    if [[ -f "$BUILD_TOOLS/lib/r8.jar" ]]; then
        R8_JAR="$BUILD_TOOLS/lib/r8.jar"
    elif [[ -x "$BUILD_TOOLS/r8" ]]; then
        R8_BIN="$BUILD_TOOLS/r8"
    else
        echo "[ERROR] R8 bulunamadi."
        exit 1
    fi
fi

echo "[OK] Ortam hazir."
echo

echo "[1/12] Eski build temizleniyor..."

rm -rf gen obj r8-out lib
rm -f compiled_res.zip sources.txt classes-input.jar classes.dex
rm -f app-unaligned.apk app-aligned.apk app-release.apk app-release-unsigned.apk

mkdir -p gen obj r8-out
mkdir -p lib/arm64-v8a lib/armeabi-v7a lib/x86 lib/x86_64
mkdir -p src/main/assets

rm -f src/main/assets/ffs_gadget src/main/assets/dnsmasq

echo "[OK] Temizlik tamam."
echo

echo "[2/12] Native kodlar 4 ABI icin derleniyor..."

for file in libgadget.c libscsi.c libtftp.c libexfat.c libfunctionfs.c; do
    [[ -f "jni/$file" ]] || { echo "[ERROR] jni/$file bulunamadi."; exit 1; }
done

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    case "$abi" in
        arm64-v8a)
            target="aarch64-linux-android$ANDROID_API"
            ;;
        armeabi-v7a)
            target="armv7a-linux-androideabi$ANDROID_API"
            ;;
        x86)
            target="i686-linux-android$ANDROID_API"
            ;;
        x86_64)
            target="x86_64-linux-android$ANDROID_API"
            ;;
        *)
            echo "[ERROR] Bilinmeyen ABI: $abi"
            exit 1
            ;;
    esac

    echo
    echo "[ABI $abi] Target: $target"

    echo "[$abi 1/5] libgadget.so"
    "$CLANG" --target="$target" -shared -fPIC -O2 -Wall -Wextra jni/libgadget.c -o "lib/$abi/libgadget.so"

    echo "[$abi 2/5] libscsi.so"
    "$CLANG" --target="$target" -shared -fPIC -O2 -Wall -Wextra jni/libscsi.c -o "lib/$abi/libscsi.so"

    echo "[$abi 3/5] libtftp.so"
    "$CLANG" --target="$target" -shared -fPIC -O2 -Wall -Wextra jni/libtftp.c -llog -o "lib/$abi/libtftp.so"

    echo "[$abi 4/5] libexfat.so"
    "$CLANG" --target="$target" -shared -fPIC -O2 -Wall -Wextra jni/libexfat.c -llog -o "lib/$abi/libexfat.so"

    echo "[$abi 5/5] libfunctionfs.so"
    "$CLANG" --target="$target" -shared -fPIC -O2 -Wall -Wextra jni/libfunctionfs.c -pthread -llog -o "lib/$abi/libfunctionfs.so"
done

echo
echo "[OK] Native kutuphaneler derlendi."
echo

echo "[3/12] dnsmasq 4 ABI icin derleniyor..."

DNSMASQ_SRC="src/native/dnsmasq/src"
DNSMASQ_ASSETS="src/main/assets"

if [[ -f "$DNSMASQ_SRC/dnsmasq.c" ]]; then
    mapfile -d '' DNSMASQ_SOURCES < <(find "$DNSMASQ_SRC" -maxdepth 1 -type f -name '*.c' -print0 | sort -z)

    [[ ${#DNSMASQ_SOURCES[@]} -gt 0 ]] || { echo "[ERROR] dnsmasq C kaynaklari bulunamadi."; exit 1; }

    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        case "$abi" in
            arm64-v8a)
                target="aarch64-linux-android$ANDROID_API"
                output="$DNSMASQ_ASSETS/dnsmasq-arm64-v8a"
                ;;
            armeabi-v7a)
                target="armv7a-linux-androideabi$ANDROID_API"
                output="$DNSMASQ_ASSETS/dnsmasq-armeabi-v7a"
                ;;
            x86)
                target="i686-linux-android$ANDROID_API"
                output="$DNSMASQ_ASSETS/dnsmasq-x86"
                ;;
            x86_64)
                target="x86_64-linux-android$ANDROID_API"
                output="$DNSMASQ_ASSETS/dnsmasq-x86_64"
                ;;
            *)
                echo "[ERROR] Bilinmeyen dnsmasq ABI: $abi"
                exit 1
                ;;
        esac

        echo
        echo "[dnsmasq $abi] Target: $target"

        "$CLANG" \
            --target="$target" \
            -O2 \
            -fPIE \
            -pie \
            -DNO_IPV6 \
            -DNO_DBUS \
            '-DVERSION="2.89"' \
            -DETHER_ADDR_LEN=6 \
            -Wno-macro-redefined \
            "${DNSMASQ_SOURCES[@]}" \
            -llog \
            -o "$output"

        [[ -f "$output" ]] || { echo "[ERROR] dnsmasq $abi cikti dosyasi olusmadi."; exit 1; }

        echo "[OK] $abi dnsmasq hazir."
    done
else
    echo "[INFO] dnsmasq kaynaklari bulunamadi; mevcut 4 ABI asset kontrol ediliyor."

    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        [[ -f "$DNSMASQ_ASSETS/dnsmasq-$abi" ]] || {
            echo "[ERROR] $DNSMASQ_ASSETS/dnsmasq-$abi bulunamadi."
            exit 1
        }
    done
fi

echo
echo "[OK] dnsmasq 4 ABI icin hazir."
echo

echo "[4/12] Resources derleniyor..."

"$AAPT2" compile --dir res -o compiled_res.zip

echo "[OK] Resources compile edildi."
echo

echo "[5/12] Resources ve assets link ediliyor..."

"$AAPT2" link \
    -o app-unaligned.apk \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    -R compiled_res.zip \
    -A src/main/assets \
    --auto-add-overlay \
    --java gen

echo "[OK] Resources ve assets eklendi."
echo

echo "[6/12] Java kaynaklari derleniyor..."

: > sources.txt

while IFS= read -r -d '' file; do
    printf '"%s"\n' "$file" >> sources.txt
done < <(find src gen -type f -name '*.java' -print0)

javac --release 8 -encoding UTF-8 -d obj -cp "$PLATFORM" @sources.txt

echo "[OK] Java derlendi."
echo

echo "[7/12] Class dosyalari JAR yapiliyor..."

jar cf classes-input.jar -C obj .

echo "[OK] classes-input.jar hazir."
echo

echo "[8/12] R8 shrink + optimize + obfuscate..."

if [[ -n "$R8_BIN" ]]; then
    "$R8_BIN" \
        --release \
        --min-api 26 \
        --lib "$PLATFORM" \
        --output r8-out \
        --pg-conf "$PROGUARD" \
        classes-input.jar
else
    java \
        -cp "$R8_JAR" \
        com.android.tools.r8.R8 \
        --release \
        --min-api 26 \
        --lib "$PLATFORM" \
        --output r8-out \
        --pg-conf "$PROGUARD" \
        classes-input.jar
fi

[[ -f r8-out/classes.dex ]] || { echo "[ERROR] classes.dex olusmadi."; exit 1; }

echo "[OK] R8 tamamlandi."
echo

echo "[9/12] DEX ve native kutuphaneler APK'ya ekleniyor..."

cp r8-out/classes.dex classes.dex
jar uf app-unaligned.apk classes.dex lib

rm -f classes.dex classes-input.jar
rm -rf r8-out

echo "[OK] DEX ve native kutuphaneler eklendi."
echo

echo "[10/12] APK icerigi kontrol ediliyor..."

"$AAPT" list app-unaligned.apk | grep -Fxq "classes.dex" || {
    echo "[ERROR] classes.dex APK icinde yok."
    exit 1
}

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    for libname in libgadget.so libscsi.so libtftp.so libexfat.so libfunctionfs.so; do
        "$AAPT" list app-unaligned.apk | grep -Fxq "lib/$abi/$libname" || {
            echo "[ERROR] lib/$abi/$libname APK icinde yok."
            exit 1
        }
    done
done

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    "$AAPT" list app-unaligned.apk | grep -Fxq "assets/dnsmasq-$abi" || {
        echo "[ERROR] assets/dnsmasq-$abi APK icinde yok."
        exit 1
    }
done

echo "[OK] APK icerigi dogru."
echo

echo "[11/12] APK align ediliyor..."

"$ZIPALIGN" -f -p 4 app-unaligned.apk app-release-unsigned.apk

echo "[OK] APK align edildi."
echo

echo "[12/12] APK align durumu dogrulaniyor..."

"$ZIPALIGN" -c -p 4 app-release-unsigned.apk

echo
echo "=========================================="
echo "      F-DROID BUILD BASARILI"
echo "=========================================="
echo
echo "APK: $(pwd)/app-release-unsigned.apk"
echo "APK boyutu: $(stat -c%s app-release-unsigned.apk) bytes"
echo
