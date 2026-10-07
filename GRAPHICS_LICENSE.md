# Graphics: sources and licenses

This document lists every icon, logo and image shipped with MultiBooter, where
it comes from and under which license it is distributed.

MultiBooter itself is licensed under `GPL-3.0-or-later`. The vector icons
listed in section 1 are derived from Google's Material Icons and keep their
own license, `Apache-2.0`. Apache-2.0 is compatible with GPL-3.0, so both can
be combined in one application.

---

## 1. Vector icons (Material Icons)

| File in `res/drawable/` | Material Icons name | Style |
| --- | --- | --- |
| `battery.xml` | `battery_charging_full` | filled |
| `bell.xml` | `notifications` | filled |
| `boot_gadget.xml` | `usb` | filled |
| `boot_tftp.xml` | `dns` | filled |
| `cdrom_to_usb.xml` | `album` | filled |
| `folder.xml` | `folder` | filled |
| `hash.xml` | `tag` | filled |
| `ventoy_bootable_usb.xml` | `usb` | filled |
| `ic_home.xml` | `home` | filled |
| `ic_hamburger.xml` | `menu` | filled |
| `ic_about.xml` | `info` | outlined |
| `ic_settings.xml` | `settings` | filled |
| `ic_coffee.xml` | `coffee` | filled |

- **Author / source:** Google, Material Icons.
- **Obtained from:** npm package `@material-design-icons/svg`, version `0.14.15`.
- **License:** Apache License 2.0. The license text is included unchanged in
  `LICENSES/Apache-2.0-MaterialIcons.txt`.
- **Changes made by MultiBooter:** each SVG was converted to an Android
  `VectorDrawable` XML file. The fill color and the drawable size were set for
  use in the app. The icon geometry was not edited. Each XML file carries a
  comment naming its source icon.
- **NOTICE file:** the `0.14.15` package contains no `NOTICE` file, so there are
  no additional attribution notices to reproduce.

---

## 2. App logo and launcher icon

| File | Description |
| --- | --- |
| `res/drawable/ic_launcher_foreground.xml` | Adaptive launcher icon, foreground layer |
| `res/drawable/ic_launcher_background.xml` | Adaptive launcher icon, background layer (blue gradient) |
| `res/drawable/logo.xml` | Logo used on the splash screen and the intro slide |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon definition |
| `fastlane/metadata/android/en-US/images/icon.png` | 512x512 store icon, rendered from the same artwork |

- **Source:** the logo is a composition of two Material Icons: the outlined
  `desktop_windows` (monitor) and the filled `settings` (gear), with the gear
  scaled to half size and placed inside the monitor screen.
- **License of the Material-derived geometry:** Apache License 2.0 (see
  section 1).
- **License of the composition** (the arrangement, the colors and the gradient
  background): `GPL-3.0-or-later`, by the MultiBooter authors.
- **Changes made:** the path data was scaled and positioned; the transforms are
  baked into the path data so the files render the same in tools that ignore
  `<group>` transforms.

---

## 3. Other drawables, layouts and shapes

All other XML drawables, shape drawables, layouts and styles in `res/`
(for example `boot_card.xml` and `boot_option_selected.xml`) are original work
of the MultiBooter authors, licensed under `GPL-3.0-or-later`.

---

## 4. Screenshots

The images in `fastlane/metadata/android/*/images/phoneScreenshots/` are
screenshots of MultiBooter's own user interface and are licensed under
`GPL-3.0-or-later`.

---

## 5. Third-party images

No third-party raster images are shipped in the application resources or in
the vendored sources.

- The vendored dnsmasq tree omits upstream's `logo/` and `contrib/` folders
  (see `src/native/dnsmasq/UPSTREAM.md`).
- Boot assets (`boot.img`, `core.img`, `ventoy.disk.img`) are documented in
  `ASSET_PROVENANCE.md`.

---

## 6. Adding or changing graphics

Before committing a new image or icon, add a row to this file with its source,
author and license. Only add material whose license allows modification and
redistribution in a GPL-3.0-or-later application (for example CC0, CC BY,
MIT, Apache-2.0 or GPL). Do not add images whose license is unknown, or that
only permit personal use, or that forbid redistribution.
