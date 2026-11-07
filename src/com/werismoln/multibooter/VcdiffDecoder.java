/******************************************************************************
 * VcdiffDecoder.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Adler32;

/**
 * Decoder for VCDIFF (RFC 3284) deltas as written by "xdelta3 -e -S none".
 *
 * It exists so that older Ventoy images can be shipped as small patches
 * against the newest image instead of as full 32 MiB copies. It supports the
 * default code table and address caches, several windows, an optional
 * application header and the Adler-32 check of xdelta3. Secondary compression
 * and custom code tables are not supported (the build must use -S none).
 */
public final class VcdiffDecoder {

    private static final int NEAR_SIZE = 4;
    private static final int SAME_SIZE = 3;

    private static final int T_NOOP = 0;
    private static final int T_ADD = 1;
    private static final int T_RUN = 2;
    private static final int T_COPY = 3;

    // Default code table (RFC 3284, section 5.6).
    private static final int[] TYPE1 = new int[256];
    private static final int[] SIZE1 = new int[256];
    private static final int[] MODE1 = new int[256];
    private static final int[] TYPE2 = new int[256];
    private static final int[] SIZE2 = new int[256];
    private static final int[] MODE2 = new int[256];

    static {

        int i = 0;

        TYPE1[i] = T_RUN;
        i++;

        for (int size = 0; size <= 17; size++) {
            TYPE1[i] = T_ADD;
            SIZE1[i] = size;
            i++;
        }

        for (int mode = 0; mode <= 8; mode++) {

            TYPE1[i] = T_COPY;
            MODE1[i] = mode;
            i++;

            for (int size = 4; size <= 18; size++) {
                TYPE1[i] = T_COPY;
                SIZE1[i] = size;
                MODE1[i] = mode;
                i++;
            }
        }

        for (int mode = 0; mode <= 5; mode++) {

            for (int add = 1; add <= 4; add++) {

                for (int copy = 4; copy <= 6; copy++) {

                    TYPE1[i] = T_ADD;
                    SIZE1[i] = add;
                    TYPE2[i] = T_COPY;
                    SIZE2[i] = copy;
                    MODE2[i] = mode;
                    i++;
                }
            }
        }

        for (int mode = 6; mode <= 8; mode++) {

            for (int add = 1; add <= 4; add++) {

                TYPE1[i] = T_ADD;
                SIZE1[i] = add;
                TYPE2[i] = T_COPY;
                SIZE2[i] = 4;
                MODE2[i] = mode;
                i++;
            }
        }

        for (int mode = 0; mode <= 8; mode++) {

            TYPE1[i] = T_COPY;
            SIZE1[i] = 4;
            MODE1[i] = mode;
            TYPE2[i] = T_ADD;
            SIZE2[i] = 1;
            i++;
        }
    }

    private VcdiffDecoder() {
    }

    /** Applies delta to source and returns the target. */
    public static byte[] decode(byte[] source, byte[] delta)
        throws IOException {

        Reader in = new Reader(delta, 0, delta.length);

        if (
            in.remaining() < 5 ||
            in.u8() != 0xD6 ||
            in.u8() != 0xC3 ||
            in.u8() != 0xC4 ||
            in.u8() != 0x00
        ) {
            throw new IOException("Not a VCDIFF delta.");
        }

        int header = in.u8();

        if ((header & 0x01) != 0) {
            throw new IOException("Secondary compression is not supported.");
        }

        if ((header & 0x02) != 0) {
            throw new IOException("Custom code tables are not supported.");
        }

        if ((header & 0x04) != 0) {
            in.skip(in.varint());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        while (in.remaining() > 0) {

            decodeWindow(in, source, out);
        }

        return out.toByteArray();
    }

    private static void decodeWindow(
        Reader in,
        byte[] source,
        ByteArrayOutputStream out
    ) throws IOException {

        int indicator = in.u8();

        int sourceStart = 0;
        int sourceLength = 0;

        if ((indicator & 0x02) != 0) {
            throw new IOException("VCD_TARGET windows are not supported.");
        }

        if ((indicator & 0x01) != 0) {

            sourceLength = in.varintInt();
            sourceStart = in.varintInt();

            if (
                sourceStart < 0 ||
                sourceLength < 0 ||
                (long) sourceStart + sourceLength > source.length
            ) {
                throw new IOException("Delta refers outside the source.");
            }
        }

        int deltaLength = in.varintInt();

        if (deltaLength > in.remaining()) {
            throw new IOException("Truncated delta.");
        }

        int windowEnd = in.position() + deltaLength;

        int targetLength = in.varintInt();

        int deltaIndicator = in.u8();

        if (deltaIndicator != 0) {
            throw new IOException("Compressed sections are not supported.");
        }

        int addLength = in.varintInt();
        int instLength = in.varintInt();
        int addrLength = in.varintInt();

        long expectedAdler = -1;

        if ((indicator & 0x04) != 0) {
            expectedAdler = in.u32();
        }

        Reader add = in.slice(addLength);
        Reader inst = in.slice(instLength);
        Reader addr = in.slice(addrLength);

        if (in.position() != windowEnd) {
            throw new IOException("Corrupt window layout.");
        }

        byte[] target = new byte[targetLength];
        int pos = 0;

        int[] near = new int[NEAR_SIZE];
        int[] same = new int[SAME_SIZE * 256];
        int nextNear = 0;

        while (inst.remaining() > 0) {

            int op = inst.u8();

            for (int half = 0; half < 2; half++) {

                int type = half == 0 ? TYPE1[op] : TYPE2[op];
                int size = half == 0 ? SIZE1[op] : SIZE2[op];
                int mode = half == 0 ? MODE1[op] : MODE2[op];

                if (half == 1 && type == T_NOOP) {
                    continue;
                }

                if (type == T_NOOP) {
                    continue;
                }

                if (size == 0) {
                    size = inst.varintInt();
                }

                if (size < 0 || pos + size > targetLength) {
                    throw new IOException("Instruction overruns the window.");
                }

                if (type == T_ADD) {

                    add.read(target, pos, size);
                    pos += size;

                } else if (type == T_RUN) {

                    byte value = (byte) add.u8();

                    for (int k = 0; k < size; k++) {
                        target[pos + k] = value;
                    }

                    pos += size;

                } else {

                    int here = sourceLength + pos;

                    int address;

                    if (mode == 0) {

                        address = addr.varintInt();

                    } else if (mode == 1) {

                        address = here - addr.varintInt();

                    } else if (mode < 2 + NEAR_SIZE) {

                        address = near[mode - 2] + addr.varintInt();

                    } else {

                        address =
                            same[(mode - (2 + NEAR_SIZE)) * 256 + addr.u8()];
                    }

                    if (address < 0 || address >= here) {
                        throw new IOException("Bad COPY address.");
                    }

                    near[nextNear] = address;
                    nextNear = (nextNear + 1) % NEAR_SIZE;
                    same[address % (SAME_SIZE * 256)] = address;

                    for (int k = 0; k < size; k++) {

                        int from = address + k;

                        target[pos + k] =
                            from < sourceLength
                                ? source[sourceStart + from]
                                : target[from - sourceLength];
                    }

                    pos += size;
                }
            }
        }

        if (pos != targetLength) {
            throw new IOException("Window decoded to the wrong length.");
        }

        if (expectedAdler >= 0) {

            Adler32 adler = new Adler32();

            adler.update(target, 0, targetLength);

            if (adler.getValue() != expectedAdler) {
                throw new IOException("Checksum mismatch (corrupt delta).");
            }
        }

        out.write(target, 0, targetLength);
    }

    /** Byte reader over a section of the delta. */
    private static final class Reader {

        private final byte[] data;
        private int pos;
        private final int end;

        Reader(byte[] data, int start, int end) {
            this.data = data;
            this.pos = start;
            this.end = end;
        }

        int position() {
            return pos;
        }

        int remaining() {
            return end - pos;
        }

        int u8() throws IOException {

            if (pos >= end) {
                throw new IOException("Unexpected end of delta.");
            }

            return data[pos++] & 0xFF;
        }

        long u32() throws IOException {

            long value = 0;

            for (int i = 0; i < 4; i++) {
                value = (value << 8) | u8();
            }

            return value;
        }

        long varint() throws IOException {

            long value = 0;

            for (int i = 0; i < 9; i++) {

                int b = u8();

                value = (value << 7) | (b & 0x7F);

                if ((b & 0x80) == 0) {
                    return value;
                }
            }

            throw new IOException("Bad integer in delta.");
        }

        int varintInt() throws IOException {

            long value = varint();

            if (value > Integer.MAX_VALUE) {
                throw new IOException("Value too large.");
            }

            return (int) value;
        }

        void skip(long count) throws IOException {

            if (count < 0 || count > remaining()) {
                throw new IOException("Unexpected end of delta.");
            }

            pos += (int) count;
        }

        void read(byte[] out, int offset, int length) throws IOException {

            if (length > remaining()) {
                throw new IOException("Unexpected end of data section.");
            }

            System.arraycopy(data, pos, out, offset, length);

            pos += length;
        }

        Reader slice(int length) throws IOException {

            if (length < 0 || length > remaining()) {
                throw new IOException("Unexpected end of delta.");
            }

            Reader part = new Reader(data, pos, pos + length);

            pos += length;

            return part;
        }
    }
}
