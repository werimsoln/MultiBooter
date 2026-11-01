# Build and provenance details

This file holds the detailed build, provenance and runtime notes that used to
live in the main README. See [README.md](../README.md) for the short overview.

---

## Requirements

### Android device

- Android 8.0 or newer
- USB Host / USB OTG support for USB-writing features
- A compatible USB mass-storage device
- Root access for USB Gadget, TFTP and FunctionFS modes
- Compatible Linux ConfigFS / FunctionFS support where required

### Build environment

The current `build.sh` script is a Linux/bash build intended for an F-Droid
style clean build environment.

Required components include:

- JDK providing `javac`, `java` and `jar`
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Android NDK 30.0.16248370
- D8
- `awk`
- `date`
- `dd`
- `faketime`
- `find`
- `git`
- `grep`
- `gzip`
- `mkfs.vfat`
- `mcopy`
- `mmd`
- `sha256sum`
- `sort`
- `stat`
- `tar`
- `touch`
- `unzip`

The Android SDK and NDK are expected below `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`).

The build script does not use Gradle or AndroidX.

---

## Building

MultiBooter uses a lightweight Android build pipeline without Gradle.

The current build sequence is:

```text
Environment checks
        |
        v
Clean previous build artifacts
        |
        v
Validate pinned Ventoy source and Secure Boot assets
        |
        v
Rebuild deterministic Ventoy VTOYEFI image
        |
        v
Verify Ventoy boot/core assets
        |
        v
Build native libraries for 4 ABIs
        |
        v
Build dnsmasq for 4 ABIs
        |
        v
Compile Android resources
        |
        v
Link Android resources and assets
        |
        v
Compile Java
        |
        v
D8 -> classes.dex
        |
        v
Insert DEX + native libraries
        |
        v
Validate APK contents and packaged Ventoy image
        |
        v
zipalign + alignment verification
        |
        v
app-release-unsigned.apk
```

### Basic build

From the repository root:

```bash
chmod +x build.sh
./build.sh
```

The build fails when a required SDK, NDK, D8, Ventoy source or host tool is
missing, or when a pinned asset fails its integrity check.

### Android SDK setup

The script resolves the SDK from:

```text
ANDROID_HOME
```

or, when that is not set:

```text
ANDROID_SDK_ROOT
```

or the default:

```text
$HOME/Android/Sdk
```

It then expects:

```text
platforms/android-36/android.jar
build-tools/36.0.0/
ndk/30.0.16248370/
```

For example:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
```

---

## Ventoy source requirement

Ventoy is a source/build-time dependency for the F-Droid/Linux build.

The build script requires:

```text
VENTOY_SRC
```

to point to a pinned Ventoy source or release tree containing:

```text
VENTOY_SRC/
└── INSTALL/
    ├── EFI/
    ├── grub/
    └── ventoy/
```

`INSTALL/grub/grub.cfg` must also be present.

The build is intended to verify that the supplied Ventoy tree corresponds to
the pinned Ventoy release commit before using it.

The build does not silently fall back to a downloaded Ventoy binary.

Example:

```bash
export VENTOY_SRC="/path/to/ventoy-1.1.17"
./build.sh
```

The F-Droid metadata is intended to provide the pinned Ventoy source dependency
rather than downloading executable Ventoy components during application
runtime.

---

## Ventoy asset and provenance model

MultiBooter distinguishes between the original upstream Ventoy assets and the
image reconstructed by the F-Droid build.

The repository records the provenance of these assets in:

```text
ASSET_PROVENANCE.md
```

The current upstream baseline is Ventoy:

```text
Version: 1.1.17
Tag: v1.1.17
```

The build also pins the exact Ventoy source commit associated with this
release. The commit is verified during the build rather than relying only on
the version string.

The original upstream VTOYEFI image is retained as the reference asset in:

```text
src/main/assets/ventoy.disk.img
```

Before reconstruction, the checked-in image is verified against its documented
upstream SHA-256 value.

The build then reconstructs a 32 MiB FAT16 VTOYEFI image from the pinned
Ventoy source tree.

This means:

```text
official Ventoy release
        |
        +----> pinned version / commit
        |
        +----> original reference image
        |
        +----> Secure Boot EFI files
        |
        v
pinned Ventoy source tree
        |
        +----> excluded prebuilt payloads removed
        |
        v
deterministic VTOYEFI rebuild
        |
        v
verified rebuilt VTOYEFI image
        |
        v
APK packaged asset
```

The original upstream image hash and the rebuilt MultiBooter/F-Droid image hash
are different provenance values and must not be treated as the same artifact.

---

## Secure Boot binaries

The Secure Boot chain contains firmware-trusted EFI binaries.

These binaries are therefore treated as release-critical binary assets and
must not be casually rebuilt or replaced.

The provenance documentation records their expected SHA-256 values and their
upstream origin.

The current build verifies the expected SHA-256 values of the required
Secure Boot EFI files before they are used in the reconstructed image.

The relevant files are:

```text
INSTALL/EFI/BOOT/BOOTX64.EFI
INSTALL/EFI/BOOT/mmx64.efi
INSTALL/EFI/BOOT/fbx64.efi
INSTALL/EFI/BOOT/grubx64_real.efi
```

The MOK enrollment certificate used by the Ventoy image is also covered by the
project's documented provenance data.

---

## Excluded Ventoy prebuilt payloads

The F-Droid-oriented Ventoy image rebuild removes:

```text
INSTALL/ventoy/7z
INSTALL/ventoy/imdisk
INSTALL/ventoy/memdisk
```

The build also removes GRUB i386 disk-image files under:

```text
INSTALL/grub/i386-pc/
```

These exclusions are part of the current packaging model.

---

## Deterministic Ventoy image generation

The Ventoy VTOYEFI image is regenerated during the build instead of relying
only on the checked-in binary image.

The reproducible build uses:

```text
SOURCE_DATE_EPOCH=1735689600
TZ=UTC
LC_ALL=C
```

The image is created as:

```text
Size:       32 MiB
Filesystem: FAT16
Label:      VTOYEFI
FAT ID:     56544f59
```

The build normalizes relevant timestamps, sorts copied files and uses
deterministic `tar`, `gzip`, `faketime` and FAT-image operations.

The generated image is written to:

```text
src/main/assets/ventoy.disk.img
```

during the build.

The resulting SHA-256 is compared against the pinned expected rebuilt-image
hash. A mismatch is fatal and stops the build.

The APK-packaged copy of the image is also verified against the same rebuilt
image hash after APK assembly.

The original upstream image hash is used only to verify the reference input
before it is replaced by the deterministic reconstruction.

---

## Native components

MultiBooter contains native C components for:

- USB Gadget functionality
- USB Mass Storage / SCSI handling
- exFAT formatting
- TFTP functionality
- FunctionFS support

The main native libraries are:

```text
libgadget.so
libscsi.so
libtftp.so
libexfat.so
libfunctionfs.so
```

Each library is built for:

```text
arm64-v8a
armeabi-v7a
x86
x86_64
```

The build also produces a dnsmasq executable for all four ABIs when the bundled
dnsmasq source tree is present.

The generated dnsmasq assets are:

```text
assets/dnsmasq-arm64-v8a
assets/dnsmasq-armeabi-v7a
assets/dnsmasq-x86
assets/dnsmasq-x86_64
```

---

## Java and DEX generation

The Java sources are compiled with the JDK using the Android platform
`android.jar` as the compile-time API.

The current build does not use R8 for Java shrinking, optimization or
obfuscation.

Java bytecode is packaged into a temporary JAR and converted to DEX with
**D8**.

The build supports:

```text
$BUILD_TOOLS/lib/d8.jar
```

or:

```text
$BUILD_TOOLS/d8
```

An explicit D8 JAR can also be supplied through:

```text
D8_JAR
```

The DEX build uses:

```text
--min-api 26
```

and the Android platform:

```text
platforms/android-36/android.jar
```

The resulting:

```text
classes.dex
```

is inserted into the APK together with the native libraries.

No `proguard-rules.pro` file is required by the current build pipeline.

---

## APK assembly

The build intentionally uses the Android SDK build tools directly.

Resources are compiled with:

```text
aapt2 compile
```

and linked with:

```text
aapt2 link
```

The Android assets directory is:

```text
src/main/assets/
```

Java bytecode is converted to DEX by D8.

The final DEX and native libraries are inserted into the generated APK and the
APK is then checked for the required entries.

The APK is finally processed with `zipalign`.

---

## APK integrity checks

The build verifies that the APK contains:

```text
classes.dex
```

all required native libraries for all four ABIs, all four dnsmasq assets, and:

```text
assets/ventoy.disk.img
```

The build also checks that the temporary:

```text
assets/ventoy.disk.img.sha256
```

file is not accidentally packaged into the APK.

The packaged `assets/ventoy.disk.img` is extracted and its SHA-256 is checked
against the deterministic rebuilt-image hash.

The final APK is processed with:

```text
zipalign
```

and the alignment is verified before the build is reported as successful.

---

## Build output

A successful build produces:

```text
app-release-unsigned.apk
```

The script prints:

- the final APK size;
- the final APK SHA-256;
- the rebuilt Ventoy VTOYEFI SHA-256;
- the pinned Ventoy source information;
- the deterministic image timestamp.

The final APK SHA-256 is a build result and should not be confused with the
pinned provenance hashes of the Ventoy assets.

---

## Build environment variables

The following variables are used by the current build:

| Variable | Default | Purpose |
| --- | --- | --- |
| `ANDROID_API` | `36` | Android API level |
| `NDK_VERSION` | `30.0.16248370` | Android NDK version |
| `BUILD_TOOLS_VERSION` | `36.0.0` | Android Build Tools version |
| `ANDROID_HOME` | `$HOME/Android/Sdk` fallback | Android SDK root |
| `ANDROID_SDK_ROOT` | unset | Alternative Android SDK root |
| `D8_JAR` | unset | Optional explicit D8 JAR path |
| `VENTOY_SRC` | unset | Pinned Ventoy source/release tree |
| `VENTOY_WORK_DIR` | temporary directory | Ventoy image work directory |

The deterministic Ventoy image timestamp is intentionally fixed by the build
for provenance purposes and is not treated as a release-build override.

`VENTOY_SRC` is mandatory.

---

## F-Droid build and provenance

MultiBooter is designed to be compatible with source-based F-Droid builds.

The F-Droid build model uses a pinned Ventoy source dependency and runs the
project build script in a clean Linux environment.

The F-Droid metadata records the exact MultiBooter source revision to build
and the pinned Ventoy source dependency.

The build verifies the Ventoy source revision and critical binary assets before
constructing the application.

The repository's:

```text
ASSET_PROVENANCE.md
```

documents the upstream versions, commits and hashes used by the build.

The application itself is designed not to download additional executable
Ventoy components after installation.

---

## Runtime network behavior

The application does not require downloading Ventoy executable components at
runtime.

Ventoy boot assets required by the installer are supplied as application
assets and/or generated during the build from the pinned Ventoy source.

The application declares no `INTERNET` permission and contains no network
client. The TFTP/PXE feature starts a bundled `dnsmasq` as root through `su`;
that process is not controlled by the Android app permission model and does
not need the app to hold `INTERNET`.

---

## Build safety

MultiBooter performs destructive low-level operations on USB media.

Installing Ventoy or writing an ISO can:

- overwrite partition tables;
- destroy existing partitions;
- destroy files stored on the selected USB device;
- make previously stored data inaccessible.

Always verify the selected USB device before starting a destructive operation.

Back up important data first.

Do not assume that the device name, capacity or USB port identifies the
correct physical storage device.

The Ventoy installer is intended for USB mass-storage devices and currently
implements the MBR-style Ventoy layout used by the application.

---

## USB access model

For rootless USB-writing features, MultiBooter does not require direct access
to:

```text
/dev/sdX
```

Instead, the application uses:

```text
Android UsbManager
        |
        v
USB device connection
        |
        v
USB Mass Storage interface
        |
        v
Bulk-Only Transport
        |
        v
SCSI commands
```

This keeps raw-media access inside the Android USB Host model rather than
depending on direct Linux block-device access.

Root-dependent USB Gadget and FunctionFS functionality follows a different
path through Linux ConfigFS / FunctionFS.

---

## Storage formats and boot media

The project contains support for:

- Ventoy-style USB installation
- raw ISO writing
- exFAT formatting
- virtual USB Mass Storage
- virtual CD-ROM style FunctionFS workflows
- TFTP/network-boot file serving

Actual hardware behavior depends on the Android device, USB controller,
storage device and Linux kernel.

---

## Project structure

The repository is organized approximately as follows:

```text
MultiBooter/
├── jni/                       Native C sources used by the Android build
├── res/                       Android resources and layouts
├── src/
│   ├── main/
│   │   └── assets/            Bundled runtime assets
│   └── com/
│       └── werismoln/
│           └── multibooter/   Java application sources
├── src/native/
│   └── dnsmasq/               Bundled dnsmasq source
├── fastlane/                  Store/F-Droid presentation metadata
├── metadata/                  F-Droid metadata
├── ASSET_PROVENANCE.md        Binary/boot asset provenance
├── AndroidManifest.xml
├── build.sh                   Linux/F-Droid build script
└── LICENSE
```

---
