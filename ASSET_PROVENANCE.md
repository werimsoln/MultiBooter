# MultiBooter Asset Provenance

This document describes the origin, purpose, licensing and verification
information for binary or generated assets bundled with MultiBooter.

The purpose of this document is to make the provenance of bundled boot
components independently auditable.

---

## Ventoy

**Project:** Ventoy

**Upstream source:**

https://github.com/ventoy/Ventoy

**Upstream version:** Ventoy 1.1.17

**Upstream tag:** `v1.1.17`

**Upstream release archive:**

https://github.com/ventoy/Ventoy/releases/download/v1.1.17/ventoy-1.1.17-linux.tar.gz

**Upstream release archive SHA-256:**

```text
7fb4ed08cef6a6b4d39dd19260d8c80291a78dfdf9af7d461571e23cbbc43805
```

Ventoy and the third-party components distributed with it retain their
respective upstream licenses.

For licensing, source and bundled-component information, see the official
Ventoy source repository and its accompanying licensing and binary-component
documentation.

MultiBooter does not download these boot components at runtime.

The required Ventoy boot assets are bundled with the application so that
Ventoy installation can operate without downloading executable components
after installation.

---

## boot.img

**MultiBooter path:**

`src/main/assets/boot.img`

**Purpose:**

Contains the initial boot-sector data used when preparing a Ventoy USB
device.

**Upstream project:** Ventoy

**Upstream version:** `v1.1.17`

**Upstream release archive:**

`ventoy-1.1.17-linux.tar.gz`

**Path inside the release archive:**

`ventoy-1.1.17/boot/boot.img`

**SHA-256:**

```text
f37cbea83596aef9812f4d984d344b5103913505dfee40dc0025742ea54a6113
```

### Acquisition

The file was extracted unchanged from the official Ventoy 1.1.17 Linux
release archive.

No modification is performed on the file before it is included in
MultiBooter.

---

## core.img

**MultiBooter path:**

`src/main/assets/core.img`

**Purpose:**

Contains the Ventoy boot core written to the target USB device during
Ventoy installation.

**Upstream project:** Ventoy

**Upstream version:** `v1.1.17`

**Upstream release archive:**

`ventoy-1.1.17-linux.tar.gz`

**Path inside the release archive:**

`ventoy-1.1.17/boot/core.img.xz`

**SHA-256 of the decompressed image:**

```text
b6581090947e7cacbd3cee23dfe2216aee9ab368c6508c2c5f3490621e969b84
```

### Acquisition

The file was obtained by losslessly decompressing:

`ventoy-1.1.17/boot/core.img.xz`

from the official Ventoy 1.1.17 Linux release archive.

No modification is performed after decompression.

Ventoy's upstream packaging process generates `core.img` and then compresses
it using XZ for inclusion in the Linux release archive.

---

## ventoy.disk.img

**MultiBooter path:**

`src/main/assets/ventoy.disk.img`

**Purpose:**

Contains the Ventoy VTOYEFI partition image written to the target USB
device during Ventoy installation.

**Upstream project:** Ventoy

**Upstream version:** `v1.1.17`

**Upstream release archive:**

`ventoy-1.1.17-linux.tar.gz`

**Path inside the release archive:**

`ventoy-1.1.17/ventoy/ventoy.disk.img.xz`

**SHA-256 of the decompressed image:**

```text
871f313d60d865a8ee307bc97c961e6cb619143288b4faf811efe9844ca1a003
```

### Acquisition

The file was obtained by losslessly decompressing:

`ventoy-1.1.17/ventoy/ventoy.disk.img.xz`

from the official Ventoy 1.1.17 Linux release archive.

No modification is performed after decompression.

Ventoy's upstream packaging process creates the VTOYEFI partition image as
`ventoy.disk.img` and then compresses it using XZ for distribution.

Some EFI executables contained in Ventoy distributions are cryptographically
signed firmware components. Their exact binary representation may need to be
preserved in order to retain valid firmware signatures.

---

## Verification

The bundled MultiBooter assets can be verified locally with:

```bash
sha256sum \
    src/main/assets/boot.img \
    src/main/assets/core.img \
    src/main/assets/ventoy.disk.img
```

The expected output is:

```text
f37cbea83596aef9812f4d984d344b5103913505dfee40dc0025742ea54a6113  src/main/assets/boot.img
b6581090947e7cacbd3cee23dfe2216aee9ab368c6508c2c5f3490621e969b84  src/main/assets/core.img
871f313d60d865a8ee307bc97c961e6cb619143288b4faf811efe9844ca1a003  src/main/assets/ventoy.disk.img
```

The official Ventoy 1.1.17 Linux release archive can be verified separately
with:

```bash
sha256sum ventoy-1.1.17-linux.tar.gz
```

Expected SHA-256:

```text
7fb4ed08cef6a6b4d39dd19260d8c80291a78dfdf9af7d461571e23cbbc43805  ventoy-1.1.17-linux.tar.gz
```

---

## Reproduction from the Official Release

The bundled assets can be independently reproduced from the official Ventoy
1.1.17 Linux release archive.

Example:

```bash
tar -xzf ventoy-1.1.17-linux.tar.gz

cp \
    ventoy-1.1.17/boot/boot.img \
    boot.img

xz -dc \
    ventoy-1.1.17/boot/core.img.xz \
    > core.img

xz -dc \
    ventoy-1.1.17/ventoy/ventoy.disk.img.xz \
    > ventoy.disk.img
```

The resulting files can then be checked with:

```bash
sha256sum \
    boot.img \
    core.img \
    ventoy.disk.img
```

They should produce the hashes documented above.

---

## Runtime Behavior

The bundled Ventoy assets are not executed as Android application code.

MultiBooter accesses the selected USB mass-storage device through the Android
USB Host API and writes the required Ventoy disk structures to that device.

The general data flow is:

```text
Ventoy assets bundled in MultiBooter
                |
                v
        Android USB Host API
                |
                v
     USB Mass Storage / SCSI
                |
                v
       User-selected USB drive
                |
                v
      PC BIOS / UEFI boot process
```

The Ventoy boot components are therefore intended for execution by the target
computer's BIOS/UEFI boot environment after being written to the USB device,
not by the Android runtime.

---

## Runtime Downloads

MultiBooter does not download Ventoy executable or boot components after
installation.

The required Ventoy boot assets are included in the application package.

This allows the Ventoy installation feature to operate without fetching
additional executable content from the network.

---

## Licensing

MultiBooter is distributed under the GNU General Public License.

Ventoy and all third-party components contained in the Ventoy boot images
retain their respective upstream licenses.

MultiBooter does not claim ownership of the Ventoy boot components.

For detailed information about Ventoy source code, third-party components,
binary components and their licensing, refer to the official Ventoy
repository:

https://github.com/ventoy/Ventoy

and the upstream binary-component documentation:

https://github.com/ventoy/Ventoy/blob/master/BLOB_List.md

---

## Upstream Build Information

Ventoy's upstream build and packaging process can be inspected in the
official source repository.

In particular, the upstream packaging process creates:

- `boot/boot.img`
- `boot/core.img`
- `ventoy/ventoy.disk.img`

The latter two images are compressed using XZ before being included in the
official Linux release archive.

MultiBooter uses the corresponding decompressed images required for direct
raw USB installation.
