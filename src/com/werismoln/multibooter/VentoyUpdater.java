/******************************************************************************
 * VentoyUpdater.java
 *
 * Copyright (c) 2026, Volkan Belek <vlkanblek@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.werismoln.multibooter;

import java.util.Arrays;

/**
 * Plans an in-place Ventoy update: new boot code, new GRUB stage 1 and a new
 * VTOYEFI partition, while the partition table, the disk signature and the
 * data partition (all the user's files) stay untouched. This is the same
 * approach as Ventoy's own "Update" button.
 *
 * Pure Java so it can be tested against disk images.
 */
public final class VentoyUpdater {

    static final int SECTOR = 512;

    /** One contiguous write to the drive. */
    public static final class Write {

        public final long lba;
        public final byte[] data;

        Write(long lba, byte[] data) {
            this.lba = lba;
            this.data = data;
        }
    }

    private VentoyUpdater() {
    }

    /**
     * Sector 0 of an updated disk: boot code from boot.img, but the disk
     * signature (bytes 440-443), the partition table and the 55 AA marker of
     * the existing disk are kept, and so is the Ventoy disk UUID (384-399).
     */
    public static byte[] buildSector0(
        byte[] oldSector0,
        byte[] bootImage,
        boolean gpt
    ) {

        if (
            oldSector0 == null || oldSector0.length < SECTOR ||
            bootImage == null || bootImage.length != SECTOR
        ) {
            throw new IllegalArgumentException("Invalid boot sector data.");
        }

        byte[] out = Arrays.copyOf(oldSector0, SECTOR);

        // Boot code 0..383 and 400..439 come from the new boot.img.
        System.arraycopy(bootImage, 0, out, 0, 384);
        System.arraycopy(bootImage, 400, out, 400, 40);

        // 384..399: Ventoy disk UUID, 440..511: signature + partitions:
        // both remain as they were.

        out[92] = gpt ? (byte) VentoyGpt.CORE_START_LBA : bootImage[92];

        return out;
    }

    /**
     * Everything that has to be written for an update.
     *
     * @param oldSector0 sector 0 of the drive as it is now
     * @param bootImage  512-byte boot.img of the new version
     * @param coreImage  core.img of the new version (2047 sectors)
     * @param gpt        layout of the drive (from VentoyDetector)
     * @param part2Start first sector of the VTOYEFI partition
     * @param efiImage   the 32 MiB VTOYEFI image of the new version
     */
        /** Sector 0 and the GRUB stage 1 (everything except the VTOYEFI image). */
    public static Write[] planBootArea(
        byte[] oldSector0,
        byte[] bootImage,
        byte[] coreImage,
        boolean gpt
    ) {

        byte[] sector0 = buildSector0(oldSector0, bootImage, gpt);

        if (gpt) {

            return new Write[] {
                new Write(0, sector0),
                new Write(VentoyGpt.CORE_START_LBA, VentoyGpt.buildCore(coreImage))
            };
        }

        return new Write[] {
            new Write(0, sector0),
            new Write(1, coreImage)
        };
    }

    public static Write[] plan(
        byte[] oldSector0,
        byte[] bootImage,
        byte[] coreImage,
        boolean gpt,
        long part2Start,
        byte[] efiImage
    ) {

        byte[] sector0 = buildSector0(oldSector0, bootImage, gpt);

        if (gpt) {

            return new Write[] {
                new Write(0, sector0),
                new Write(VentoyGpt.CORE_START_LBA, VentoyGpt.buildCore(coreImage)),
                new Write(part2Start, efiImage)
            };
        }

        return new Write[] {
            new Write(0, sector0),
            new Write(1, coreImage),
            new Write(part2Start, efiImage)
        };
    }
}
