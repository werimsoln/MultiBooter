# MultiBooter

MultiBooter is a free and open-source Android application for creating bootable USB media and booting computers directly from an Android device.

The project is built with the Android framework, Java, native C code and the Android NDK. It does not depend on AndroidX.

## Features

MultiBooter currently provides the following boot methods:

- **Ventoy USB installation** — prepares a USB flash drive with Ventoy directly from Android.
- **Direct ISO writer** — writes bootable ISO/CD-ROM images byte-for-byte to USB mass-storage devices.
- **USB Gadget Mass Storage** — exposes an ISO or disk image from the Android device as USB Mass Storage through Linux ConfigFS.
- **TFTP boot** — provides files for network booting through a TFTP server.
- **FunctionFS boot** — implements a userspace USB Mass Storage / virtual CD-ROM backend through FunctionFS.

## Root requirements

Not every MultiBooter feature requires root access.

| Feature | Root required |
| --- | --- |
| Ventoy USB installation | No |
| Direct ISO writer | No |
| USB Gadget Mass Storage | Yes |
| TFTP boot | Yes |
| FunctionFS boot | Yes |

Rootless USB-writing features use the Android USB Host API and USB Mass Storage Bulk-Only Transport rather than direct access to `/dev/sdX`.

Root-dependent features require compatible Linux kernel functionality on the Android device. Availability can vary between manufacturers, kernels and ROMs.

## Requirements

- Android 8.0 or newer
- USB Host / USB OTG support for USB-writing features
- A compatible USB mass-storage device
- Root access for USB Gadget, TFTP and FunctionFS modes
- Kernel ConfigFS / FunctionFS support where required

## Important warning

MultiBooter performs low-level operations on USB drives and boot media.

**Installing Ventoy or writing an ISO can overwrite partition tables and destroy all existing data on the selected USB device.**

Always verify the selected USB device and keep backups of important data before starting a destructive operation.

## Building

MultiBooter uses a lightweight build process without Gradle.

### Linux

Required components include:

- JDK with `javac`, `java` and `jar`
- Android SDK Platform 34
- Android Build Tools 34.0.0
- Android NDK 26.1.10909125
- R8

Build with:

```bash
chmod +x build.sh
./build.sh
```

The F-Droid/Linux build script produces:

```text
app-release-unsigned.apk
```

## Native components

MultiBooter contains native components written in C for:

- USB Gadget support
- USB Mass Storage / SCSI operations
- exFAT formatting
- TFTP support
- FunctionFS support

Native libraries are built for:

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

## Project structure

```text
MultiBooter/
├── jni/                  Native C sources
├── res/                  Android resources
├── src/                  Java and native source tree
├── fastlane/             Store/F-Droid presentation metadata
├── metadata/             F-Droid metadata
├── AndroidManifest.xml
├── build.sh
├── proguard-rules.pro
└── LICENSE
```

## Distribution

MultiBooter is prepared for distribution through F-Droid and can also be installed and updated directly from GitHub Releases using Obtainium.

The application is designed to operate without downloading additional executable components after installation. Required application components are intended to be included in, or built from, the source repository.

## Source code and issues

Source repository:

https://github.com/werimsoln/MultiBooter

Bug reports and feature requests:

https://github.com/werimsoln/MultiBooter/issues

## License

MultiBooter is licensed under the **GNU General Public License v3.0 or later**.

See [LICENSE](LICENSE) for the full license text.

## Disclaimer

Boot-media creation, raw USB writing, USB Gadget configuration and network booting are low-level operations. Hardware and kernel behavior differs between Android devices.

Use the software at your own risk and verify important data before performing destructive operations.

<p align="center">
  <a href="https://f-droid.org/packages/com.werismoln.multibooter/">
    <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
         alt="Get it on F-Droid"
         height="80">
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/werimsoln/MultiBooter">
    <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png"
         alt="Get it on Obtainium"
         height="80">
  </a>
</p>
