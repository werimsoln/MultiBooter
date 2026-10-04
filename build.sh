#!/usr/bin/env bash
set -euo pipefail

echo "=========================================="
echo "      MultiBooter F-Droid BUILD"
echo "=========================================="
echo

ANDROID_API="${ANDROID_API:-34}"
NDK_VERSION="${NDK_VERSION:-30.0.16248370}"
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

VENTOY_SRC="${VENTOY_SRC:-}"
VENTOY_IMAGE="src/main/assets/ventoy.disk.img"
VENTOY_WORK_DIR="${VENTOY_WORK_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/multibooter-ventoy.XXXXXX")}"

SOURCE_DATE_EPOCH="${VENTOY_IMAGE_EPOCH:-1735689600}"

cleanup() {
    rm -rf "$VENTOY_WORK_DIR"
}

trap cleanup EXIT

echo "[0/14] Ortam kontrol ediliyor..."

[[ -f "$PLATFORM" ]] || {
    echo "[ERROR] android.jar bulunamadi: $PLATFORM"
    exit 1
}

[[ -x "$CLANG" ]] || {
    echo "[ERROR] NDK clang bulunamadi: $CLANG"
    exit 1
}

[[ -x "$AAPT2" ]] || {
    echo "[ERROR] aapt2 bulunamadi: $AAPT2"
    exit 1
}

[[ -x "$AAPT" ]] || {
    echo "[ERROR] aapt bulunamadi: $AAPT"
    exit 1
}

[[ -x "$ZIPALIGN" ]] || {
    echo "[ERROR] zipalign bulunamadi: $ZIPALIGN"
    exit 1
}

[[ -f "$PROGUARD" ]] || {
    echo "[ERROR] proguard-rules.pro bulunamadi."
    exit 1
}

command -v javac >/dev/null 2>&1 || {
    echo "[ERROR] javac bulunamadi."
    exit 1
}

command -v jar >/dev/null 2>&1 || {
    echo "[ERROR] jar bulunamadi."
    exit 1
}

command -v java >/dev/null 2>&1 || {
    echo "[ERROR] java bulunamadi."
    exit 1
}

R8_BIN=""

if [[ ! -f "$R8_JAR" ]]; then
    if [[ -f "$BUILD_TOOLS/lib/r8.jar" ]]; then
        R8_JAR="$BUILD_TOOLS/lib/r8.jar"
    elif [[ -f "$BUILD_TOOLS/lib/d8.jar" ]]; then
        R8_JAR="$BUILD_TOOLS/lib/d8.jar"
    elif [[ -x "$BUILD_TOOLS/r8" ]]; then
        R8_BIN="$BUILD_TOOLS/r8"
    else
        echo "[ERROR] R8 bulunamadi."
        exit 1
    fi
fi

echo "[OK] Android build ortami hazir."
echo

echo "[1/14] Eski build temizleniyor..."

rm -rf gen obj r8-out lib
rm -f compiled_res.zip sources.txt classes-input.jar classes.dex
rm -f app-unaligned.apk app-aligned.apk app-release.apk app-release-unsigned.apk

mkdir -p gen obj r8-out
mkdir -p lib/arm64-v8a lib/armeabi-v7a lib/x86 lib/x86_64
mkdir -p src/main/assets

rm -f src/main/assets/ffs_gadget
rm -f src/main/assets/dnsmasq
rm -f src/main/assets/ventoy.disk.img.sha256

echo "[OK] Temizlik tamam."
echo

echo "[2/14] Ventoy kaynak kontrol ediliyor..."

if [[ -z "$VENTOY_SRC" ]]; then
    echo "[ERROR] VENTOY_SRC tanimli degil."
    echo "[ERROR] F-Droid build ortaminda Ventoy@v1.1.17 srclib yolu verilmelidir."
    exit 1
fi

[[ -d "$VENTOY_SRC" ]] || {
    echo "[ERROR] Ventoy kaynak dizini bulunamadi: $VENTOY_SRC"
    exit 1
}

[[ -d "$VENTOY_SRC/INSTALL" ]] || {
    echo "[ERROR] Ventoy INSTALL klasoru bulunamadi: $VENTOY_SRC/INSTALL"
    exit 1
}

[[ -d "$VENTOY_SRC/INSTALL/grub" ]] || {
    echo "[ERROR] $VENTOY_SRC/INSTALL/grub bulunamadi."
    exit 1
}

[[ -d "$VENTOY_SRC/INSTALL/EFI" ]] || {
    echo "[ERROR] $VENTOY_SRC/INSTALL/EFI bulunamadi."
    exit 1
}

[[ -d "$VENTOY_SRC/INSTALL/ventoy" ]] || {
    echo "[ERROR] $VENTOY_SRC/INSTALL/ventoy bulunamadi."
    exit 1
}

echo "[OK] Ventoy kaynaklari bulundu: $VENTOY_SRC"
echo

echo "[3/14] Ventoy disk image icin gerekli araclar kontrol ediliyor..."

for tool in awk date dd faketime find grep mkfs.vfat mcopy mmd gzip sha256sum sort tar touch; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "[ERROR] Gerekli arac bulunamadi: $tool"
        exit 1
    }
done

echo "[OK] Ventoy image araclari hazir."
echo

echo "[4/14] Ventoy disk image yeniden olusturuluyor..."

export SOURCE_DATE_EPOCH
export TZ=UTC

INSTALL_DIR="$VENTOY_SRC/INSTALL"
GRUB_DIR="$INSTALL_DIR/grub"

[[ -f "$GRUB_DIR/grub.cfg" ]] || {
    echo "[ERROR] Ventoy grub.cfg bulunamadi."
    exit 1
}

mkdir -p "$VENTOY_WORK_DIR/root/grub"
mkdir -p "$VENTOY_WORK_DIR/root/tool"

cp -a "$GRUB_DIR/grub.cfg" "$VENTOY_WORK_DIR/root/grub/"

find "$GRUB_DIR" \
    -mindepth 1 \
    -maxdepth 1 \
    ! -name grub.cfg \
    -exec cp -a '{}' "$VENTOY_WORK_DIR/root/grub/" ';'

(
    cd "$VENTOY_WORK_DIR/root/grub"

    tar \
        --sort=name \
        --mtime="@$SOURCE_DATE_EPOCH" \
        --owner=0 \
        --group=0 \
        --numeric-owner \
        -cf - \
        ./help | gzip -n > help.tar.gz

    rm -rf ./help

    if [[ -d menu ]]; then
        vtlangtitle="$(
            grep VTLANG_LANGUAGE_NAME menu/zh_CN.json |
            awk -F\" '{print $4}'
        )"

        {
            echo "menuentry \"zh_CN  -  $vtlangtitle\" --class=menu_lang_item --class=debug_menu_lang --class=F5tool {"
            echo "    vt_load_menu_lang zh_CN"
            echo "}"

            find menu \
                -mindepth 1 \
                -maxdepth 1 \
                -type f \
                ! -name zh_CN.json \
                -printf '%f\n' |
                sort |
                while read -r vtlang; do
                    vtlangname="${vtlang%.*}"
                    vtlangtitle="$(
                        grep VTLANG_LANGUAGE_NAME "menu/$vtlang" |
                        awk -F\" '{print $4}'
                    )"

                    echo "menuentry \"$vtlangname  -  $vtlangtitle\" --class=menu_lang_item --class=debug_menu_lang --class=F5tool {"
                    echo "    vt_load_menu_lang $vtlangname"
                    echo "}"
                done

            echo 'menuentry "$VTLANG_RETURN_PREVIOUS" --class=vtoyret VTOY_RET {'
            echo '        echo "Return ..."'
            echo "}"
        } > menulang.cfg

        tar \
            --sort=name \
            --mtime="@$SOURCE_DATE_EPOCH" \
            --owner=0 \
            --group=0 \
            --numeric-owner \
            -cf - \
            ./menu | gzip -n > menu.tar.gz

        rm -rf ./menu
    fi
)

cp -a "$INSTALL_DIR/ventoy" "$VENTOY_WORK_DIR/root/"
cp -a "$INSTALL_DIR/EFI" "$VENTOY_WORK_DIR/root/"

if [[ -f "$INSTALL_DIR/tool/ENROLL_THIS_KEY_IN_MOKMANAGER.cer" ]]; then
    cp -a \
        "$INSTALL_DIR/tool/ENROLL_THIS_KEY_IN_MOKMANAGER.cer" \
        "$VENTOY_WORK_DIR/root/"
fi

# F-Droid icin non-Secure-Boot prebuilt bloblar dahil edilmez.
rm -rf "$VENTOY_WORK_DIR/root/ventoy/7z"
rm -rf "$VENTOY_WORK_DIR/root/ventoy/imdisk"
rm -f "$VENTOY_WORK_DIR/root/ventoy/memdisk"

# GRUB i386 disk image dosyalarini dahil etme.
find "$VENTOY_WORK_DIR/root/grub/i386-pc" \
    -name '*.img' \
    -delete 2>/dev/null || true

find "$VENTOY_WORK_DIR/root" \
    -exec touch -h -d "@$SOURCE_DATE_EPOCH" '{}' +

mkdir -p "$(dirname "$VENTOY_IMAGE")"

rm -f "$VENTOY_IMAGE"

dd \
    if=/dev/zero \
    of="$VENTOY_IMAGE" \
    bs=1M \
    count=32 \
    status=none

mkfs.vfat \
    --invariant \
    -F 16 \
    -n VTOYEFI \
    -s 1 \
    -i 56544f59 \
    "$VENTOY_IMAGE" >/dev/null

FAT_BUILD_TIME="$(
    date -u \
        -d "@$SOURCE_DATE_EPOCH" \
        '+%Y-%m-%d %H:%M:%S'
)"

copy_tree() {
    local src="$1"
    local dst="$2"

    faketime -f "$FAT_BUILD_TIME" \
        mmd -i "$VENTOY_IMAGE" "$dst" 2>/dev/null || true

    find "$src" \
        -mindepth 1 \
        -maxdepth 1 |
        sort |
        while read -r child; do

            local base
            base="$(basename "$child")"

            if [[ -d "$child" ]]; then
                copy_tree "$child" "$dst/$base"
            else
                mcopy \
                    -m \
                    -i "$VENTOY_IMAGE" \
                    "$child" \
                    "$dst/$base"
            fi
        done
}

copy_tree "$VENTOY_WORK_DIR/root" ::

(
    cd "$(dirname "$VENTOY_IMAGE")"
    sha256sum "$(basename "$VENTOY_IMAGE")"
)

echo "[OK] Ventoy disk image yeniden olusturuldu."
echo

echo "[5/14] Native kodlar 4 ABI icin derleniyor..."

for file in libgadget.c libscsi.c libtftp.c libexfat.c libfunctionfs.c; do
    [[ -f "jni/$file" ]] || {
        echo "[ERROR] jni/$file bulunamadi."
        exit 1
    }
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
    "$CLANG" \
        --target="$target" \
        -shared \
        -fPIC \
        -O2 \
        -Wall \
        -Wextra \
        jni/libgadget.c \
        -o "lib/$abi/libgadget.so"

    echo "[$abi 2/5] libscsi.so"
    "$CLANG" \
        --target="$target" \
        -shared \
        -fPIC \
        -O2 \
        -Wall \
        -Wextra \
        jni/libscsi.c \
        -o "lib/$abi/libscsi.so"

    echo "[$abi 3/5] libtftp.so"
    "$CLANG" \
        --target="$target" \
        -shared \
        -fPIC \
        -O2 \
        -Wall \
        -Wextra \
        jni/libtftp.c \
        -llog \
        -o "lib/$abi/libtftp.so"

    echo "[$abi 4/5] libexfat.so"
    "$CLANG" \
        --target="$target" \
        -shared \
        -fPIC \
        -O2 \
        -Wall \
        -Wextra \
        jni/libexfat.c \
        -llog \
        -o "lib/$abi/libexfat.so"

    echo "[$abi 5/5] libfunctionfs.so"
    "$CLANG" \
        --target="$target" \
        -shared \
        -fPIC \
        -O2 \
        -Wall \
        -Wextra \
        jni/libfunctionfs.c \
        -pthread \
        -llog \
        -o "lib/$abi/libfunctionfs.so"
done

echo
echo "[OK] Native kutuphaneler derlendi."
echo

echo "[6/14] dnsmasq 4 ABI icin derleniyor..."

DNSMASQ_SRC="src/native/dnsmasq/src"
DNSMASQ_ASSETS="src/main/assets"

if [[ -f "$DNSMASQ_SRC/dnsmasq.c" ]]; then

    mapfile -d '' DNSMASQ_SOURCES < <(
        find "$DNSMASQ_SRC" \
            -maxdepth 1 \
            -type f \
            -name '*.c' \
            -print0 |
        sort -z
    )

    [[ ${#DNSMASQ_SOURCES[@]} -gt 0 ]] || {
        echo "[ERROR] dnsmasq C kaynaklari bulunamadi."
        exit 1
    }

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

        [[ -f "$output" ]] || {
            echo "[ERROR] dnsmasq $abi cikti dosyasi olusmadi."
            exit 1
        }

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

echo "[7/14] Resources derleniyor..."

"$AAPT2" compile \
    --dir res \
    -o compiled_res.zip

echo "[OK] Resources compile edildi."
echo

echo "[8/14] Resources ve assets link ediliyor..."

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

echo "[9/14] Java kaynaklari derleniyor..."

: > sources.txt

while IFS= read -r -d '' file; do
    printf '"%s"\n' "$file" >> sources.txt
done < <(
    find src gen \
        -type f \
        -name '*.java' \
        -print0
)

javac \
    --release 8 \
    -encoding UTF-8 \
    -d obj \
    -cp "$PLATFORM" \
    @sources.txt

echo "[OK] Java derlendi."
echo

echo "[10/14] Class dosyalari JAR yapiliyor..."

jar cf classes-input.jar -C obj .

echo "[OK] classes-input.jar hazir."
echo

echo "[11/14] R8 shrink + optimize + obfuscate..."

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

[[ -f r8-out/classes.dex ]] || {
    echo "[ERROR] classes.dex olusmadi."
    exit 1
}

echo "[OK] R8 tamamlandi."
echo

echo "[12/14] DEX ve native kutuphaneler APK'ya ekleniyor..."

cp r8-out/classes.dex classes.dex

jar uf app-unaligned.apk \
    classes.dex \
    lib

rm -f classes.dex classes-input.jar
rm -rf r8-out

echo "[OK] DEX ve native kutuphaneler eklendi."
echo

echo "[13/14] APK icerigi kontrol ediliyor..."

"$AAPT" list app-unaligned.apk |
    grep -Fxq "classes.dex" || {
        echo "[ERROR] classes.dex APK icinde yok."
        exit 1
    }

for abi in arm64-v8a armeabi-v7a x86 x86_64; do

    for libname in \
        libgadget.so \
        libscsi.so \
        libtftp.so \
        libexfat.so \
        libfunctionfs.so
    do
        "$AAPT" list app-unaligned.apk |
            grep -Fxq "lib/$abi/$libname" || {
                echo "[ERROR] lib/$abi/$libname APK icinde yok."
                exit 1
            }
    done
done

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    "$AAPT" list app-unaligned.apk |
        grep -Fxq "assets/dnsmasq-$abi" || {
            echo "[ERROR] assets/dnsmasq-$abi APK icinde yok."
            exit 1
        }
done

"$AAPT" list app-unaligned.apk |
    grep -Fxq "assets/ventoy.disk.img" || {
        echo "[ERROR] assets/ventoy.disk.img APK icinde yok."
        exit 1
    }

if "$AAPT" list app-unaligned.apk | grep -Fx "assets/ventoy.disk.img.sha256" >/dev/null; then
    echo "[ERROR] assets/ventoy.disk.img.sha256 APK icinde olmamali."
    exit 1
fi

echo "[OK] APK icerigi dogru."
echo

echo "[14/14] APK align ediliyor ve dogrulaniyor..."

"$ZIPALIGN" \
    -f \
    -p 4 \
    app-unaligned.apk \
    app-release-unsigned.apk

"$ZIPALIGN" \
    -c \
    -p 4 \
    app-release-unsigned.apk

echo
echo "=========================================="
echo "      F-DROID BUILD BASARILI"
echo "=========================================="
echo
echo "APK: $(pwd)/app-release-unsigned.apk"
echo "APK boyutu: $(stat -c%s app-release-unsigned.apk) bytes"
echo "Ventoy image: $(pwd)/$VENTOY_IMAGE"
echo
