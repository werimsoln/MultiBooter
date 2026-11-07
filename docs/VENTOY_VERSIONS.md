# Shipping more than one Ventoy version

Each Ventoy release adds about 16 MB to the APK as a full `ventoy.disk.img`
(32 MiB, 12.6 MiB with xz, about 16 MiB with the APK's deflate). Shipping four
versions that way would cost about 64 MB. Patches against the newest image
need only a fraction of that.

## What was measured

Official Ventoy releases, each `ventoy.disk.img` compared with 1.1.18:

| Older version | Same 4 KiB blocks | Patch (xdelta3, raw) | Patch + deflate |
| --- | --- | --- | --- |
| 1.1.17 | 13 % | 2.9 MB | 2.8 MB |
| 1.1.10 | 1 % | 3.9 MB | 3.6 MB |
| 1.1.05 | 1 % | 4.0 MB | 3.7 MB |
| 1.0.99 | 1 % | 4.0 MB | 3.7 MB |

Takeaways:

- Comparing the images **block by block saves nothing**. About 99 % of the
  4 KiB blocks differ, because files move around inside the FAT file system
  between releases even when their content is identical (929 of 992 files are
  byte-for-byte the same between 1.0.99 and 1.1.18).
- A patch that can **copy from any offset** (VCDIFF / xdelta3) finds that
  shared content and is about 4-5 times smaller than a full image.
- `core.img` (GRUB stage 1) changes in only 883 of 2047 sectors and is about
  440 KB per version; ship it whole for each version.

Four extra versions therefore cost about 15 MB instead of about 64 MB.

## Proposed asset layout

```
assets/ventoy.disk.img          newest version, full (as today)
assets/ventoy.version           "1.1.18"
assets/ventoy/1.1.17/efi.vcdiff patch: newest image -> 1.1.17 image
assets/ventoy/1.1.17/core.img   GRUB stage 1 of 1.1.17
assets/ventoy/1.1.17/version    "1.1.17"
...
```

`boot.img` is identical across these releases (512 bytes) and can stay a single
file; compare it per version at build time and fail the build if it ever
differs.

## Build step

For every extra version, build its `ventoy.disk.img` exactly like the newest
one (same pinned sources, same deterministic image generation as `build.sh`),
then:

```sh
tools/make-ventoy-delta.sh newest/ventoy.disk.img v1.1.17/ventoy.disk.img \
    src/main/assets/ventoy/1.1.17/efi.vcdiff
```

The script applies the patch again and fails unless the result equals the
older image byte for byte. Install `xdelta3` on the build machine.

For F-Droid each extra version needs its own pinned `srclib` entry (its own
source commit and the same SHA-256 checks of the Secure Boot binaries).

## At run time

`VcdiffDecoder.decode(newestImage, patch)` rebuilds the older image in memory
in well under a second (tested bit-exact on 1.1.17, 1.1.10, 1.1.05 and
1.0.99). The installer then writes it like any other image. The decoder checks
the Adler-32 sum that xdelta3 stores, so a damaged patch is rejected.

Memory: the newest image (32 MiB) plus the rebuilt image (32 MiB) are held at
once. That is fine on current phones; the installer should rebuild the image
only after the user picked an older version.

## Not done yet

The version picker on the Ventoy screen and reading `assets/ventoy/*` are not
implemented; they need the extra versions to be built first. The decoder, the
patch tool and the measurements above are ready.
