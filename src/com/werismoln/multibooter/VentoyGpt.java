/******************************************************************************
 * VentoyGpt.java
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

import java.util.zip.CRC32;

/**
 * Builds the on-disk structures of a Ventoy GPT disk (512-byte sectors).
 *
 * The layout follows Ventoy's own VentoyFillGpt() / VentoyFillProtectMBR():
 *
 *   LBA 0          protective MBR (boot.img code, boot.img[92] = 34)
 *   LBA 1          primary GPT header
 *   LBA 2..33      primary partition entries (128 x 128 bytes)
 *   LBA 34..2047   GRUB stage 1 (core.img, first sector's block list = 35)
 *   LBA 2048..     partition 1 "Ventoy"  (exFAT)
 *   end - 34 ..    partition 2 "VTOYEFI" (32 MiB, FAT)
 *   last 33 LBAs   backup partition entries + backup GPT header
 *
 * This class has no Android dependencies so it can be unit tested.
 */
final class VentoyGpt {

    static final int SECTOR = 512;

    static final long PART1_START = 2048L;
    static final long VTOYEFI_SECTORS = 65536L;

    /** Sectors used by one copy of the partition entry array. */
    static final int ENTRY_SECTORS = 32;

    /** Primary copy: protective MBR + header + entries. */
    static final int PRIMARY_SECTORS = 2 + ENTRY_SECTORS;

    /** Backup copy: entries + header. */
    static final int BACKUP_SECTORS = ENTRY_SECTORS + 1;

    /** First LBA of the GRUB stage 1 image in GPT mode. */
    static final long CORE_START_LBA = 34L;

    /** Core sectors available between the GPT and partition 1. */
    static final long CORE_SECTORS = PART1_START - CORE_START_LBA;

    private static final int ENTRY_COUNT = 128;
    private static final int ENTRY_SIZE = 128;

    /** EBD0A0A2-B9E5-4433-87C0-68B6B72699C7 (Microsoft basic data). */
    private static final byte[] TYPE_BASIC_DATA = {
        (byte) 0xA2, (byte) 0xA0, (byte) 0xD0, (byte) 0xEB,
        (byte) 0xE5, (byte) 0xB9, (byte) 0x33, (byte) 0x44,
        (byte) 0x87, (byte) 0xC0, (byte) 0x68, (byte) 0xB6,
        (byte) 0xB7, (byte) 0x26, (byte) 0x99, (byte) 0xC7
    };

    private static final long VTOYEFI_ATTRIBUTES = 0x8000000000000000L;

    private VentoyGpt() {
    }

    /** Partition geometry of one Ventoy GPT disk. */
    static final class Layout {

        long totalSectors;

        long part1Start;
        long part1Sectors;

        long part2Start;
        long part2Sectors;

        long lastUsableLba;
    }

    /**
     * Calculates the layout. Partition 2 is aligned down to 4 KiB, like the
     * MBR layout of this app and Ventoy's Linux installer.
     *
     * @return the layout, or null if the disk is too small
     */
    static Layout calculateLayout(long totalSectors) {

        long lastUsable = totalSectors - 34L;

        long part2Start = lastUsable - VTOYEFI_SECTORS + 1L;

        part2Start -= part2Start % 8L;

        long part1Sectors = part2Start - PART1_START;

        if (part1Sectors < 4096L) {
            return null;
        }

        Layout layout = new Layout();

        layout.totalSectors = totalSectors;
        layout.part1Start = PART1_START;
        layout.part1Sectors = part1Sectors;
        layout.part2Start = part2Start;
        layout.part2Sectors = VTOYEFI_SECTORS;
        layout.lastUsableLba = lastUsable;

        return layout;
    }

    /**
     * Returns the first PRIMARY_SECTORS sectors of the disk: protective
     * MBR, GPT header and partition entries.
     *
     * @param bootImage 512-byte boot.img
     * @param diskUuid  16 random bytes (stored in the boot code area)
     * @param diskGuid  16 random bytes (GPT disk GUID)
     * @param guid1     16 random bytes (partition 1 unique GUID)
     * @param guid2     16 random bytes (partition 2 unique GUID)
     */
    static byte[] buildPrimary(
        byte[] bootImage,
        byte[] diskUuid,
        byte[] diskGuid,
        byte[] guid1,
        byte[] guid2,
        Layout layout
    ) {

        if (bootImage == null || bootImage.length != SECTOR) {
            throw new IllegalArgumentException(
                "bootImage must be exactly 512 bytes."
            );
        }

        requireLength(diskUuid, "diskUuid");
        requireLength(diskGuid, "diskGuid");
        requireLength(guid1, "guid1");
        requireLength(guid2, "guid2");

        byte[] out = new byte[PRIMARY_SECTORS * SECTOR];

        writeProtectiveMbr(out, bootImage, diskUuid, layout.totalSectors);

        byte[] entries = buildEntries(guid1, guid2, layout);

        long entriesCrc = crc32(entries, 0, entries.length);

        byte[] header = buildHeader(
            1L,
            layout.totalSectors - 1L,
            2L,
            diskGuid,
            layout,
            entriesCrc
        );

        System.arraycopy(header, 0, out, SECTOR, SECTOR);
        System.arraycopy(entries, 0, out, 2 * SECTOR, entries.length);

        return out;
    }

    /**
     * Returns the last BACKUP_SECTORS sectors of the disk: partition
     * entries followed by the backup header (which is the very last sector).
     * The same GUIDs must be passed as for buildPrimary().
     */
    static byte[] buildBackup(
        byte[] diskGuid,
        byte[] guid1,
        byte[] guid2,
        Layout layout
    ) {

        requireLength(diskGuid, "diskGuid");
        requireLength(guid1, "guid1");
        requireLength(guid2, "guid2");

        byte[] out = new byte[BACKUP_SECTORS * SECTOR];

        byte[] entries = buildEntries(guid1, guid2, layout);

        long entriesCrc = crc32(entries, 0, entries.length);

        byte[] header = buildHeader(
            layout.totalSectors - 1L,
            1L,
            layout.totalSectors - BACKUP_SECTORS,
            diskGuid,
            layout,
            entriesCrc
        );

        System.arraycopy(entries, 0, out, 0, entries.length);
        System.arraycopy(header, 0, out, ENTRY_SECTORS * SECTOR, SECTOR);

        return out;
    }

    /**
     * Returns the part of core.img that fits between the GPT and partition 1,
     * with the first sector's block list pointing at LBA 35 (the sector after
     * the first one), as Ventoy does for GPT.
     */
    static byte[] buildCore(byte[] coreImage) {

        int length = (int) (CORE_SECTORS * SECTOR);

        if (coreImage == null || coreImage.length < length) {
            throw new IllegalArgumentException(
                "core.img is too small."
            );
        }

        byte[] out = new byte[length];

        System.arraycopy(coreImage, 0, out, 0, length);

        out[500] = (byte) (CORE_START_LBA + 1L);

        return out;
    }

    // ---------------------------------------------------------------------

    private static void writeProtectiveMbr(
        byte[] out,
        byte[] bootImage,
        byte[] diskUuid,
        long totalSectors
    ) {

        System.arraycopy(bootImage, 0, out, 0, 446);

        // Same positions the MBR installer uses.
        System.arraycopy(diskUuid, 0, out, 384, 16);
        System.arraycopy(diskUuid, 12, out, 440, 4);

        // boot.img looks for core.img at LBA 1; in GPT mode it is at LBA 34.
        out[92] = (byte) CORE_START_LBA;

        int e = 446;

        out[e] = 0x00;                  // not active
        out[e + 1] = 0;                 // start head
        out[e + 2] = 1;                 // start sector
        out[e + 3] = 0;                 // start cylinder
        out[e + 4] = (byte) 0xEE;       // GPT protective
        out[e + 5] = (byte) 254;        // end head
        out[e + 6] = (byte) 0xFF;       // end sector 63 | cylinder bits 8-9
        out[e + 7] = (byte) 0xFF;       // end cylinder bits 0-7

        long protectedSectors = Math.min(totalSectors - 1L, 0xFFFFFFFFL);

        putLe32(out, e + 8, 1L);
        putLe32(out, e + 12, protectedSectors);

        out[510] = (byte) 0x55;
        out[511] = (byte) 0xAA;
    }

    private static byte[] buildEntries(
        byte[] guid1,
        byte[] guid2,
        Layout layout
    ) {

        byte[] entries = new byte[ENTRY_COUNT * ENTRY_SIZE];

        writeEntry(
            entries,
            0,
            guid1,
            layout.part1Start,
            layout.part1Start + layout.part1Sectors - 1L,
            0L,
            "Ventoy"
        );

        writeEntry(
            entries,
            ENTRY_SIZE,
            guid2,
            layout.part2Start,
            layout.part2Start + layout.part2Sectors - 1L,
            VTOYEFI_ATTRIBUTES,
            "VTOYEFI"
        );

        return entries;
    }

    private static void writeEntry(
        byte[] entries,
        int offset,
        byte[] uniqueGuid,
        long firstLba,
        long lastLba,
        long attributes,
        String name
    ) {

        System.arraycopy(TYPE_BASIC_DATA, 0, entries, offset, 16);
        System.arraycopy(uniqueGuid, 0, entries, offset + 16, 16);

        putLe64(entries, offset + 32, firstLba);
        putLe64(entries, offset + 40, lastLba);
        putLe64(entries, offset + 48, attributes);

        for (int i = 0; i < name.length() && i < 36; i++) {

            char c = name.charAt(i);

            entries[offset + 56 + i * 2] = (byte) (c & 0xFF);
            entries[offset + 56 + i * 2 + 1] = (byte) (c >> 8);
        }
    }

    private static byte[] buildHeader(
        long myLba,
        long alternateLba,
        long entriesLba,
        byte[] diskGuid,
        Layout layout,
        long entriesCrc
    ) {

        byte[] sector = new byte[SECTOR];

        byte[] signature = {
            'E', 'F', 'I', ' ', 'P', 'A', 'R', 'T'
        };

        System.arraycopy(signature, 0, sector, 0, 8);

        sector[10] = 0x01;                          // revision 1.0
        putLe32(sector, 12, 92L);                   // header size
        // 16..19 header CRC (filled below), 20..23 reserved = 0
        putLe64(sector, 24, myLba);
        putLe64(sector, 32, alternateLba);
        putLe64(sector, 40, CORE_START_LBA);        // first usable LBA = 34
        putLe64(sector, 48, layout.lastUsableLba);
        System.arraycopy(diskGuid, 0, sector, 56, 16);
        putLe64(sector, 72, entriesLba);
        putLe32(sector, 80, ENTRY_COUNT);
        putLe32(sector, 84, ENTRY_SIZE);
        putLe32(sector, 88, entriesCrc);

        putLe32(sector, 16, crc32(sector, 0, 92));

        return sector;
    }

    private static long crc32(byte[] data, int offset, int length) {

        CRC32 crc = new CRC32();

        crc.update(data, offset, length);

        return crc.getValue();
    }

    private static void requireLength(byte[] value, String name) {

        if (value == null || value.length != 16) {

            throw new IllegalArgumentException(
                name + " must be exactly 16 bytes."
            );
        }
    }

    private static void putLe32(byte[] b, int o, long v) {

        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF);
        b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putLe64(byte[] b, int o, long v) {

        putLe32(b, o, v & 0xFFFFFFFFL);
        putLe32(b, o + 4, (v >>> 32) & 0xFFFFFFFFL);
    }
}
