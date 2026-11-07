/******************************************************************************
 * VirtualDisk.java
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.SecureRandom;

/**
 * Creates the image file of a virtual USB flash drive.
 *
 * The file is sparse: only the parts that hold data (partition table, boot
 * code, the Ventoy partition, file system metadata) are written, so an 8 GiB
 * drive takes well under 100 MiB of real storage until files are copied to it.
 *
 * Pure Java (no Android classes) so it can be tested with disk images.
 */
public final class VirtualDisk {

    public enum Kind {

        /** Ventoy with an MBR partition table. */
        VENTOY_MBR("Ventoy (MBR)"),

        /** Ventoy with a GPT partition table. */
        VENTOY_GPT("Ventoy (GPT)"),

        /** An empty exFAT drive, ready to use. */
        EXFAT("Empty exFAT drive"),

        /** Nothing at all: the PC sees an uninitialized drive. */
        BLANK("Blank (uninitialized)");

        public final String label;

        Kind(String label) {
            this.label = label;
        }

        public boolean isVentoy() {

            return this == VENTOY_MBR || this == VENTOY_GPT;
        }
    }

    /** Where boot.img, core.img and ventoy.disk.img come from. */
    public interface Assets {

        InputStream open(String name) throws IOException;
    }

    public interface Progress {

        void onProgress(int percent, String text);

        boolean cancelled();
    }

    public static final String ASSET_BOOT = "boot.img";
    public static final String ASSET_CORE = "core.img";
    public static final String ASSET_VTOYEFI = "ventoy.disk.img";

    public static final long MIN_VENTOY_BYTES = 512L * 1024 * 1024;
    public static final long MIN_OTHER_BYTES = 64L * 1024 * 1024;

    /** MBR and the 10-byte SCSI commands both stop at 2 TiB. */
    public static final long MAX_BYTES = 2L * 1024 * 1024 * 1024 * 1024 - 1024 * 1024;

    private static final int SECTOR = 512;
    private static final int CORE_BYTES = 1048064;
    private static final long VTOYEFI_BYTES = 32L * 1024 * 1024;

    private VirtualDisk() {
    }

    public static long minBytes(Kind kind) {

        return kind.isVentoy() ? MIN_VENTOY_BYTES : MIN_OTHER_BYTES;
    }

    /**
     * Creates (or replaces) the image. On failure or cancel the partial file
     * is deleted.
     */
    public static void create(
        File image,
        long sizeBytes,
        Kind kind,
        Assets assets,
        Progress progress
    ) throws IOException {

        long sectors = sizeBytes / SECTOR;

        if (sectors * SECTOR < minBytes(kind) || sectors * SECTOR > MAX_BYTES) {

            throw new IOException(
                "The size must be between " +
                UsbAnalyzer.format(minBytes(kind)) + " and " +
                UsbAnalyzer.format(MAX_BYTES) + "."
            );
        }

        boolean done = false;

        RandomAccessFile file = null;

        try {

            report(progress, 1, "Creating the image file...");

            file = new RandomAccessFile(image, "rw");

            file.setLength(0);
            file.setLength(sectors * SECTOR);

            switch (kind) {

                case VENTOY_MBR:
                    createVentoy(file, sectors, false, assets, progress);
                    break;

                case VENTOY_GPT:
                    createVentoy(file, sectors, true, assets, progress);
                    break;

                case EXFAT:
                    createExFat(file, sectors, progress);
                    break;

                default:
                    break;                          // blank: all zero
            }

            file.getFD().sync();

            report(progress, 100, "Done.");

            done = true;

        } finally {

            if (file != null) {
                file.close();
            }

            if (!done) {
                image.delete();
            }
        }
    }

    // ---------------------------------------------------------------------

    private static void createVentoy(
        RandomAccessFile file,
        long sectors,
        boolean gpt,
        Assets assets,
        Progress progress
    ) throws IOException {

        byte[] boot = readExact(assets, ASSET_BOOT, SECTOR);
        byte[] core = readExact(assets, ASSET_CORE, CORE_BYTES);

        SecureRandom random = new SecureRandom();

        byte[] uuid = new byte[16];

        random.nextBytes(uuid);

        long part1Start = VentoyGpt.PART1_START;
        long part1Sectors;
        long part2Start;

        if (gpt) {

            VentoyGpt.Layout layout = VentoyGpt.calculateLayout(sectors);

            if (layout == null) {
                throw new IOException("The size is too small for Ventoy.");
            }

            part1Sectors = layout.part1Sectors;
            part2Start = layout.part2Start;

            byte[] diskGuid = new byte[16];
            byte[] guid1 = new byte[16];
            byte[] guid2 = new byte[16];

            random.nextBytes(diskGuid);
            random.nextBytes(guid1);
            random.nextBytes(guid2);

            report(progress, 3, "Writing the GPT...");

            write(file, 0, VentoyGpt.buildPrimary(boot, uuid, diskGuid, guid1, guid2, layout));
            write(file, VentoyGpt.CORE_START_LBA * SECTOR, VentoyGpt.buildCore(core));
            write(
                file,
                (sectors - VentoyGpt.BACKUP_SECTORS) * SECTOR,
                VentoyGpt.buildBackup(diskGuid, guid1, guid2, layout)
            );

        } else {

            part2Start = sectors - VentoyGpt.VTOYEFI_SECTORS;
            part2Start -= part2Start % 8;
            part1Sectors = part2Start - part1Start;

            report(progress, 3, "Writing the MBR...");

            byte[] mbr = new byte[SECTOR];

            System.arraycopy(boot, 0, mbr, 0, 446);
            System.arraycopy(uuid, 0, mbr, 384, 16);
            System.arraycopy(uuid, 12, mbr, 440, 4);

            entry(mbr, 446, true, 0x07, part1Start, part1Sectors);
            entry(mbr, 462, false, 0xEF, part2Start, VentoyGpt.VTOYEFI_SECTORS);

            mbr[510] = 0x55;
            mbr[511] = (byte) 0xAA;

            write(file, 0, mbr);
            write(file, SECTOR, core);
        }

        checkCancel(progress);

        // The 32 MiB Ventoy (VTOYEFI) partition.
        InputStream efi = assets.open(ASSET_VTOYEFI);

        try {

            byte[] chunk = new byte[1024 * 1024];

            long done = 0;

            file.seek(part2Start * SECTOR);

            while (done < VTOYEFI_BYTES) {

                int want = (int) Math.min(chunk.length, VTOYEFI_BYTES - done);

                int read = efi.read(chunk, 0, want);

                if (read <= 0) {
                    throw new IOException("The Ventoy image is shorter than expected.");
                }

                file.write(chunk, 0, read);

                done += read;

                report(
                    progress,
                    5 + (int) (done * 85 / VTOYEFI_BYTES),
                    "Writing Ventoy..."
                );

                checkCancel(progress);
            }

        } finally {

            efi.close();
        }

        report(progress, 92, "Formatting the data partition...");

        formatExFat(file, part1Start, part1Sectors, "Ventoy");
    }

    private static void createExFat(
        RandomAccessFile file,
        long sectors,
        Progress progress
    ) throws IOException {

        long start = VentoyGpt.PART1_START;
        long count = sectors - start;

        report(progress, 20, "Writing the partition table...");

        byte[] mbr = new byte[SECTOR];

        byte[] signature = new byte[4];

        new SecureRandom().nextBytes(signature);

        System.arraycopy(signature, 0, mbr, 440, 4);

        entry(mbr, 446, true, 0x07, start, count);

        mbr[510] = 0x55;
        mbr[511] = (byte) 0xAA;

        write(file, 0, mbr);

        report(progress, 50, "Formatting as exFAT...");

        formatExFat(file, start, count, "USB DRIVE");
    }

    private static void formatExFat(
        final RandomAccessFile file,
        final long startSector,
        long sectors,
        String label
    ) throws IOException {

        ExFatFormatter.format(
            new ExFatFormatter.Sink() {

                @Override
                public void write(long offset, byte[] data) throws IOException {

                    VirtualDisk.write(file, startSector * SECTOR + offset, data);
                }
            },
            sectors,
            startSector,
            label,
            (int) System.nanoTime()
        );
    }

    // ---------------------------------------------------------------------

    private static void entry(
        byte[] mbr,
        int offset,
        boolean active,
        int type,
        long start,
        long count
    ) {

        mbr[offset] = (byte) (active ? 0x80 : 0x00);
        mbr[offset + 1] = (byte) 0xFE;
        mbr[offset + 2] = (byte) 0xFF;
        mbr[offset + 3] = (byte) 0xFF;
        mbr[offset + 4] = (byte) type;
        mbr[offset + 5] = (byte) 0xFE;
        mbr[offset + 6] = (byte) 0xFF;
        mbr[offset + 7] = (byte) 0xFF;

        for (int i = 0; i < 4; i++) {

            mbr[offset + 8 + i] = (byte) (start >> (8 * i));
            mbr[offset + 12 + i] = (byte) (count >> (8 * i));
        }
    }

    private static void write(RandomAccessFile file, long offset, byte[] data)
        throws IOException {

        file.seek(offset);
        file.write(data);
    }

    private static byte[] readExact(Assets assets, String name, int length)
        throws IOException {

        InputStream in = assets.open(name);

        try {

            byte[] data = new byte[length];

            int done = 0;

            while (done < length) {

                int read = in.read(data, done, length - done);

                if (read < 0) {
                    throw new IOException(name + " is shorter than expected.");
                }

                done += read;
            }

            if (in.read() >= 0) {
                throw new IOException(name + " is longer than expected.");
            }

            return data;

        } finally {

            in.close();
        }
    }

    private static void report(Progress progress, int percent, String text) {

        if (progress != null) {
            progress.onProgress(percent, text);
        }
    }

    private static void checkCancel(Progress progress) throws IOException {

        if (progress != null && progress.cancelled()) {
            throw new IOException("Creating the drive was cancelled.");
        }
    }
}
