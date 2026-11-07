<div align="center">

# MultiBooter

**Bootable USB, ISO writing and USB/PXE boot tricks, straight from your Android phone.**

[![Release](https://img.shields.io/github/v/release/werimsoln/MultiBooter?style=for-the-badge&color=2563EB&label=release)](https://github.com/werimsoln/MultiBooter/releases)
[![License](https://img.shields.io/badge/license-GPLv3%2B-blue?style=for-the-badge)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/werimsoln/MultiBooter/total?style=for-the-badge&color=4CAF50)](https://github.com/werimsoln/MultiBooter/releases)
[![Last commit](https://img.shields.io/github/last-commit/werimsoln/MultiBooter?style=for-the-badge&color=F59E0B)](https://github.com/werimsoln/MultiBooter/commits)
[![Platform](https://img.shields.io/badge/android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#requirements)
[![F-Droid](https://img.shields.io/f-droid/v/com.werismoln.multibooter?style=for-the-badge&logo=f-droid&logoColor=white)](https://f-droid.org/packages/com.werismoln.multibooter/)

[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="72">](https://f-droid.org/packages/com.werismoln.multibooter/)
&nbsp;
[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="72">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.werismoln.multibooter%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fwerimsoln%2FMultiBooter%22%2C%22author%22%3A%22werimsoln%22%2C%22name%22%3A%22MultiBooter%22%7D)

</div>

---

## What it does

| Feature | What you get | Root |
| --- | --- | :---: |
| **Ventoy install** | Installs Ventoy on a USB drive (MBR or GPT) and shows the version already on it | No |
| **ISO writer** | Writes an ISO / CD-ROM image straight to a USB drive | No |
| **USB Gadget** | Makes your phone act as a USB drive or CD-ROM (ConfigFS) | Yes |
| **FunctionFS** | Userspace USB Mass Storage / virtual CD-ROM | Yes |
| **TFTP / PXE** | Network-boot a PC from your phone | Yes |
| **Virtual USB drive** | Your phone acts as a USB flash drive (Ventoy or blank) for a PC | Yes |
| **Drive analysis** | Partitions, free space and the ISO list of a USB drive | No |

Ventoy and the ISO writer talk to the USB drive through the Android USB Host
API (SCSI over Bulk-Only Transport). They never need root.

Running modes keep going in the background and show a notification with a
progress bar (for writes), a **Stop** button, and a tap-to-return shortcut.

## Permissions

Everything is optional. MultiBooter works without any of them and tells you
what is missing.

| Permission | Why | Without it |
| --- | --- | --- |
| Root | USB Gadget, FunctionFS, TFTP | Those three modes stay locked |
| Notifications | Progress, Stop button | Modes still run, no notification |
| Battery optimization exemption | Long running modes | Android may stop background work sooner |

There is no internet permission and no network code in the app. Files are
picked with the system file picker, so no storage permission is needed.

## Install

- **F-Droid:** use the badge above.
- **Obtainium:** use the badge above, or add `https://github.com/werimsoln/MultiBooter`.
- **GitHub:** download the APK from the [releases page](https://github.com/werimsoln/MultiBooter/releases).

## Requirements

- Android 8.0 or newer
- USB OTG support and a USB mass-storage drive for Ventoy / ISO writing
- Root plus a kernel with ConfigFS / FunctionFS support for the root modes

## Build

MultiBooter is built without Gradle or AndroidX (plain Android SDK, NDK and
JDK) with a single script:

```sh
./build.sh
```

Build setup, Ventoy provenance, Secure Boot binaries and F-Droid details are
in [docs/BUILDING.md](docs/BUILDING.md) and
[ASSET_PROVENANCE.md](ASSET_PROVENANCE.md).

## Limits

USB and kernel behavior differs between phones, ROMs and USB controllers. A
feature being listed does not mean every device supports it.

## Help and feedback

[Open an issue](https://github.com/werimsoln/MultiBooter/issues) for bugs and
ideas.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). Ventoy and other bundled
third-party components keep their own upstream licenses; graphics are covered
by [GRAPHICS_LICENSE.md](GRAPHICS_LICENSE.md).

## Disclaimer

MultiBooter performs low-level operations on USB drives. Picking the wrong
device can permanently destroy its data. The root modes also change USB and
network state on your phone. Use it only when you understand what the chosen
operation does.
