/******************************************************************************
 * VentoyDetector.java
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
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects an existing Ventoy installation on a 512-byte-sector disk and
 * reads its version, using the same method as Ventoy's own installers
 * (Ventoy2Disk.exe and the Linux tool):
 *
 *   1. The disk must have the Ventoy layout: partition 1 starts at LBA 2048,
 *      partition 2 is exactly 65536 sectors (32 MiB) and starts right after
 *      partition 1. In GPT mode partition 2 must be named "VTOYEFI".
 *   2. Partition 2 is a small FAT file system. /grub/grub.cfg inside it
 *      contains a line  set VENTOY_VERSION="x.y.z".
 *
 * Everything is read-only. Any failure is reported as "no Ventoy found" or
 * as an error text; this class never throws for bad disk contents.
 */
final class VentoyDetector {

    /** Reads count 512-byte sectors starting at lba. */
    interface SectorReader {

        byte[] read(long lba, int count) throws IOException;
    }

    static final class Info {

        /** The Ventoy partition layout was found. */
        boolean layoutFound;

        /** true = GPT, false = MBR (only meaningful if layoutFound). */
        boolean gpt;

        /** Version text such as "1.1.17", or "" if it could not be read. */
        String version = "";

        long part1Start;
        long part1Sectors;
        long part2Start;
        long part2Sectors;

        /** Short human readable reason when the layout was not found. */
        String reason = "";

        String describe() {

            if (!layoutFound) {
                return "No Ventoy installation found.";
            }

            return
                "Ventoy " +
                (version.length() == 0 ? "(version unknown)" : version) +
                " is installed (" +
                (gpt ? "GPT" : "MBR") +
                ")";
        }
    }

    static final int SECTOR = 512;

    static final long PART1_START = 2048L;
    static final long VTOYEFI_SECTORS = 65536L;

    private static final int HEADER_SECTORS = 34;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int CACHE_BLOCK_SECTORS = 8;
    private static final int MAX_CACHE_BLOCKS = 256;

    private VentoyDetector() {
    }

    static Info detect(
        SectorReader reader,
        long totalSectors
    ) {

        Info info = new Info();

        try {

            if (totalSectors < 2L * VTOYEFI_SECTORS) {

                info.reason = "Disk is too small.";
                return info;
            }

            byte[] head = reader.read(0, HEADER_SECTORS);

            if (
                head == null ||
                head.length < HEADER_SECTORS * SECTOR
            ) {

                info.reason = "Could not read the partition table.";
                return info;
            }

            if (
                (head[510] & 0xFF) != 0x55 ||
                (head[511] & 0xFF) != 0xAA
            ) {

                info.reason = "No partition table.";
                return info;
            }

            boolean gpt =
                (head[446 + 4] & 0xFF) == 0xEE &&
                matches(head, SECTOR, "EFI PART");

            long p1Start;
            long p1Count;
            long p2Start;
            long p2Count;

            if (gpt) {

                int entries = SECTOR * 2;

                p1Start = le64(head, entries + 32);
                p1Count = le64(head, entries + 40) - p1Start + 1;
                p2Start = le64(head, entries + 128 + 32);
                p2Count = le64(head, entries + 128 + 40) - p2Start + 1;

                if (
                    p1Start == 0 ||
                    p2Start == 0 ||
                    !"VTOYEFI".equals(
                        utf16Name(head, entries + 128 + 56)
                    )
                ) {

                    info.reason = "GPT without the Ventoy partitions.";
                    return info;
                }

            } else {

                p1Start = le32(head, 446 + 8);
                p1Count = le32(head, 446 + 12);
                p2Start = le32(head, 446 + 16 + 8);
                p2Count = le32(head, 446 + 16 + 12);
            }

            info.gpt = gpt;
            info.part1Start = p1Start;
            info.part1Sectors = p1Count;
            info.part2Start = p2Start;
            info.part2Sectors = p2Count;

            if (
                p1Start != PART1_START ||
                p2Count != VTOYEFI_SECTORS ||
                p1Start + p1Count != p2Start ||
                p2Start + p2Count > totalSectors
            ) {

                info.reason = "Partition layout is not a Ventoy layout.";
                return info;
            }

            info.layoutFound = true;

            try {

                info.version = readVersion(reader, p2Start);

            } catch (IOException e) {

                info.reason = "Could not read the Ventoy version.";
            }

            return info;

        } catch (IOException e) {

            info.layoutFound = false;
            info.reason = "USB read error.";
            return info;

        } catch (RuntimeException e) {

            info.layoutFound = false;
            info.reason = "Unexpected data on the disk.";
            return info;
        }
    }

    /**
     * Returns the version text, or "" if the file system or the version line
     * was not found.
     */
    static String readVersion(
        SectorReader reader,
        long part2Start
    ) throws IOException {

        Fat fat = Fat.open(reader, part2Start);

        if (fat == null) {
            return "";
        }

        byte[] cfg = fat.readFile("/grub/grub.cfg", MAX_FILE_BYTES);

        if (cfg == null) {
            return "";
        }

        return parseVersion(cfg);
    }

    /** Same rules as Ventoy's ParseVentoyVersionFromString(). */
    static String parseVersion(byte[] text) {

        byte[] key = "VENTOY_VERSION=".getBytes(Charset.forName("US-ASCII"));
        int at = indexOf(text, key);

        if (at < 0) {
            return "";
        }

        int pos = at + key.length;

        if (pos < text.length && text[pos] == '"') {
            pos++;
        }

        int end = pos;

        while (
            end < text.length &&
            text[end] != '"' &&
            text[end] != '\r' &&
            text[end] != '\n' &&
            end - pos < 64
        ) {
            end++;
        }

        return new String(
            text,
            pos,
            end - pos,
            Charset.forName("US-ASCII")
        ).trim();
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private static int indexOf(byte[] data, byte[] key) {

        outer:
        for (int i = 0; i + key.length <= data.length; i++) {

            for (int j = 0; j < key.length; j++) {

                if (data[i + j] != key[j]) {
                    continue outer;
                }
            }

            return i;
        }

        return -1;
    }

    private static boolean matches(byte[] data, int offset, String text) {

        for (int i = 0; i < text.length(); i++) {

            if (data[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }

        return true;
    }

    private static int le16(byte[] b, int o) {

        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int o) {

        return
            (b[o] & 0xFFL) |
            ((b[o + 1] & 0xFFL) << 8) |
            ((b[o + 2] & 0xFFL) << 16) |
            ((b[o + 3] & 0xFFL) << 24);
    }

    private static long le64(byte[] b, int o) {

        return le32(b, o) | (le32(b, o + 4) << 32);
    }

    private static String utf16Name(byte[] b, int offset) {

        StringBuilder name = new StringBuilder();

        for (int i = 0; i < 36; i++) {

            int c = le16(b, offset + i * 2);

            if (c == 0) {
                break;
            }

            name.append((char) c);
        }

        return name.toString();
    }

    // ---------------------------------------------------------------------
    // Minimal read-only FAT12/16/32 implementation
    // ---------------------------------------------------------------------

    private static final class Fat {

        private final SectorReader reader;
        private final long base;

        private int sectorsPerCluster;
        private int reserved;
        private int fatCount;
        private int rootEntries;
        private long fatSectors;
        private long totalSectors;
        private int fatBits;

        private long fatStart;
        private long rootStart;
        private long rootSectors;
        private long dataStart;
        private long clusterCount;
        private long rootCluster;

        private final Map<Long, byte[]> cache =
            new HashMap<Long, byte[]>();

        private Fat(SectorReader reader, long base) {

            this.reader = reader;
            this.base = base;
        }

        static Fat open(
            SectorReader reader,
            long base
        ) throws IOException {

            Fat fat = new Fat(reader, base);

            byte[] boot = reader.read(base, 1);

            if (
                boot == null ||
                boot.length < SECTOR ||
                (boot[510] & 0xFF) != 0x55 ||
                (boot[511] & 0xFF) != 0xAA ||
                le16(boot, 11) != SECTOR
            ) {
                return null;
            }

            fat.sectorsPerCluster = boot[13] & 0xFF;
            fat.reserved = le16(boot, 14);
            fat.fatCount = boot[16] & 0xFF;
            fat.rootEntries = le16(boot, 17);

            long total16 = le16(boot, 19);
            long fatSize16 = le16(boot, 22);

            fat.totalSectors = total16 != 0 ? total16 : le32(boot, 32);
            fat.fatSectors = fatSize16 != 0 ? fatSize16 : le32(boot, 36);

            if (
                fat.sectorsPerCluster == 0 ||
                (fat.sectorsPerCluster & (fat.sectorsPerCluster - 1)) != 0 ||
                fat.reserved == 0 ||
                fat.fatCount == 0 ||
                fat.fatSectors == 0 ||
                fat.totalSectors == 0
            ) {
                return null;
            }

            fat.rootSectors =
                ((long) fat.rootEntries * 32 + SECTOR - 1) / SECTOR;

            fat.fatStart = fat.reserved;
            fat.rootStart =
                fat.fatStart + (long) fat.fatCount * fat.fatSectors;
            fat.dataStart = fat.rootStart + fat.rootSectors;

            long dataSectors = fat.totalSectors - fat.dataStart;

            if (dataSectors <= 0) {
                return null;
            }

            fat.clusterCount = dataSectors / fat.sectorsPerCluster;

            if (fat.clusterCount < 4085) {
                fat.fatBits = 12;
            } else if (fat.clusterCount < 65525) {
                fat.fatBits = 16;
            } else {
                fat.fatBits = 32;
            }

            if (fat.fatBits == 32) {

                if (fat.rootEntries != 0) {
                    return null;
                }

                fat.rootCluster = le32(boot, 44);

                if (fat.rootCluster < 2) {
                    return null;
                }

            } else if (fat.rootEntries == 0) {

                return null;
            }

            return fat;
        }

        /** Returns the file content, or null if it does not exist. */
        byte[] readFile(
            String path,
            int maxBytes
        ) throws IOException {

            String[] parts = path.split("/");

            long cluster = 0;       // 0 = fixed root directory (FAT12/16)
            boolean rootDir = true;
            Entry entry = null;

            if (fatBits == 32) {

                cluster = rootCluster;
                rootDir = false;
            }

            int count = 0;

            for (String part : parts) {

                if (part.length() == 0) {
                    continue;
                }

                count++;

                entry = findEntry(rootDir, cluster, part);

                if (entry == null) {
                    return null;
                }

                if (entry.directory) {

                    rootDir = false;
                    cluster = entry.cluster;
                    entry = null;
                }
            }

            if (entry == null || count == 0) {
                return null;
            }

            return readChain(entry.cluster, entry.size, maxBytes);
        }

        private static final class Entry {

            boolean directory;
            long cluster;
            long size;
        }

        private Entry findEntry(
            boolean rootDir,
            long cluster,
            String name
        ) throws IOException {

            byte[] dir;

            if (rootDir) {

                dir = readSectors(rootStart, (int) rootSectors);

            } else {

                dir = readChain(cluster, Long.MAX_VALUE, 512 * 1024);
            }

            if (dir == null) {
                return null;
            }

            StringBuilder longName = new StringBuilder();
            int lfnExpected = 0;
            int lfnChecksum = -1;

            for (int off = 0; off + 32 <= dir.length; off += 32) {

                int first = dir[off] & 0xFF;

                if (first == 0x00) {
                    break;
                }

                if (first == 0xE5) {

                    longName.setLength(0);
                    lfnExpected = 0;
                    continue;
                }

                int attr = dir[off + 11] & 0xFF;

                if (attr == 0x0F) {

                    int order = first & 0x3F;

                    if ((first & 0x40) != 0) {

                        longName.setLength(0);
                        lfnExpected = order;
                        lfnChecksum = dir[off + 13] & 0xFF;
                    }

                    if (order == lfnExpected && order > 0) {

                        StringBuilder piece = new StringBuilder();
                        int[] offsets = {
                            1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30
                        };

                        for (int o : offsets) {

                            int c = le16(dir, off + o);

                            if (c == 0 || c == 0xFFFF) {
                                break;
                            }

                            piece.append((char) c);
                        }

                        longName.insert(0, piece);
                        lfnExpected--;
                    }

                    continue;
                }

                if ((attr & 0x08) != 0) {

                    // volume label
                    longName.setLength(0);
                    lfnExpected = 0;
                    continue;
                }

                String shortName = shortName(dir, off);
                String candidate = shortName;

                if (
                    lfnExpected == 0 &&
                    longName.length() > 0 &&
                    lfnChecksum == checksum(dir, off)
                ) {
                    candidate = longName.toString();
                }

                boolean hit =
                    candidate.equalsIgnoreCase(name) ||
                    shortName.equalsIgnoreCase(name);

                longName.setLength(0);
                lfnExpected = 0;

                if (!hit) {
                    continue;
                }

                Entry entry = new Entry();

                entry.directory = (attr & 0x10) != 0;
                entry.size = le32(dir, off + 28);
                entry.cluster =
                    le16(dir, off + 26) |
                    ((long) le16(dir, off + 20) << 16);

                if (fatBits != 32) {
                    entry.cluster &= 0xFFFFL;
                }

                return entry;
            }

            return null;
        }

        private static String shortName(byte[] dir, int off) {

            StringBuilder name = new StringBuilder();

            for (int i = 0; i < 8; i++) {

                char c = (char) (dir[off + i] & 0xFF);

                if (i == 0 && (dir[off] & 0xFF) == 0x05) {
                    c = (char) 0xE5;
                }

                if (c == ' ') {
                    break;
                }

                name.append(c);
            }

            StringBuilder ext = new StringBuilder();

            for (int i = 8; i < 11; i++) {

                char c = (char) (dir[off + i] & 0xFF);

                if (c == ' ') {
                    break;
                }

                ext.append(c);
            }

            if (ext.length() > 0) {
                name.append('.').append(ext);
            }

            return name.toString();
        }

        private static int checksum(byte[] dir, int off) {

            int sum = 0;

            for (int i = 0; i < 11; i++) {

                sum = (((sum & 1) << 7) | ((sum & 0xFF) >> 1))
                    + (dir[off + i] & 0xFF);
                sum &= 0xFF;
            }

            return sum;
        }

        /**
         * Reads a cluster chain. size is the file size (or Long.MAX_VALUE
         * for directories); at most maxBytes are returned.
         */
        private byte[] readChain(
            long cluster,
            long size,
            int maxBytes
        ) throws IOException {

            if (cluster < 2 || cluster >= clusterCount + 2) {
                return size == 0 ? new byte[0] : null;
            }

            long want = Math.min(size, (long) maxBytes);
            int clusterBytes = sectorsPerCluster * SECTOR;

            java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream();

            long guard = 0;

            while (
                cluster >= 2 &&
                cluster < clusterCount + 2 &&
                out.size() < want &&
                guard++ <= clusterCount
            ) {

                byte[] data = readSectors(
                    dataStart + (cluster - 2) * sectorsPerCluster,
                    sectorsPerCluster
                );

                if (data == null) {
                    return null;
                }

                int take = (int) Math.min(clusterBytes, want - out.size());
                out.write(data, 0, take);

                cluster = nextCluster(cluster);
            }

            return out.toByteArray();
        }

        private long nextCluster(long cluster) throws IOException {

            long offset;

            switch (fatBits) {

                case 12:
                    offset = cluster + cluster / 2;
                    break;

                case 16:
                    offset = cluster * 2;
                    break;

                default:
                    offset = cluster * 4;
                    break;
            }

            long sector = fatStart + offset / SECTOR;
            int inSector = (int) (offset % SECTOR);

            byte[] a = readSectors(sector, 1);

            if (a == null) {
                return -1;
            }

            long value;

            if (fatBits == 12) {

                int lo = a[inSector] & 0xFF;
                int hi;

                if (inSector == SECTOR - 1) {

                    byte[] b = readSectors(sector + 1, 1);

                    if (b == null) {
                        return -1;
                    }

                    hi = b[0] & 0xFF;

                } else {

                    hi = a[inSector + 1] & 0xFF;
                }

                value = lo | (hi << 8);

                value = (cluster & 1) != 0 ? (value >> 4) : (value & 0x0FFF);

                return value >= 0x0FF8 ? -1 : value;
            }

            if (fatBits == 16) {

                value = le16(a, inSector);

                return value >= 0xFFF8 ? -1 : value;
            }

            value = le32(a, inSector) & 0x0FFFFFFFL;

            return value >= 0x0FFFFFF8L ? -1 : value;
        }

        /** Reads sectors relative to the start of the file system. */
        private byte[] readSectors(
            long sector,
            int count
        ) throws IOException {

            byte[] out = new byte[count * SECTOR];

            for (int i = 0; i < count; ) {

                long current = sector + i;
                long block = current / CACHE_BLOCK_SECTORS;

                byte[] data = cache.get(block);

                if (data == null) {

                    long blockStart = block * CACHE_BLOCK_SECTORS;
                    long available = totalSectors - blockStart;

                    int blockCount =
                        (int) Math.min(CACHE_BLOCK_SECTORS, available);

                    if (blockCount <= 0) {
                        return null;
                    }

                    data = reader.read(base + blockStart, blockCount);

                    if (
                        data == null ||
                        data.length < blockCount * SECTOR
                    ) {
                        return null;
                    }

                    if (cache.size() >= MAX_CACHE_BLOCKS) {
                        cache.clear();
                    }

                    cache.put(block, data);
                }

                int within = (int) (current - block * CACHE_BLOCK_SECTORS);
                int fits = data.length / SECTOR - within;

                if (fits <= 0) {
                    return null;
                }

                int copy = Math.min(fits, count - i);

                System.arraycopy(
                    data,
                    within * SECTOR,
                    out,
                    i * SECTOR,
                    copy * SECTOR
                );

                i += copy;
            }

            return out;
        }
    }
}
