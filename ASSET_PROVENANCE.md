# MultiBooter Asset Provenance

This document records the origin, version, licensing, verification status and
rebuild model of binary or generated boot assets used by MultiBooter.

The purpose of this document is to make the provenance of bundled boot
components independently auditable and to clearly distinguish:

1. official upstream Ventoy release assets;
2. firmware-trusted Secure Boot binaries preserved from the upstream release;
3. the deterministic VTOYEFI image reconstructed by the MultiBooter build;
4. the files shipped inside the application package.

MultiBooter does not claim ownership of Ventoy or any third-party components
contained in Ventoy assets.

---

## 1. Ventoy upstream source

**Project:** Ventoy

**Repository:**

https://github.com/ventoy/Ventoy

**Version:** `1.1.17`

**Tag:** `v1.1.17`

**Tag commit:**

`7cbdc5cf69935bcf1f085ae67f40e70ea7e74bae`

Ventoy 1.1.17's upstream `BLOB_List.md` identifies the origins of the
relevant EFI components, including Rocky Linux 9.8 Secure Boot binaries and
Ventoy-built EFI components.

The F-Droid metadata pins the Ventoy source library to the exact commit above.

---

## 2. Official Ventoy release archive

**Release archive:**

https://github.com/ventoy/Ventoy/releases/download/v1.1.17/ventoy-1.1.17-linux.tar.gz

**Archive SHA-256:**

```text
7fb4ed08cef6a6b4d39dd19260d8c80291a78dfdf9af7d461571e23cbbc43805
```

The archive above is an independent upstream provenance reference for the
Ventoy 1.1.17 assets documented in this file.

The current F-Droid build does not use this release archive directly.
Instead, the F-Droid build obtains Ventoy through its pinned source library.
The archive SHA-256 is retained as an independently verifiable upstream
reference and must be updated when the documented Ventoy release changes.

Example verification:

```bash
sha256sum ventoy-1.1.17-linux.tar.gz
```

Expected result:

```text
7fb4ed08cef6a6b4d39dd19260d8c80291a78dfdf9af7d461571e23cbbc43805  ventoy-1.1.17-linux.tar.gz
```

---

# 3. Official Ventoy boot assets

## 3.1 boot.img

**MultiBooter path:**

```text
src/main/assets/boot.img
```

**Purpose:**

Contains the Ventoy MBR boot-sector image used as the source of the boot
code written to the beginning of a target USB device.

**Upstream project:** Ventoy

**Upstream version:** `1.1.17`

**Release archive path:**

```text
ventoy-1.1.17/boot/boot.img
```

**Acquisition method:**

The file is extracted unchanged from the official Ventoy 1.1.17 Linux release
archive.

**SHA-256:**

```text
f37cbea83596aef9812f4d984d344b5103913505dfee40dc0025742ea54a6113
```

**Modification status:**

The bundled file is not modified after extraction.

During installation, MultiBooter uses only the required boot-sector data
needed to construct the target MBR. The partition table and disk-specific
values are generated separately by MultiBooter.

The current `build.sh` verifies this bundled asset before packaging.

---

## 3.2 core.img

**MultiBooter path:**

```text
src/main/assets/core.img
```

**Purpose:**

Contains the Ventoy BIOS boot core written to the target USB device.

**Upstream project:** Ventoy

**Upstream version:** `1.1.17`

**Release archive path:**

```text
ventoy-1.1.17/boot/core.img.xz
```

**Acquisition method:**

The file is losslessly decompressed from:

```text
ventoy-1.1.17/boot/core.img.xz
```

using XZ decompression.

**SHA-256 of the decompressed image:**

```text
b6581090947e7cacbd3cee23dfe2216aee9ab368c6508c2c5f3490621e969b84
```

**Modification status:**

No modification is performed after decompression.

The decompressed image is written to the Ventoy BIOS boot region as required
by the MultiBooter MBR installation procedure.

The current `build.sh` verifies this bundled asset before packaging.

---

# 4. Official Ventoy VTOYEFI image

## 4.1 Original upstream image

**MultiBooter path:**

```text
src/main/assets/ventoy.disk.img
```

**Purpose:**

Provides the official Ventoy VTOYEFI partition image used as the upstream
reference and as the source of firmware-trusted EFI files used by the
F-Droid build process.

**Upstream project:** Ventoy

**Upstream version:** `1.1.17`

**Release archive path:**

```text
ventoy-1.1.17/ventoy/ventoy.disk.img.xz
```

**Acquisition method:**

The image is losslessly decompressed from the official Ventoy release:

```bash
xz -dc \
    ventoy-1.1.17/ventoy/ventoy.disk.img.xz \
    > ventoy.disk.img
```

**Original upstream SHA-256:**

```text
871f313d60d865a8ee307bc97c961e6cb619143288b4faf811efe9844ca1a003
```

This hash identifies the original official Ventoy 1.1.17 VTOYEFI image
before any MultiBooter/F-Droid reconstruction.

The current `build.sh` verifies this exact SHA-256 before removing and
recreating the checked-in `ventoy.disk.img`.

**Important:**

The original upstream image and the deterministic MultiBooter-reconstructed
image are intentionally treated as different artifacts.

The original upstream hash above must not be presented as the hash of the
final F-Droid rebuilt image.

---

# 5. F-Droid reconstructed VTOYEFI image

The F-Droid build does not simply redistribute the complete upstream
`ventoy.disk.img`.

Instead, the build process:

1. obtains the pinned Ventoy 1.1.17 source tree;
2. verifies the checked-in upstream reference image before replacing it;
3. extracts and verifies the firmware-trusted Secure Boot EFI binaries;
4. inserts those verified binaries into the pinned Ventoy source tree;
5. removes non-Secure-Boot prebuilt payloads excluded from the F-Droid build;
6. reconstructs a deterministic 32 MiB FAT16 VTOYEFI image;
7. verifies the rebuilt image SHA-256;
8. packages the rebuilt image into the APK.

The reconstructed image therefore has a different SHA-256 from the original
official upstream image.

## 5.1 Rebuild inputs

The rebuild uses the following Ventoy source locations:

```text
VENTOY_SRC/INSTALL/grub
VENTOY_SRC/INSTALL/EFI
VENTOY_SRC/INSTALL/ventoy
VENTOY_SRC/INSTALL/tool/ENROLL_THIS_KEY_IN_MOKMANAGER.cer
```

The F-Droid metadata currently pins the Ventoy source as:

```text
Ventoy@7cbdc5cf69935bcf1f085ae67f40e70ea7e74bae
```

The build uses deterministic metadata settings:

```text
SOURCE_DATE_EPOCH = 1735689600
TZ = UTC
```

The F-Droid metadata passes the timestamp explicitly to `build.sh` as:

```text
VENTOY_IMAGE_EPOCH=1735689600
```

The FAT image is created as:

```text
32 MiB
FAT16
Volume label: VTOYEFI
FAT ID: 56544f59
```

The build also normalizes relevant file timestamps and uses deterministic
ordering for archive and directory operations.

---

## 5.2 Excluded non-Secure-Boot payloads

For the F-Droid build, the following Ventoy prebuilt payloads are removed:

```text
INSTALL/ventoy/imdisk
INSTALL/ventoy/memdisk
INSTALL/ventoy/7z
```

In addition, GRUB i386 disk-image files under:

```text
INSTALL/grub/i386-pc/
```

are excluded from the reconstructed VTOYEFI image.

These exclusions are part of the MultiBooter F-Droid packaging policy and are
not applied to the documented original upstream SHA-256 baseline.

---

## 5.3 Rebuilt image SHA-256

The build currently records the following SHA-256 as the expected output for
the deterministic VTOYEFI reconstruction:

```text
830d225ec39c06dcd57fd38f38ae784a1123588b2f27cde9e9a49e86d6fc2113
```

This value is hard-coded in `build.sh` as:

```text
VENTOY_EXPECTED_REBUILT_SHA256
```

A mismatch is fatal and stops the build.

The value represents the expected output for the exact documented build
inputs and deterministic settings. It must be recomputed and reviewed
whenever the Ventoy source, bundled EFI assets, image-generation procedure,
or deterministic build inputs are intentionally changed.

---

# 6. Secure Boot EFI provenance

The Ventoy 1.1.17 upstream `BLOB_List.md` identifies the following Secure Boot
files as upstream or preserved firmware-trusted components.

MultiBooter preserves these exact binaries rather than rebuilding them from
source.

This is necessary because rebuilding signed PE/EFI binaries can change their
binary representation and invalidate their existing firmware-trusted
signatures.

The current F-Droid build removes these four files from the Ventoy source tree
during `prebuild`, extracts them from the checked-in reference image, verifies
their SHA-256 values, and restores them into the pinned Ventoy source tree.

## 6.1 BOOTX64.EFI

**Path inside VTOYEFI:**

```text
EFI/BOOT/BOOTX64.EFI
```

**Role:**

First-stage x86_64 UEFI Secure Boot loader / shim.

**Origin:**

Rocky Linux 9.8 x86_64 Secure Boot component, as documented by Ventoy
`BLOB_List.md`.

**SHA-256:**

```text
1ff3f223c2fcf5b11615d042fcb5674c4651bbbc8505b5b2987d60da0cb65d1a
```

**Policy:**

Preserve the exact binary.

Do not rebuild this file for packaging.

The current F-Droid `prebuild` step and `build.sh` both verify its exact
SHA-256.

---

## 6.2 mmx64.efi

**Path inside VTOYEFI:**

```text
EFI/BOOT/mmx64.efi
```

**Role:**

MOK Manager used by the Secure Boot chain.

**Origin:**

Rocky Linux 9.8 x86_64 Secure Boot component, as documented by Ventoy
`BLOB_List.md`.

**SHA-256:**

```text
1a3687f923d077080fe49feb470e3932c2b1d3fd4c6439123aa0226246a24522
```

**Policy:**

Preserve the exact binary.

Do not rebuild this file for packaging.

The current F-Droid `prebuild` step and `build.sh` both verify its exact
SHA-256.

---

## 6.3 fbx64.efi

**Path inside VTOYEFI:**

```text
EFI/BOOT/fbx64.efi
```

**Role:**

Ventoy Secure Boot fallback companion.

**Origin:**

Ventoy-provided signed fallback EFI component.

**SHA-256:**

```text
c8fc4661f4b64b916e37e4fdd68042d3d64290a696add9199afb84c12ad896c8
```

**Policy:**

Preserve the exact binary used by the pinned Ventoy release.

Do not replace it with an independently rebuilt binary unless the upstream
Ventoy release and its resulting signature/provenance are intentionally
updated.

The current F-Droid `prebuild` step and `build.sh` both verify its exact
SHA-256.

---

## 6.4 grubx64_real.efi

**Path inside VTOYEFI:**

```text
EFI/BOOT/grubx64_real.efi
```

**Role:**

Ventoy GRUB payload loaded as part of the x86_64 boot chain.

**Origin:**

Ventoy build output for the pinned Ventoy 1.1.17 release.

**SHA-256:**

```text
907c99a8370e953eb4ec34df2c314cf979356bfca97733ccb1139ee3f5e98cce
```

**Policy:**

Use the exact verified binary associated with the documented Ventoy 1.1.17
asset baseline.

The current F-Droid `prebuild` step and `build.sh` both verify its exact
SHA-256.

---

# 7. Secure Boot certificate

## ENROLL_THIS_KEY_IN_MOKMANAGER.cer

**Path inside the VTOYEFI image:**

```text
ENROLL_THIS_KEY_IN_MOKMANAGER.cer
```

**Purpose:**

Ventoy certificate used for MOK enrollment where required by the Secure Boot
workflow.

**Upstream project:** Ventoy

**Upstream version:** `1.1.17`

**Source path in Ventoy:**

```text
INSTALL/tool/ENROLL_THIS_KEY_IN_MOKMANAGER.cer
```

The current build copies this certificate from the pinned Ventoy source tree
when it is present.

The exact certificate hash is recorded here for provenance:

```text
8072e285ed57ffd63421beb52d5c27cb5ad70a8d7377b67b358f816f97012e27
```

The current `build.sh` does not perform a SHA-256 check on this certificate.
Therefore this value is presently a documented provenance value, not an
enforced build-time integrity check.

If certificate verification is added in the future, the build should fail on
a mismatch.

---

# 8. Verification of bundled upstream assets

The original bundled Ventoy reference assets should verify as follows:

```bash
sha256sum \
    src/main/assets/boot.img \
    src/main/assets/core.img \
    src/main/assets/ventoy.disk.img
```

Expected output:

```text
f37cbea83596aef9812f4d984d344b5103913505dfee40dc0025742ea54a6113  src/main/assets/boot.img
b6581090947e7cacbd3cee23dfe2216aee9ab368c6508c2c5f3490621e969b84  src/main/assets/core.img
871f313d60d865a8ee307bc97c961e6cb619143288b4faf811efe9844ca1a003  src/main/assets/ventoy.disk.img
```

The current `build.sh` verifies all three values before packaging.

The official Ventoy archive itself should independently verify to:

```text
7fb4ed08cef6a6b4d39dd19260d8c80291a78dfdf9af7d461571e23cbbc43805
```

---

# 9. Verification of Secure Boot binaries

The four x86_64 Secure Boot binaries are extracted from the checked-in
reference image during the F-Droid `prebuild` step.

The metadata then verifies their SHA-256 values before the build continues.

The `build.sh` also verifies the corresponding files in the supplied Ventoy
source tree.

Example extraction:

```bash
mcopy -i src/main/assets/ventoy.disk.img \
    ::/EFI/BOOT/BOOTX64.EFI \
    /tmp/BOOTX64.EFI

mcopy -i src/main/assets/ventoy.disk.img \
    ::/EFI/BOOT/mmx64.efi \
    /tmp/mmx64.efi

mcopy -i src/main/assets/ventoy.disk.img \
    ::/EFI/BOOT/fbx64.efi \
    /tmp/fbx64.efi

mcopy -i src/main/assets/ventoy.disk.img \
    ::/EFI/BOOT/grubx64_real.efi \
    /tmp/grubx64_real.efi
```

Then:

```bash
sha256sum \
    /tmp/BOOTX64.EFI \
    /tmp/mmx64.efi \
    /tmp/fbx64.efi \
    /tmp/grubx64_real.efi
```

Expected hashes:

```text
1ff3f223c2fcf5b11615d042fcb5674c4651bbbc8505b5b2987d60da0cb65d1a  /tmp/BOOTX64.EFI
1a3687f923d077080fe49feb470e3932c2b1d3fd4c6439123aa0226246a24522  /tmp/mmx64.efi
c8fc4661f4b64b916e37e4fdd68042d3d64290a696add9199afb84c12ad896c8  /tmp/fbx64.efi
907c99a8370e953eb4ec34df2c314cf979356bfca97733ccb1139ee3f5e98cce  /tmp/grubx64_real.efi
```

Any mismatch in the current F-Droid prebuild or `build.sh` verification must
fail the build.

---

# 10. F-Droid source rebuild model

The current F-Droid build metadata pins the Ventoy source to the exact
Ventoy 1.1.17 tag commit:

```text
Ventoy@7cbdc5cf69935bcf1f085ae67f40e70ea7e74bae
```

The corresponding MultiBooter F-Droid build source revision is:

```text
26252db66473075511348854fbe90deb0f9f9dd0
```

The build process does not download Ventoy executable or boot components at
runtime.

The intended build flow is:

```text
Pinned Ventoy 1.1.17 source
            |
            v
Checked-in official reference image
            |
            v
Extract Secure Boot EFI binaries
            |
            v
Verify exact SHA-256 values
            |
            v
Replace corresponding files in Ventoy source
            |
            v
Remove excluded non-Secure-Boot prebuilt payloads
            |
            v
Rebuild deterministic FAT16 VTOYEFI image
            |
            v
Verify rebuilt image SHA-256
            |
            v
Package image into MultiBooter APK
```

The F-Droid metadata is responsible for pinning the Ventoy source to the
documented Git commit. The current `build.sh` verifies the expected Ventoy
directory structure and the hashes of the critical assets, but it does not
perform a separate `git rev-parse HEAD` check.

The current build must fail if:

- the required Ventoy source directories are missing;
- the checked-in reference image hash does not match;
- any required Secure Boot binary is missing;
- any Secure Boot binary has an unexpected SHA-256;
- the rebuilt VTOYEFI image does not match its documented expected SHA-256.

---

# 11. Runtime asset policy

MultiBooter does not execute the bundled Ventoy boot assets as Android
application code.

The assets are raw boot-media data.

The intended runtime flow is:

```text
Bundled Ventoy assets
        |
        v
Android USB Host API
        |
        v
USB Mass Storage / SCSI
        |
        v
User-selected USB device
        |
        v
PC BIOS / UEFI boot environment
```

The Ventoy boot assets are therefore intended for execution by the target
computer's firmware and boot environment after they have been written to the
USB device.

MultiBooter does not download Ventoy executable or boot components after
application installation.

---

# 12. Runtime integrity verification

The intended policy is to verify the exact SHA-256 of every bundled asset used
by the installer before any destructive Ventoy USB write operation begins.

The policy covers:

```text
boot.img
    -> exact expected SHA-256

core.img
    -> exact expected SHA-256

ventoy.disk.img
    -> exact expected SHA-256 for the image being shipped
```

File-size checks alone are not sufficient to establish asset integrity.

**Current implementation status:**

The repository currently contains build-time SHA-256 verification for the
relevant Ventoy assets in `build.sh`.

No Android runtime `MessageDigest`/SHA-256 verification for these bundled
assets was found in the current source used for this provenance review.

Therefore the runtime verification described in this section is currently a
policy requirement, not an implemented runtime check.

When runtime verification is implemented, it must complete before the first
destructive USB write.

A runtime verification failure must abort the installation.

---

# 13. Reproducibility and expected-output recording

The repository records two different VTOYEFI image identities.

### Official upstream reference

```text
871f313d60d865a8ee307bc97c961e6cb619143288b4faf811efe9844ca1a003
```

### MultiBooter/F-Droid reconstructed output

```text
830d225ec39c06dcd57fd38f38ae784a1123588b2f27cde9e9a49e86d6fc2113
```

These hashes must not be conflated.

The rebuilt-image hash is an expected build result for the documented
reproducible input set. It is enforced by `build.sh` through
`VENTOY_EXPECTED_REBUILT_SHA256`.

After any intentional Ventoy asset update:

1. update the pinned Ventoy version and tag commit;
2. verify the upstream release archive hash;
3. update the official asset hashes;
4. update Secure Boot binary hashes when upstream changes them;
5. rebuild the deterministic VTOYEFI image;
6. verify the resulting rebuilt-image SHA-256;
7. update this document;
8. update the F-Droid metadata;
9. update the corresponding MultiBooter source revision;
10. verify the final build from a clean environment.

No rebuilt-image hash should be changed merely to make a build pass.

A changed hash must correspond to a documented upstream, source, packaging or
deterministic-build change.

---

# 14. Licensing

MultiBooter is distributed under:

```text
GNU General Public License v3.0 or later
```

Ventoy and its third-party components retain their respective upstream
licenses.

The presence of a Ventoy-derived boot asset in MultiBooter does not transfer
ownership of that asset to MultiBooter.

Ventoy source, binary-component and licensing information is available from:

https://github.com/ventoy/Ventoy

In particular:

https://github.com/ventoy/Ventoy/blob/master/BLOB_List.md

and the Ventoy project documentation.

For Secure Boot components originating from other projects, the applicable
upstream licensing and redistribution conditions remain in force.

---

# 15. Update policy

Ventoy assets are updated only when an intentional upstream version change is
made.

An update must use the exact upstream Ventoy release tag and corresponding
source commit.

The following must be reviewed together:

```text
Ventoy version
Ventoy tag commit
release archive SHA-256
boot.img SHA-256
core.img SHA-256
official ventoy.disk.img SHA-256
Secure Boot EFI hashes
MOK certificate hash
reconstructed VTOYEFI SHA-256
F-Droid metadata
MultiBooter source revision
runtime asset hashes
```

The Ventoy source pin used by F-Droid must correspond to the documented
Ventoy tag commit.

No asset hash may be changed merely to make a build pass.

A hash change must correspond to a documented upstream or packaging change.

---

# 16. Audit summary

For the current F-Droid release build, the MultiBooter source revision
specified by the metadata is:

```text
26252db66473075511348854fbe90deb0f9f9dd0
```

The provenance chain for the current Ventoy integration is:

```text
Ventoy v1.1.17
    |
    +-- tag: v1.1.17
    |   commit: 7cbdc5cf69935bcf1f085ae67f40e70ea7e74bae
    |
    +-- release archive
    |   SHA-256:
    |   7fb4ed08cef6a6b4d39dd19260d8c80291a78dfdf9af7d461571e23cbbc43805
    |
    +-- boot.img
    |   SHA-256:
    |   f37cbea83596aef9812f4d984d344b5103913505dfee40dc0025742ea54a6113
    |
    +-- core.img
    |   SHA-256:
    |   b6581090947e7cacbd3cee23dfe2216aee9ab368c6508c2c5f3490621e969b84
    |
    +-- official ventoy.disk.img
    |   SHA-256:
    |   871f313d60d865a8ee307bc97c961e6cb619143288b4faf811efe9844ca1a003
    |
    +-- preserved Secure Boot components
    |      BOOTX64.EFI
    |      mmx64.efi
    |      fbx64.efi
    |      grubx64_real.efi
    |
    +-- MultiBooter F-Droid build source
    |   commit:
    |   76a4ae198102433b663a81f1b7596bb73347be41
    |
    +-- pinned Ventoy source rebuild
           |
           +-- non-Secure-Boot blobs removed
           |
           +-- deterministic FAT16 image generated
           |
           +-- rebuilt SHA-256 verified:
               830d225ec39c06dcd57fd38f38ae784a1123588b2f27cde9e9a49e86d6fc2113
```

The official upstream image is the provenance baseline.

The reconstructed image is the F-Droid packaging artifact.

The two artifacts have distinct identities and must remain separately
documented.

The current documentation distinguishes between build-time integrity checks
that are implemented and runtime integrity checks that remain a future
policy requirement.
