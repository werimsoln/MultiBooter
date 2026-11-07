/******************************************************************************
 * ExFatReader.java
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only exFAT analysis: volume label, size, free space (from the
 * allocation bitmap) and a scan for boot images (ISO, IMG, WIM, VHD ...).
 *
 * Works on any exFAT volume, whoever formatted it: contiguous files
 * (NoFatChain) and FAT-chained files, long names, directories that span
 * several clusters. Nothing is ever written.
 *
 * The caller supplies a byte reader positioned at the start of the volume,
 * so the same code works for a partition on a USB drive and for a disk image.
 */
public final class ExFatReader {

    /** Reads length bytes starting at byte offset (relative to the volume). */
    public interface Bytes {

        byte[] read(long offset, int length) throws IOException;
    }

    public interface Control {

        boolean cancelled();

        /** Called now and then so the UI can show that work is going on. */
        void onProgress(String what);
    }

    /** A boot image found on the volume. */
    public static final class Found {

        public final String path;
        public final long size;

        Found(String path, long size) {
            this.path = path;
            this.size = size;
        }
    }

    public static final class Info {

        public boolean valid;
        public String error = "";

        public String label = "";
        public long volumeBytes;
        public long clusterBytes;
        public long clusterCount;

        /** -1 if the bitmap could not be read. */
        public long freeClusters = -1;

        public long freeBytes() {

            return freeClusters < 0 ? -1 : freeClusters * clusterBytes;
        }

        public long usedBytes() {

            return freeClusters < 0
                ? -1
                : (clusterCount - freeClusters) * clusterBytes;
        }

        public final List<Found> images = new ArrayList<Found>();

        public long filesSeen;
        public long directoriesSeen;

        /** The scan stopped early (limit reached or cancelled). */
        public boolean truncated;

        public boolean cancelled;
    }

    public static final class Options {

        /** Directory levels below the root that are scanned. */
        public int maxDepth = 8;

        /** Stop after this many directory entries have been looked at. */
        public int maxEntries = 100000;

        public boolean scanImages = true;

        /** Lower-case extensions that count as boot images. */
        public String[] extensions = {
            ".iso", ".img", ".wim", ".vhd", ".vhdx", ".vtoy", ".efi"
        };
    }

    private static final long EOC = 0xFFFFFFF8L;
    private static final int ENTRY = 32;
    private static final int CHUNK = 64 * 1024;

    private final Bytes device;
    private final Control control;
    private final Options options;

    private int bytesPerSector;
    private long fatOffset;
    private long fatLength;
    private long clusterHeapOffset;
    private long clusterCount;
    private long rootCluster;
    private int clusterBytes;

    private final Map<Long, byte[]> fatCache = new HashMap<Long, byte[]>();

    private int entriesSeen;

    private ExFatReader(Bytes device, Options options, Control control) {

        this.device = device;
        this.options = options;
        this.control = control;
    }

    // ---------------------------------------------------------------------

    public static Info analyze(
        Bytes device,
        Options options,
        Control control
    ) {

        Info info = new Info();

        ExFatReader reader = new ExFatReader(device, options, control);

        try {

            reader.run(info);

        } catch (IOException e) {

            info.error = "Read error: " + e.getMessage();

        } catch (RuntimeException e) {

            info.error = "Unexpected data on the volume.";
        }

        return info;
    }

    /** true if the first sector looks like an exFAT boot sector. */
    public static boolean looksLikeExFat(byte[] bootSector) {

        return
            bootSector != null &&
            bootSector.length >= 512 &&
            bootSector[3] == 'E' &&
            bootSector[4] == 'X' &&
            bootSector[5] == 'F' &&
            bootSector[6] == 'A' &&
            bootSector[7] == 'T' &&
            (bootSector[510] & 0xFF) == 0x55 &&
            (bootSector[511] & 0xFF) == 0xAA;
    }

    private void run(Info info) throws IOException {

        byte[] boot = device.read(0, 512);

        if (!looksLikeExFat(boot)) {

            info.error = "Not an exFAT volume.";
            return;
        }

        int sectorShift = boot[108] & 0xFF;
        int clusterShift = boot[109] & 0xFF;

        if (
            sectorShift < 9 || sectorShift > 12 ||
            clusterShift > 25 - sectorShift
        ) {

            info.error = "Invalid exFAT geometry.";
            return;
        }

        bytesPerSector = 1 << sectorShift;
        clusterBytes = bytesPerSector << clusterShift;

        long volumeLength = le64(boot, 72);

        fatOffset = le32(boot, 80);
        fatLength = le32(boot, 84);
        clusterHeapOffset = le32(boot, 88);
        clusterCount = le32(boot, 92);
        rootCluster = le32(boot, 96);

        if (
            clusterCount == 0 ||
            rootCluster < 2 ||
            rootCluster >= clusterCount + 2
        ) {

            info.error = "Invalid exFAT geometry.";
            return;
        }

        info.valid = true;
        info.volumeBytes = volumeLength * bytesPerSector;
        info.clusterBytes = clusterBytes;
        info.clusterCount = clusterCount;

        // One pass over the root directory finds label and bitmap; the same
        // pass lists the files of the root.
        RootFacts facts = new RootFacts();

        scanDirectory(
            rootCluster,
            false,
            -1,
            "",
            0,
            info,
            facts
        );

        info.label = facts.label;

        if (facts.bitmapCluster >= 2) {

            info.freeClusters =
                countFree(facts.bitmapCluster, facts.bitmapBytes);
        }
    }

    private static final class RootFacts {

        String label = "";
        long bitmapCluster = -1;
        long bitmapBytes;
    }

    // ---------------------------------------------------------------------
    // Free space
    // ---------------------------------------------------------------------

    private long countFree(long firstCluster, long bitmapBytes)
        throws IOException {

        long neededBytes = (clusterCount + 7) / 8;

        long length = Math.min(bitmapBytes, neededBytes);

        ClusterStream stream =
            new ClusterStream(firstCluster, false, length);

        long used = 0;
        long bit = 0;

        byte[] chunk = new byte[CHUNK];

        long remaining = length;

        while (remaining > 0) {

            int want = (int) Math.min(chunk.length, remaining);

            int got = stream.read(chunk, want);

            if (got <= 0) {
                return -1;
            }

            for (int i = 0; i < got; i++) {

                int value = chunk[i] & 0xFF;

                for (int b = 0; b < 8 && bit < clusterCount; b++, bit++) {

                    if ((value & (1 << b)) != 0) {
                        used++;
                    }
                }
            }

            remaining -= got;

            if (control != null && control.cancelled()) {
                return -1;
            }
        }

        return clusterCount - used;
    }

    // ---------------------------------------------------------------------
    // Directories
    // ---------------------------------------------------------------------

    private void scanDirectory(
        long cluster,
        boolean noFatChain,
        long dataLength,
        String path,
        int depth,
        Info info,
        RootFacts facts
    ) throws IOException {

        ClusterStream stream =
            new ClusterStream(cluster, noFatChain, dataLength);

        byte[] entry = new byte[ENTRY];

        while (true) {

            if (control != null && control.cancelled()) {

                info.cancelled = true;
                info.truncated = true;
                return;
            }

            if (entriesSeen >= options.maxEntries) {

                info.truncated = true;
                return;
            }

            if (stream.readFully(entry) < ENTRY) {
                return;
            }

            entriesSeen++;

            int type = entry[0] & 0xFF;

            if (type == 0x00) {
                return;                                 // end of directory
            }

            if (type == 0x83 && facts != null) {

                int chars = Math.min(entry[1] & 0xFF, 11);

                facts.label = utf16(entry, 2, chars);

            } else if (type == 0x81 && facts != null) {

                facts.bitmapCluster = le32(entry, 20);
                facts.bitmapBytes = le64(entry, 24);

            } else if (type == 0x85) {

                if (!readFileSet(entry, stream, path, depth, info)) {
                    return;
                }
            }
        }
    }

    /**
     * Reads the secondary entries of one file entry set and handles the
     * file or directory. Returns false if the directory can not be read on.
     */
    private boolean readFileSet(
        byte[] primary,
        ClusterStream stream,
        String path,
        int depth,
        Info info
    ) throws IOException {

        int secondaries = primary[1] & 0xFF;

        int attributes = le16(primary, 4);

        boolean directory = (attributes & 0x10) != 0;

        boolean haveStream = false;
        boolean noFatChain = false;
        int nameLength = 0;
        long first = 0;
        long size = 0;

        StringBuilder name = new StringBuilder();

        byte[] entry = new byte[ENTRY];

        for (int i = 0; i < secondaries; i++) {

            if (stream.readFully(entry) < ENTRY) {
                return false;
            }

            entriesSeen++;

            int type = entry[0] & 0xFF;

            if (type == 0xC0) {

                haveStream = true;
                noFatChain = (entry[1] & 0x02) != 0;
                nameLength = entry[3] & 0xFF;
                size = le64(entry, 24);
                first = le32(entry, 20);

            } else if (type == 0xC1) {

                int take = Math.min(15, nameLength - name.length());

                if (take > 0) {
                    name.append(utf16(entry, 2, take));
                }
            }
        }

        if (!haveStream) {
            return true;
        }

        String full = path + "/" + name;

        if (directory) {

            info.directoriesSeen++;

            if (depth + 1 > options.maxDepth) {

                info.truncated = true;
                return true;
            }

            if (first >= 2 && first < clusterCount + 2) {

                if (control != null) {
                    control.onProgress(full);
                }

                scanDirectory(
                    first,
                    noFatChain,
                    size,
                    full,
                    depth + 1,
                    info,
                    null
                );
            }

            return !info.cancelled;
        }

        info.filesSeen++;

        if (options.scanImages && hasImageExtension(name.toString())) {

            info.images.add(new Found(full, size));
        }

        return true;
    }

    private boolean hasImageExtension(String name) {

        String lower = name.toLowerCase(Locale.ROOT);

        for (String extension : options.extensions) {

            if (lower.endsWith(extension)) {
                return true;
            }
        }

        return false;
    }

    // ---------------------------------------------------------------------
    // Clusters and the FAT
    // ---------------------------------------------------------------------

    /** Sequential reader over a cluster chain. */
    private final class ClusterStream {

        private long cluster;
        private final boolean noFatChain;
        private long remaining;       // bytes left, or Long.MAX_VALUE
        private int offsetInCluster;
        private long guard;

        private byte[] buffer = new byte[0];
        private int bufferPos;

        ClusterStream(long firstCluster, boolean noFatChain, long length) {

            this.cluster = firstCluster;
            this.noFatChain = noFatChain;
            this.remaining = length < 0 ? Long.MAX_VALUE : length;
        }

        int readFully(byte[] out) throws IOException {

            int done = 0;

            while (done < out.length) {

                if (bufferPos >= buffer.length && !fill()) {
                    break;
                }

                int take = Math.min(out.length - done, buffer.length - bufferPos);

                System.arraycopy(buffer, bufferPos, out, done, take);

                bufferPos += take;
                done += take;
            }

            return done;
        }

        int read(byte[] out, int length) throws IOException {

            int done = 0;

            while (done < length) {

                if (bufferPos >= buffer.length && !fill()) {
                    break;
                }

                int take = Math.min(length - done, buffer.length - bufferPos);

                System.arraycopy(buffer, bufferPos, out, done, take);

                bufferPos += take;
                done += take;
            }

            return done;
        }

        private boolean fill() throws IOException {

            if (
                remaining <= 0 ||
                cluster < 2 ||
                cluster >= clusterCount + 2 ||
                guard++ > clusterCount + 8
            ) {
                return false;
            }

            long clusterStart =
                clusterHeapOffset * bytesPerSector +
                (cluster - 2) * (long) clusterBytes;

            int want =
                (int) Math.min(
                    (long) Math.min(CHUNK, clusterBytes - offsetInCluster),
                    remaining
                );

            buffer = device.read(clusterStart + offsetInCluster, want);
            bufferPos = 0;

            remaining -= want;
            offsetInCluster += want;

            if (offsetInCluster >= clusterBytes) {

                offsetInCluster = 0;

                if (remaining > 0) {

                    cluster =
                        noFatChain
                        ? cluster + 1
                        : nextCluster(cluster);
                }
            }

            return buffer.length > 0;
        }
    }

    private long nextCluster(long cluster) throws IOException {

        long position = fatOffset * bytesPerSector + cluster * 4;

        long blockStart = position - position % CHUNK;

        byte[] block = fatCache.get(blockStart);

        if (block == null) {

            long fatEnd =
                (fatOffset + fatLength) * bytesPerSector;

            int length = (int) Math.min(CHUNK, fatEnd - blockStart);

            if (length < 4) {
                return 0;
            }

            block = device.read(blockStart, length);

            if (fatCache.size() > 64) {
                fatCache.clear();
            }

            fatCache.put(blockStart, block);
        }

        int at = (int) (position - blockStart);

        if (at + 4 > block.length) {
            return 0;
        }

        long next = le32(block, at);

        return next >= EOC ? 0 : next;
    }

    // ---------------------------------------------------------------------

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

    private static String utf16(byte[] b, int offset, int chars) {

        StringBuilder text = new StringBuilder();

        for (int i = 0; i < chars; i++) {
            text.append((char) le16(b, offset + i * 2));
        }

        return text.toString();
    }
}
