/******************************************************************************
 * UsbVerifier.java
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
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Read-back verification of data written to a USB drive.
 *
 * While the data is written, a Recorder keeps a SHA-256 of every 1 MiB block
 * (and of the whole stream in Full mode). Afterwards verify() reads the same
 * range back from the drive and compares. If a block differs, the exact first
 * differing byte is found by comparing the drive with the source again, so the
 * report can name the sector (LBA) and the byte inside it.
 *
 * Pure Java: it only needs a BlockReader and a way to reopen the source.
 */
public final class UsbVerifier {

    public static final int BLOCK = 1024 * 1024;

    /** Number of 1 MiB blocks checked at the start and at the end (Partial). */
    private static final int PARTIAL_EDGE_BLOCKS = 64;

    /** Evenly spread samples between the edges (Partial). */
    private static final int PARTIAL_SAMPLES = 32;

    public enum Mode {
        FULL,
        PARTIAL
    }

    /** Reads sectors from the drive. */
    public interface BlockReader {

        void read(long lba, byte[] out, int sectors) throws IOException;
    }

    /** Opens the source again, positioned at its start. */
    public interface SourceOpener {

        InputStream open() throws IOException;
    }

    public interface Progress {

        void onProgress(long done, long total);

        boolean cancelled();
    }

    public static final class Result {

        public final boolean ok;
        public final boolean cancelled;
        public final String message;

        /** Byte offset of the first difference, or -1. */
        public final long failOffset;

        /** LBA of the sector that holds failOffset, or -1. */
        public final long failLba;

        /** Position of failOffset inside its sector, or -1. */
        public final int failByteInSector;

        /** Hex SHA-256 of the whole image (Full mode only), else "". */
        public final String sha256;

        Result(
            boolean ok,
            boolean cancelled,
            String message,
            long failOffset,
            long failLba,
            int failByteInSector,
            String sha256
        ) {
            this.ok = ok;
            this.cancelled = cancelled;
            this.message = message;
            this.failOffset = failOffset;
            this.failLba = failLba;
            this.failByteInSector = failByteInSector;
            this.sha256 = sha256;
        }
    }

    /** Collects the digests while the data is being written. */
    public static final class Recorder {

        private final long totalBytes;
        private final Mode mode;
        private final boolean[] selected;
        private final TreeMap<Integer, byte[]> blockDigests =
            new TreeMap<Integer, byte[]>();

        private final MessageDigest total;
        private MessageDigest block;

        private long position = 0;
        private int blockIndex = 0;

        public Recorder(long totalBytes, Mode mode) {

            this.totalBytes = totalBytes;
            this.mode = mode;
            this.selected = select(totalBytes, mode);
            this.total = mode == Mode.FULL ? sha256() : null;
            this.block = null;

            startBlock(0);
        }

        /** Feed the bytes in the order they are written. */
        public void update(byte[] data, int offset, int length) {

            int end = offset + length;

            while (offset < end) {

                int room = BLOCK - (int) (position % BLOCK);
                int take = Math.min(room, end - offset);

                if (total != null) {
                    total.update(data, offset, take);
                }

                if (block != null) {
                    block.update(data, offset, take);
                }

                position += take;
                offset += take;

                if (position % BLOCK == 0 || position == totalBytes) {

                    if (block != null) {
                        blockDigests.put(blockIndex, block.digest());
                    }

                    blockIndex++;

                    startBlock(blockIndex);
                }
            }
        }

                public Mode mode() {

            return mode;
        }

        public long recordedBytes() {

            return position;
        }

        private void startBlock(int index) {

            block =
                index < selected.length && selected[index]
                ? sha256()
                : null;
        }
    }

    private UsbVerifier() {
    }

    // ---------------------------------------------------------------------

    /** Which 1 MiB blocks are checked in the given mode. */
    static boolean[] select(long totalBytes, Mode mode) {

        int blocks = (int) ((totalBytes + BLOCK - 1) / BLOCK);

        boolean[] selected = new boolean[blocks];

        if (mode == Mode.FULL || blocks <= 2 * PARTIAL_EDGE_BLOCKS + PARTIAL_SAMPLES) {

            Arrays.fill(selected, true);

            return selected;
        }

        for (int i = 0; i < PARTIAL_EDGE_BLOCKS; i++) {
            selected[i] = true;
            selected[blocks - 1 - i] = true;
        }

        int first = PARTIAL_EDGE_BLOCKS;
        int last = blocks - 1 - PARTIAL_EDGE_BLOCKS;

        for (int i = 0; i < PARTIAL_SAMPLES; i++) {

            selected[first + (int) (((long) (last - first) * i) / PARTIAL_SAMPLES)] =
                true;
        }

        return selected;
    }

    /** Bytes that verify() will read for a recorder (for the progress bar). */
    public static long bytesToVerify(long totalBytes, Mode mode) {

        boolean[] selected = select(totalBytes, mode);

        long bytes = 0;

        for (int i = 0; i < selected.length; i++) {

            if (selected[i]) {

                bytes += Math.min(BLOCK, totalBytes - (long) i * BLOCK);
            }
        }

        return bytes;
    }

    public static Result verify(
        Recorder recorder,
        BlockReader reader,
        int sectorSize,
        SourceOpener source,
        Progress progress
    ) {

        long totalBytes = recorder.totalBytes;

        if (recorder.recordedBytes() != totalBytes) {

            return failed("Internal error: not all data was recorded.");
        }

        boolean full = recorder.mode == Mode.FULL;

        MessageDigest readTotal = full ? sha256() : null;

        long toVerify = bytesToVerify(totalBytes, recorder.mode);
        long done = 0;

        byte[] buffer = new byte[BLOCK];

        try {

            for (int i = 0; i < recorder.selected.length; i++) {

                if (!recorder.selected[i]) {
                    continue;
                }

                if (progress != null && progress.cancelled()) {

                    return new Result(
                        false, true, "Verification was stopped.", -1, -1, -1, ""
                    );
                }

                long offset = (long) i * BLOCK;
                int length = (int) Math.min(BLOCK, totalBytes - offset);

                readRange(reader, sectorSize, offset, length, buffer);

                MessageDigest digest = sha256();

                digest.update(buffer, 0, length);

                if (readTotal != null) {
                    readTotal.update(buffer, 0, length);
                }

                byte[] expected = recorder.blockDigests.get(i);

                if (expected == null || !MessageDigest.isEqual(expected, digest.digest())) {

                    return locate(
                        reader,
                        sectorSize,
                        source,
                        offset,
                        length,
                        buffer
                    );
                }

                done += length;

                if (progress != null) {
                    progress.onProgress(done, toVerify);
                }
            }

        } catch (IOException e) {

            return failed(
                "The drive could not be read back: " + e.getMessage()
            );
        }

        String sha = "";

        if (readTotal != null) {

            byte[] expected = recorder.total.digest();
            byte[] actual = readTotal.digest();

            if (!MessageDigest.isEqual(expected, actual)) {

                return failed(
                    "The block checks passed but the whole-image SHA-256 differs."
                );
            }

            sha = hex(actual);
        }

        return new Result(
            true,
            false,
            full
                ? "All " + totalBytes + " bytes match (SHA-256 " + sha + ")."
                : "Start, end and spread-out samples match.",
            -1,
            -1,
            -1,
            sha
        );
    }

    // ---------------------------------------------------------------------

    /**
     * The block at blockOffset differs. Find the first differing byte by
     * comparing the drive with the source.
     */
    private static Result locate(
        BlockReader reader,
        int sectorSize,
        SourceOpener source,
        long blockOffset,
        int length,
        byte[] driveData
    ) {

        byte[] expected = new byte[length];

        try {

            InputStream in = source.open();

            try {

                skipFully(in, blockOffset);
                readFully(in, expected, length);

            } finally {

                in.close();
            }

        } catch (IOException e) {

            return failed(
                "The data around byte " + blockOffset +
                " does not match, but the source could not be read again to " +
                "find the exact position."
            );
        }

        for (int i = 0; i < length; i++) {

            if (driveData[i] != expected[i]) {

                long offset = blockOffset + i;

                // Read the sector once more to tell a bad write from a
                // flaky read.
                boolean repeatable = true;

                try {

                    byte[] again = new byte[BLOCK];

                    readRange(reader, sectorSize, blockOffset, length, again);

                    repeatable = again[i] != expected[i];

                } catch (IOException ignored) {
                }

                long lba = offset / sectorSize;
                int inSector = (int) (offset % sectorSize);

                return new Result(
                    false,
                    false,
                    "Verification failed at byte offset " + offset +
                    " (LBA " + lba + ", byte " + inSector + " of the sector). " +
                    (
                        repeatable
                        ? "The drive holds different data than was written."
                        : "A second read returned the correct data, so the " +
                          "drive or cable is unreliable."
                    ),
                    offset,
                    lba,
                    inSector,
                    ""
                );
            }
        }

        return failed(
            "The block digest differed but a byte comparison found no " +
            "difference. Try again."
        );
    }

    private static void readRange(
        BlockReader reader,
        int sectorSize,
        long offset,
        int length,
        byte[] out
    ) throws IOException {

        long firstLba = offset / sectorSize;
        int sectors = (length + sectorSize - 1) / sectorSize;

        byte[] raw =
            sectors * sectorSize == length
            ? out
            : new byte[sectors * sectorSize];

        int done = 0;

        while (done < sectors) {

            int count = Math.min(sectors - done, 2048);

            byte[] part = new byte[count * sectorSize];

            reader.read(firstLba + done, part, count);

            System.arraycopy(part, 0, raw, done * sectorSize, part.length);

            done += count;
        }

        if (raw != out) {
            System.arraycopy(raw, 0, out, 0, length);
        }
    }

    private static void skipFully(InputStream in, long count) throws IOException {

        long left = count;
        byte[] sink = new byte[64 * 1024];

        while (left > 0) {

            long skipped = in.skip(left);

            if (skipped <= 0) {

                int read = in.read(sink, 0, (int) Math.min(sink.length, left));

                if (read < 0) {
                    throw new IOException("Source ended early.");
                }

                skipped = read;
            }

            left -= skipped;
        }
    }

    private static void readFully(InputStream in, byte[] out, int length)
        throws IOException {

        int done = 0;

        while (done < length) {

            int read = in.read(out, done, length - done);

            if (read < 0) {
                throw new IOException("Source ended early.");
            }

            done += read;
        }
    }

    private static Result failed(String message) {

        return new Result(false, false, message, -1, -1, -1, "");
    }

    private static MessageDigest sha256() {

        try {

            return MessageDigest.getInstance("SHA-256");

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {

        StringBuilder text = new StringBuilder();

        for (byte b : bytes) {
            text.append(String.format("%02x", b));
        }

        return text.toString();
    }
}
