/******************************************************************************
 * ExFatFormatter.java
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

import java.io.IOException;
import java.util.Arrays;

/**
 * Pure Java exFAT formatter (512-byte sectors) for disk image files.
 *
 * It writes only the metadata: boot regions with the checksum sector and
 * their backup, the FAT, the allocation bitmap, a case-folding up-case table
 * and the root directory with the volume label. Everything else stays zero,
 * so a sparse image file stays small.
 *
 * Used for virtual USB drives. USB drives themselves are still formatted by
 * the native libexfat.
 */
public final class ExFatFormatter {

    /** Where the metadata is written. Offsets are in bytes from the volume start. */
    public interface Sink {

        void write(long offset, byte[] data) throws IOException;
    }

    public static final int SECTOR = 512;

    private static final int FAT_OFFSET_SECTORS = 128;

    private static final int UPCASE_ENTRIES = 65536;

    private ExFatFormatter() {
    }

    /** Smallest size that can be formatted (about 1 MiB). */
    public static final long MIN_SECTORS = 2048;

    /**
     * @param volumeSectors  size of the volume in 512-byte sectors
     * @param partitionStart absolute start of the volume (stored in the boot sector)
     * @param label          volume label, at most 11 characters
     */
    public static void format(
        Sink sink,
        long volumeSectors,
        long partitionStart,
        String label,
        int serial
    ) throws IOException {

        if (volumeSectors < MIN_SECTORS) {
            throw new IOException("The volume is too small for exFAT.");
        }

        if (label == null) {
            label = "";
        }

        if (label.length() > 11) {
            label = label.substring(0, 11);
        }

        // ---- geometry ----
        int sectorsPerCluster = clusterSectors(volumeSectors);
        int clusterShift = Integer.numberOfTrailingZeros(sectorsPerCluster);
        int clusterBytes = sectorsPerCluster * SECTOR;

        long fatSectors =
            (((volumeSectors / sectorsPerCluster) + 2) * 4 + SECTOR - 1) / SECTOR;

        long heapOffset = FAT_OFFSET_SECTORS + fatSectors;

        heapOffset =
            ((heapOffset + sectorsPerCluster - 1) / sectorsPerCluster) *
            sectorsPerCluster;

        long clusterCount = (volumeSectors - heapOffset) / sectorsPerCluster;

        if (clusterCount < 16 || clusterCount > 0xFFFFFFF5L) {
            throw new IOException("The volume size is not supported.");
        }

        long bitmapBytes = (clusterCount + 7) / 8;

        int bitmapClusters = (int) ((bitmapBytes + clusterBytes - 1) / clusterBytes);

        byte[] upcase = buildUpcase();

        int upcaseClusters = (upcase.length + clusterBytes - 1) / clusterBytes;

        int bitmapFirst = 2;
        int upcaseFirst = bitmapFirst + bitmapClusters;
        int rootFirst = upcaseFirst + upcaseClusters;

        long usedClusters = bitmapClusters + upcaseClusters + 1;

        if (usedClusters > clusterCount) {
            throw new IOException("The volume is too small for exFAT.");
        }

        // ---- boot region ----
        byte[] region = new byte[SECTOR * 12];

        byte[] boot = new byte[SECTOR];

        boot[0] = (byte) 0xEB;
        boot[1] = 0x76;
        boot[2] = (byte) 0x90;

        byte[] name = { 'E', 'X', 'F', 'A', 'T', ' ', ' ', ' ' };

        System.arraycopy(name, 0, boot, 3, 8);

        putLe64(boot, 64, partitionStart);
        putLe64(boot, 72, volumeSectors);
        putLe32(boot, 80, FAT_OFFSET_SECTORS);
        putLe32(boot, 84, fatSectors);
        putLe32(boot, 88, heapOffset);
        putLe32(boot, 92, clusterCount);
        putLe32(boot, 96, rootFirst);
        putLe32(boot, 100, serial);

        boot[104] = 0x00;                           // revision 1.00
        boot[105] = 0x01;
        // 106-107 volume flags = 0

        boot[108] = 9;                              // 512-byte sectors
        boot[109] = (byte) clusterShift;
        boot[110] = 1;                              // one FAT
        boot[111] = (byte) 0x80;                    // drive select

        boot[112] = (byte) Math.min(100, usedClusters * 100 / clusterCount);

        // 120-509: boot code, left as 0xF4 (HLT) like other formatters
        Arrays.fill(boot, 120, 510, (byte) 0xF4);

        boot[510] = 0x55;
        boot[511] = (byte) 0xAA;

        System.arraycopy(boot, 0, region, 0, SECTOR);

        // sectors 1-8: extended boot sectors
        for (int s = 1; s <= 8; s++) {

            putLe32(region, s * SECTOR + 508, 0xAA550000L);
        }

        // sector 9: OEM parameters, 10: reserved (zero); 11: checksum
        long checksum = bootChecksum(region);

        for (int i = 0; i < SECTOR; i += 4) {
            putLe32(region, 11 * SECTOR + i, checksum);
        }

        byte[] both = new byte[SECTOR * 24];

        System.arraycopy(region, 0, both, 0, region.length);
        System.arraycopy(region, 0, both, region.length, region.length);

        sink.write(0, both);

        // ---- FAT ----
        int fatEntries = rootFirst + 1;

        byte[] fat = new byte[((fatEntries + 1) * 4 + SECTOR - 1) / SECTOR * SECTOR];

        putLe32(fat, 0, 0xFFFFFFF8L);
        putLe32(fat, 4, 0xFFFFFFFFL);

        chain(fat, bitmapFirst, bitmapClusters);
        chain(fat, upcaseFirst, upcaseClusters);
        chain(fat, rootFirst, 1);

        sink.write((long) FAT_OFFSET_SECTORS * SECTOR, fat);

        // ---- allocation bitmap ----
        byte[] bitmap = new byte[(int) bitmapBytes];

        for (long c = 0; c < usedClusters; c++) {
            bitmap[(int) (c / 8)] |= (byte) (1 << (c % 8));
        }

        sink.write(clusterOffset(heapOffset, clusterBytes, bitmapFirst), bitmap);

        // ---- up-case table ----
        sink.write(clusterOffset(heapOffset, clusterBytes, upcaseFirst), upcase);

        // ---- root directory ----
        byte[] root = new byte[clusterBytes];

        int at = 0;

        root[at] = (byte) 0x83;                     // volume label
        root[at + 1] = (byte) label.length();

        for (int i = 0; i < label.length(); i++) {

            root[at + 2 + i * 2] = (byte) (label.charAt(i) & 0xFF);
            root[at + 3 + i * 2] = (byte) (label.charAt(i) >> 8);
        }

        at += 32;

        root[at] = (byte) 0x81;                     // allocation bitmap
        putLe32(root, at + 20, bitmapFirst);
        putLe64(root, at + 24, bitmapBytes);

        at += 32;

        root[at] = (byte) 0x82;                     // up-case table
        putLe32(root, at + 4, tableChecksum(upcase));
        putLe32(root, at + 20, upcaseFirst);
        putLe64(root, at + 24, upcase.length);

        sink.write(clusterOffset(heapOffset, clusterBytes, rootFirst), root);
    }

    /** Microsoft's recommended cluster sizes. */
    static int clusterSectors(long volumeSectors) {

        long bytes = volumeSectors * SECTOR;

        if (bytes <= 256L * 1024 * 1024) {
            return 8;           // 4 KiB
        }

        if (bytes <= 32L * 1024 * 1024 * 1024) {
            return 64;          // 32 KiB
        }

        return 256;             // 128 KiB
    }

    private static long clusterOffset(long heapOffset, int clusterBytes, long cluster) {

        return heapOffset * SECTOR + (cluster - 2) * (long) clusterBytes;
    }

    private static void chain(byte[] fat, int first, int count) {

        for (int i = 0; i < count; i++) {

            long next = i == count - 1 ? 0xFFFFFFFFL : first + i + 1;

            putLe32(fat, (first + i) * 4, next);
        }
    }

    /** exFAT boot checksum over sectors 0-10, skipping the volatile fields. */
    static long bootChecksum(byte[] region) {

        long checksum = 0;

        for (int i = 0; i < SECTOR * 11; i++) {

            if (i == 106 || i == 107 || i == 112) {
                continue;
            }

            checksum =
                (((checksum & 1) != 0 ? 0x80000000L : 0) +
                 (checksum >> 1) +
                 (region[i] & 0xFF)) & 0xFFFFFFFFL;
        }

        return checksum;
    }

    static long tableChecksum(byte[] table) {

        long checksum = 0;

        for (byte b : table) {

            checksum =
                (((checksum & 1) != 0 ? 0x80000000L : 0) +
                 (checksum >> 1) +
                 (b & 0xFF)) & 0xFFFFFFFFL;
        }

        return checksum;
    }

    /**
     * Case-folding table in the compressed form of the exFAT specification:
     * a run of unchanged characters is written as 0xFFFF followed by the
     * number of characters to skip.
     */
    static byte[] buildUpcase() {

        int[] map = new int[UPCASE_ENTRIES];

        for (int c = 0; c < UPCASE_ENTRIES; c++) {

            map[c] = c;

            if (c >= 0xD800 && c <= 0xDFFF) {
                continue;                           // surrogates stay as they are
            }

            int upper = Character.toUpperCase(c);

            if (upper != c && upper < UPCASE_ENTRIES && upper != 0xFFFF) {

                // one-to-one BMP mappings only
                if (Character.toLowerCase(upper) == c || c < 0x250) {
                    map[c] = upper;
                }
            }
        }

        // The table must keep the standard ASCII a-z -> A-Z mapping.
        for (int c = 'a'; c <= 'z'; c++) {
            map[c] = c - 32;
        }

        // Trailing identity run is omitted: the table covers characters up to
        // the last one that changes, the rest map to themselves.
        int last = UPCASE_ENTRIES - 1;

        while (last > 0 && map[last] == last) {
            last--;
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        int c = 0;

        while (c <= last) {

            if (map[c] != c) {

                out.write(map[c] & 0xFF);
                out.write(map[c] >> 8);
                c++;
                continue;
            }

            int run = 0;

            while (c + run <= last && map[c + run] == c + run) {
                run++;
            }

            if (run >= 3) {

                out.write(0xFF);
                out.write(0xFF);
                out.write(run & 0xFF);
                out.write(run >> 8);

            } else {

                for (int i = 0; i < run; i++) {

                    out.write((c + i) & 0xFF);
                    out.write((c + i) >> 8);
                }
            }

            c += run;
        }

        return out.toByteArray();
    }

    private static void putLe32(byte[] b, int o, long v) {

        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static void putLe64(byte[] b, int o, long v) {

        putLe32(b, o, v & 0xFFFFFFFFL);
        putLe32(b, o + 4, (v >>> 32) & 0xFFFFFFFFL);
    }
}
