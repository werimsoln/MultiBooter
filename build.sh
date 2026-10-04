#!/usr/bin/env bash
set -euo pipefail

# Always build relative to the project root, not the caller's current directory.
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

# Pinned integrity data for assets that must remain byte-for-byte stable.
# These values are also documented in ASSET_PROVENANCE.md.
VENTOY_DISK_IMG_SHA256="871f313d60d865a8ee307bc97c961e6cb619143288b4faf811efe9844ca1a003"
BOOT_IMG_SHA256="f37cbea83596aef9812f4d984d344b5103913505dfee40dc0025742ea54a6113"
CORE_IMG_SHA256="b6581090947e7cacbd3cee23dfe2216aee9ab368c6508c2c5f3490621e969b84"
EFI_BOOTX64_SHA256="1ff3f223c2fcf5b11615d042fcb5674c4651bbbc8505b5b2987d60da0cb65d1a"
EFI_MMX64_SHA256="1a3687f923d077080fe49feb470e3932c2b1d3fd4c6439123aa0226246a24522"
EFI_FBX64_SHA256="c8fc4661f4b64b916e37e4fdd68042d3d64290a696add9199afb84c12ad896c8"
EFI_GRUBX64_REAL_SHA256="907c99a8370e953eb4ec34df2c314cf979356bfca97733ccb1139ee3f5e98cce"

VENTOY_VERSION="${VENTOY_VERSION:-1.1.17}"
# SHA-256 of the deterministic F-Droid-rebuilt Ventoy VTOYEFI image.
# A mismatch is fatal and must stop the build.
VENTOY_EXPECTED_REBUILT_SHA256="830d225ec39c06dcd57fd38f38ae784a1123588b2f27cde9e9a49e86d6fc2113"

echo "=========================================="
echo "      MultiBooter F-Droid BUILD"
echo "=========================================="
echo

echo "[INFO] Java:"
java -version

echo "[INFO] Javac:"
javac -version

echo "[INFO] Available Java versions:"
update-alternatives --list java 2>/dev/null || true

ANDROID_API="${ANDROID_API:-36}"
NDK_VERSION="${NDK_VERSION:-30.0.16248370}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}"

PLATFORM="$ANDROID_HOME/platforms/android-$ANDROID_API/android.jar"
BUILD_TOOLS="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$NDK_BIN/clang"

AAPT2="$BUILD_TOOLS/aapt2"
AAPT="$BUILD_TOOLS/aapt"
ZIPALIGN="$BUILD_TOOLS/zipalign"

# Optional explicit D8 JAR path.
D8_JAR="${D8_JAR:-}"

VENTOY_SRC="${VENTOY_SRC:-}"
VENTOY_IMAGE="src/main/assets/ventoy.disk.img"
VENTOY_WORK_DIR="${VENTOY_WORK_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/multibooter-ventoy.XXXXXX")}"

SOURCE_DATE_EPOCH="${VENTOY_IMAGE_EPOCH:-1735689600}"

cleanup() {
    rm -rf "$VENTOY_WORK_DIR"
}

trap cleanup EXIT
trap 'cleanup; exit 129' HUP
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

fail() {
    echo "[ERROR] $*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fail "Gerekli arac bulunamadi: $1"
}

verify_sha256() {
    local file="$1"
    local expected="$2"
    local label="$3"
    local actual

    [[ -f "$file" ]] || fail "$label bulunamadi: $file"

    actual="$(sha256sum "$file" | awk '{print $1}')"

    if [[ "$actual" != "$expected" ]]; then
        echo "[ERROR] $label SHA-256 uyusmuyor." >&2
        echo "[ERROR] Beklenen: $expected" >&2
        echo "[ERROR] Gercek:    $actual" >&2
        exit 1
    fi

    echo "[OK] $label SHA-256 dogrulandi: $actual"
}

validate_sha256_value() {
    local value="$1"
    local label="$2"

    [[ "$value" =~ ^[0-9a-fA-F]{64}$ ]] ||
        fail "$label gecersiz. 64 karakterlik SHA-256 bekleniyor."
}

echo "[0/15] Ortam kontrol ediliyor..."

[[ "$(uname -s)" == "Linux" ]] ||
    fail "Bu build scripti Linux icin tasarlanmistir."

[[ "$(uname -m)" == "x86_64" ]] ||
    fail "Bu build scripti linux-x86_64 Android/NDK arac zincirini kullaniyor; x86_64 host gerekli."

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

require_cmd javac
require_cmd jar
require_cmd java
require_cmd stat
require_cmd sha256sum

# D8 discovery:
# 1) Explicit D8_JAR, when supplied by the caller/CI.
# 2) Build Tools bundled D8 (lib/d8.jar).
# 3) Build Tools' d8 executable wrapper.
D8_BIN=""

if [[ -n "$D8_JAR" ]]; then
    [[ -f "$D8_JAR" ]] ||
        fail "D8_JAR olarak verilen dosya bulunamadi: $D8_JAR"
elif [[ -f "$BUILD_TOOLS/lib/d8.jar" ]]; then
    D8_JAR="$BUILD_TOOLS/lib/d8.jar"
elif [[ -x "$BUILD_TOOLS/d8" ]]; then
    D8_BIN="$BUILD_TOOLS/d8"
else
    echo "[ERROR] D8 bulunamadi."
    echo "[ERROR] Aranan konumlar:"
    echo "        $BUILD_TOOLS/lib/d8.jar"
    echo "        $BUILD_TOOLS/d8"
    exit 1
fi

echo "[OK] Android build ortami hazir."
echo "[OK] Project root: $PROJECT_ROOT"
echo "[OK] Android API: $ANDROID_API"
echo "[OK] Build Tools: $BUILD_TOOLS_VERSION"
echo "[OK] NDK: $NDK_VERSION"

if [[ -n "$D8_BIN" ]]; then
    echo "[OK] D8: $D8_BIN"
else
    echo "[OK] D8 JAR: $D8_JAR"
fi

echo

echo "[1/15] Eski build temizleniyor..."

rm -rf gen obj dex-out lib
rm -f compiled_res.zip sources.txt classes.dex
rm -f app-unaligned.apk app-aligned.apk app-release.apk app-release-unsigned.apk

mkdir -p gen obj dex-out
mkdir -p lib/arm64-v8a lib/armeabi-v7a lib/x86 lib/x86_64
mkdir -p src/main/assets

rm -f src/main/assets/ffs_gadget
rm -f src/main/assets/dnsmasq
rm -f src/main/assets/ventoy.disk.img.sha256

echo "[OK] Temizlik tamam."
echo

echo "[2/15] Ventoy kaynak kontrol ediliyor..."

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

# These signed EFI binaries are deliberately pinned.
# F-Droid metadata may remove and restore them from the checked-in reference image;
# the build must reject any unexpected replacement before packaging them into the rebuilt image.
verify_sha256 \
    "$VENTOY_SRC/INSTALL/EFI/BOOT/BOOTX64.EFI" \
    "$EFI_BOOTX64_SHA256" \
    "Ventoy BOOTX64.EFI"

verify_sha256 \
    "$VENTOY_SRC/INSTALL/EFI/BOOT/mmx64.efi" \
    "$EFI_MMX64_SHA256" \
    "Ventoy mmx64.efi"

verify_sha256 \
    "$VENTOY_SRC/INSTALL/EFI/BOOT/fbx64.efi" \
    "$EFI_FBX64_SHA256" \
    "Ventoy fbx64.efi"

verify_sha256 \
    "$VENTOY_SRC/INSTALL/EFI/BOOT/grubx64_real.efi" \
    "$EFI_GRUBX64_REAL_SHA256" \
    "Ventoy grubx64_real.efi"

echo "[OK] Ventoy kaynaklari bulundu ve kritik Secure Boot varliklari dogrulandi: $VENTOY_SRC"
echo

echo "[3/15] Ventoy disk image icin gerekli araclar kontrol ediliyor..."

for tool in awk date dd faketime find grep mkfs.vfat mcopy mmd gzip sha256sum sort stat tar touch; do
    require_cmd "$tool"
done

echo "[OK] Ventoy image araclari hazir."
echo

echo "[4/15] Ventoy disk image yeniden olusturuluyor..."

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

verify_sha256 \
    "$VENTOY_IMAGE" \
    "$VENTOY_DISK_IMG_SHA256" \
    "upstream ventoy.disk.img"

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

[[ -f "$VENTOY_IMAGE" ]] ||
    fail "Ventoy disk image olusturulamadi: $VENTOY_IMAGE"

VENTOY_IMAGE_SIZE="$(stat -c%s "$VENTOY_IMAGE")"

[[ "$VENTOY_IMAGE_SIZE" -eq 33554432 ]] ||
    fail "Rebuilt Ventoy VTOYEFI image boyutu 33554432 byte olmali; gercek: $VENTOY_IMAGE_SIZE"

VENTOY_REBUILT_SHA256="$(
    sha256sum "$VENTOY_IMAGE" |
    awk '{print $1}'
)"

echo "[INFO] Rebuilt Ventoy VTOYEFI SHA-256: $VENTOY_REBUILT_SHA256"

if [[ -n "$VENTOY_EXPECTED_REBUILT_SHA256" ]]; then

    if [[ "$VENTOY_REBUILT_SHA256" != "$VENTOY_EXPECTED_REBUILT_SHA256" ]]; then
        echo "[ERROR] Rebuilt Ventoy VTOYEFI SHA-256 beklenen degerle eslesmiyor." >&2
        echo "[ERROR] Beklenen: $VENTOY_EXPECTED_REBUILT_SHA256" >&2
        echo "[ERROR] Gercek:    $VENTOY_REBUILT_SHA256" >&2
        exit 1
    fi

    echo "[OK] Rebuilt Ventoy VTOYEFI SHA-256 beklenen degerle dogrulandi."

else

    echo "[WARN] VENTOY_EXPECTED_REBUILT_SHA256 ayarlanmadi; rebuilt image hash karsilastirmasi zorunlu degil."

fi

echo "[OK] Ventoy disk image yeniden olusturuldu."
echo

echo "[5/15] Paketlenecek Ventoy assetleri dogrulaniyor..."

verify_sha256 \
    "src/main/assets/boot.img" \
    "$BOOT_IMG_SHA256" \
    "boot.img"

verify_sha256 \
    "src/main/assets/core.img" \
    "$CORE_IMG_SHA256" \
    "core.img"

[[ "$(stat -c%s src/main/assets/boot.img)" -eq 512 ]] ||
    fail "boot.img boyutu 512 byte olmali."

[[ "$(stat -c%s src/main/assets/core.img)" -eq 1048064 ]] ||
    fail "core.img boyutu 1048064 byte olmali."

[[ "$(stat -c%s "$VENTOY_IMAGE")" -eq 33554432 ]] ||
    fail "ventoy.disk.img boyutu 33554432 byte olmali."

echo "[OK] Ventoy boot/core/VTOYEFI assetleri dogrulandi."
echo

echo "[6/15] Native kodlar 4 ABI icin derleniyor..."

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

echo "[7/15] dnsmasq 4 ABI icin derleniyor..."

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

echo "[8/15] Resources derleniyor..."

"$AAPT2" compile \
    --dir res \
    -o compiled_res.zip

echo "[OK] Resources compile edildi."
echo

echo "[9/15] Resources ve assets link ediliyor..."

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

echo "[10/15] Java kaynaklari derleniyor..."

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
    -g:lines,source \
    -encoding UTF-8 \
    -d obj \
    -cp "$PLATFORM" \
    @sources.txt

echo "[OK] Java derlendi."
echo

echo "[11/15] D8 ile DEX uretiliyor..."
echo

rm -rf dex-out
mkdir -p dex-out

rm -f classes-input.jar

jar cf classes-input.jar -C obj .

if [[ -n "$D8_BIN" ]]; then

    "$D8_BIN" \
        --release \
        --min-api 26 \
        --lib "$PLATFORM" \
        --output dex-out \
        classes-input.jar

else

    java \
        -cp "$D8_JAR" \
        com.android.tools.r8.D8 \
        --release \
        --min-api 26 \
        --lib "$PLATFORM" \
        --output dex-out \
        classes-input.jar

fi

echo "[12/15] DEX ve native kutuphaneler APK'ya ekleniyor..."

cp dex-out/classes.dex classes.dex

jar uf app-unaligned.apk \
    classes.dex \
    lib

rm -f classes.dex
rm -rf dex-out

echo "[OK] DEX ve native kutuphaneler eklendi."
echo

echo "[13/15] APK icerigi kontrol ediliyor..."

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

if "$AAPT" list app-unaligned.apk |
    grep -Fx "assets/ventoy.disk.img.sha256" >/dev/null
then
    echo "[ERROR] assets/ventoy.disk.img.sha256 APK icinde olmamali."
    exit 1
fi

echo "[OK] APK icerigi dogru."
echo

echo "[14/15] APK align ediliyor ve dogrulaniyor..."

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
echo "APK: $(pwd)/app-release-unsigned.apk"
echo "APK boyutu: $(stat -c%s app-release-unsigned.apk) bytes"
echo "APK SHA-256: $(sha256sum app-release-unsigned.apk | awk '{print $1}')"
echo "Ventoy image: $PROJECT_ROOT/$VENTOY_IMAGE"
echo "Ventoy rebuilt image SHA-256: $VENTOY_REBUILT_SHA256"
echo "Ventoy source: $VENTOY_SRC"
echo "SOURCE_DATE_EPOCH: $SOURCE_DATE_EPOCH"
echo
