# MultiBooter

MultiBooter is a free and open-source Android application for creating bootable
USB media and providing low-level boot and USB workflows directly from an
Android device.

The project is intentionally built without Gradle and without AndroidX. It
uses the Android framework, Java, native C code and the Android NDK.

MultiBooter currently combines several independent low-level workflows in one
application:

- Ventoy USB installation
- Direct ISO/CD-ROM image writing
- USB Gadget Mass Storage through Linux ConfigFS
- TFTP boot
- FunctionFS-based USB Mass Storage / virtual CD-ROM

The application is designed for users who need direct control over boot media,
USB mass-storage devices and Android/Linux USB functionality.

---

## Features

### Ventoy USB installation

MultiBooter can prepare a USB mass-storage device with a Ventoy MBR layout
directly from Android.

The installer:

- obtains the USB capacity through USB Mass Storage / SCSI;
- creates the Ventoy-style MBR partition layout;
- writes the Ventoy boot code;
- formats the data partition as exFAT;
- writes the Ventoy BIOS boot core;
- writes the VTOYEFI partition image;
- synchronizes the USB device cache before reporting completion.

The Ventoy installer operates through the Android USB Host API and USB
Mass Storage Bulk-Only Transport. It does not require root access for the USB
device-writing operation.

### Direct ISO writer

MultiBooter can write bootable ISO and CD-ROM images directly to USB
mass-storage devices.

The USB writer uses the Android USB Host API and SCSI/Bulk-Only Transport
instead of requiring direct access to Linux block-device paths such as
`/dev/sdX`.

### USB Gadget Mass Storage

On compatible rooted Android devices, MultiBooter can expose an ISO or disk
image as a USB Mass Storage device using Linux ConfigFS USB Gadget support.

This mode depends on the kernel configuration of the Android device.

### TFTP boot

MultiBooter includes a TFTP-based network-boot workflow.

This mode requires root access because the application uses native networking
and Android/Linux interfaces that are not available to ordinary applications
in all environments.

### FunctionFS boot

MultiBooter includes a FunctionFS-based userspace USB Mass Storage /
virtual CD-ROM backend.

This mode requires root access and a kernel with compatible FunctionFS and
USB Gadget support.

---

## Root requirements

Not every MultiBooter feature requires root access.

| Feature | Root required |
| --- | --- |
| Ventoy USB installation | No |
| Direct ISO writer | No |
| USB Gadget Mass Storage | Yes |
| TFTP boot | Yes |
| FunctionFS boot | Yes |

Rootless USB-writing features use the Android USB Host API and USB Mass
Storage Bulk-Only Transport.

Root-dependent features require compatible Linux kernel functionality.
Availability therefore varies between Android devices, kernels, vendors and
ROMs.

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
- Android SDK Platform 34
- Android Build Tools 34.0.0
- Android NDK 30.0.16248370
- R8
- `awk`
- `date`
- `dd`
- `faketime`
- `find`
- `grep`
- `gzip`
- `mkfs.vfat`
- `mcopy`
- `mmd`
- `sha256sum`
- `sort`
- `tar`
- `touch`

The Android SDK and NDK are expected below `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`).

The build script does not use Gradle.

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
Validate Ventoy source
        |
        v
Rebuild deterministic Ventoy VTOYEFI image
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
Link Android assets/resources
        |
        v
Compile Java
        |
        v
R8 shrink / optimize / obfuscate
        |
        v
Insert DEX + native libraries
        |
        v
Validate APK contents
        |
        v
zipalign
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

The build fails immediately when a required SDK, NDK, R8, Ventoy source or
host tool is missing.

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
platforms/android-34/android.jar
build-tools/34.0.0/
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

to point to a Ventoy source or release tree containing:

```text
VENTOY_SRC/
└── INSTALL/
    ├── EFI/
    ├── grub/
    └── ventoy/
```

`INSTALL/grub/grub.cfg` must also be present.

The build does not silently fall back to a downloaded Ventoy binary.

Example:

```bash
export VENTOY_SRC="/path/to/ventoy-1.1.17"
./build.sh
```

The F-Droid metadata is intended to provide the pinned Ventoy source tree
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

The original upstream VTOYEFI image is retained as the reference asset in:

```text
src/main/assets/ventoy.disk.img
```

The build then reconstructs a 32 MiB FAT16 VTOYEFI image from the pinned
Ventoy source tree.

This means:

```text
official Ventoy release image
        |
        +----> provenance/reference
        |
        +----> Secure Boot EFI files can be extracted and verified
        |
        v
pinned Ventoy source tree
        |
        +----> excluded prebuilt payloads removed
        |
        v
deterministic VTOYEFI rebuild
```

The original upstream image hash and the rebuilt MultiBooter/F-Droid image hash
are different provenance values and must not be treated as the same artifact.

### Secure Boot binaries

The Secure Boot chain contains firmware-trusted EFI binaries.

These binaries are therefore treated as release-critical binary assets and
must not be casually rebuilt or replaced.

The provenance documentation records their expected SHA-256 values and their
upstream origin.

The current build process preserves the required Secure Boot EFI files while
excluding selected non-Secure-Boot prebuilt payloads from the F-Droid image.

### Excluded Ventoy prebuilt payloads

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

The current script uses:

```text
SOURCE_DATE_EPOCH=1735689600
TZ=UTC
```

unless `VENTOY_IMAGE_EPOCH` is explicitly supplied.

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

The build prints the resulting SHA-256 of the generated image so it can be
compared with the documented provenance for the exact build environment.

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

## R8

The Java build uses R8 for:

- shrinking;
- optimization;
- obfuscation.

The build expects:

```text
$ANDROID_HOME/r8/r8.jar
```

by default.

It can also use compatible R8/D8 tooling found under the Android Build Tools
directory when the primary R8 path is unavailable.

The R8 configuration is stored in:

```text
proguard-rules.pro
```

---

## APK assembly

The build intentionally uses the Android SDK build tools directly.

Resources are compiled with:

```text
aapt2
```

and linked together with:

```text
aapt2 link
```

The Android assets directory is:

```text
src/main/assets/
```

The resulting Java bytecode is converted to DEX by R8.

The final DEX and native libraries are inserted into the generated APK and the
APK is then checked for the required entries.

---

## Build output

A successful build produces:

```text
app-release-unsigned.apk
```

The script verifies that the APK contains:

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

The final APK is processed with:

```text
zipalign
```

and the alignment is verified before the build is reported as successful.

---

## Build environment variables

The following variables can be used to control the current build:

| Variable | Default | Purpose |
| --- | --- | --- |
| `ANDROID_API` | `34` | Android API level |
| `NDK_VERSION` | `30.0.16248370` | Android NDK version |
| `BUILD_TOOLS_VERSION` | `34.0.0` | Android Build Tools version |
| `ANDROID_HOME` | `$HOME/Android/Sdk` fallback | Android SDK root |
| `ANDROID_SDK_ROOT` | unset | Alternative Android SDK root |
| `R8_JAR` | `$ANDROID_HOME/r8/r8.jar` | R8 JAR path |
| `VENTOY_SRC` | unset | Ventoy source/release tree |
| `VENTOY_WORK_DIR` | temporary directory | Ventoy image work directory |
| `VENTOY_IMAGE_EPOCH` | `1735689600` | Reproducible Ventoy image timestamp |

`VENTOY_SRC` is mandatory.

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
├── proguard-rules.pro         R8 configuration
└── LICENSE
```

---

## F-Droid

MultiBooter is designed to be compatible with source-based F-Droid builds.

The F-Droid build model uses a pinned Ventoy source dependency and runs the
project build script in a clean Linux environment.

The application itself is designed not to download additional executable
Ventoy components after installation.

The repository contains:

```text
metadata/com.werismoln.multibooter.yml
```

for F-Droid packaging metadata.

The F-Droid build should be evaluated from the exact commit recorded in that
metadata file.

---

## Runtime network behavior

The application does not require downloading Ventoy executable components at
runtime.

Ventoy boot assets required by the installer are supplied as application
assets and/or generated during the build from the pinned Ventoy source.

Network functionality exists separately for the TFTP feature.

Runtime TFTP behavior therefore should not be confused with downloading
Ventoy boot components.

---

## Distribution

MultiBooter is intended for distribution through:

- F-Droid
- GitHub Releases
- compatible direct-update tools such as Obtainium

The exact contents of a release should correspond to the repository source and
its documented build process.

---

## Source code and issue tracker

Source repository:

https://github.com/werimsoln/MultiBooter

Issue tracker:

https://github.com/werimsoln/MultiBooter/issues

F-Droid package:

https://f-droid.org/packages/com.werismoln.multibooter/

---

## Provenance and licensing

MultiBooter is licensed under:

**GNU General Public License v3.0 or later**

See:

```text
LICENSE
```

Ventoy and all third-party components bundled inside or derived from the
Ventoy source/release retain their respective upstream licenses.

MultiBooter does not claim ownership of Ventoy or its third-party components.

Detailed binary provenance is maintained separately in:

```text
ASSET_PROVENANCE.md
```

That document records upstream versions, source locations, hashes,
Secure Boot components and the distinction between official upstream images
and MultiBooter-reconstructed images.

---

## Current build policy

The repository intentionally avoids Gradle and AndroidX.

The build is based on:

```text
Android SDK
Android Build Tools
Android NDK
JDK
R8
AAPT2 / AAPT
zipalign
```

This keeps the build process close to the Android platform toolchain and avoids
introducing Gradle/AndroidX dependencies into the application.

---

## Limitations

The project performs low-level USB and boot-media operations that depend on
hardware and firmware behavior.

Expected limitations include:

- device-specific USB Host behavior;
- vendor-specific Android kernel restrictions;
- device-specific ConfigFS support;
- device-specific FunctionFS support;
- firmware-specific Secure Boot behavior;
- USB mass-storage controllers with unusual SCSI/BOT behavior;
- storage devices whose cache synchronization behavior differs from the
  standard expectations.

A feature appearing in the application does not mean that every Android
device supports the underlying kernel or USB functionality.

---

## Disclaimer

MultiBooter performs low-level operations on USB drives and boot media.

Incorrect device selection can permanently destroy data.

USB Gadget, FunctionFS, TFTP and other root-dependent operations can also change
kernel-level USB/network state on the Android device.

Use the software only when you understand the consequences of the selected
operation and always keep backups of important data.

