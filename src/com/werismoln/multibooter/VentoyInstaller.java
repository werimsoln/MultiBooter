/******************************************************************************
 * VentoyInstaller.java
 *
 * Copyright (c) 2026, werismoln <vlkanblek@gmail.com>
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

import android.content.Context;
import android.hardware.usb.UsbDevice;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;

public final class VentoyInstaller {

    public static final int RESULT_OK = 0;

    public static final int ERROR_ARGUMENT = -100;
    public static final int ERROR_USB = -101;
    public static final int ERROR_UNSUPPORTED_MEDIA = -102;
    public static final int ERROR_LAYOUT = -103;
    public static final int ERROR_ASSET = -104;
    public static final int ERROR_WRITE = -105;
    public static final int ERROR_FORMAT = -106;
    public static final int ERROR_SYNC = -107;

    /*
     * Ventoy default MBR layout:
     *
     *   LBA 0            : MBR / boot.img first 446 bytes
     *   LBA 1..2047      : core.img (2047 sectors)
     *   LBA 2048..       : Partition 1 (exFAT)
     *   Last 65536 LBAs  : Partition 2 (VTOYEFI, 32 MiB)
     *
     * Part 2 start is aligned down to a 4 KiB boundary exactly like
     * Ventoy's format_ventoy_disk_mbr() implementation.
     */
    private static final int LOGICAL_SECTOR_SIZE = 512;
    private static final long PART1_START_LBA = 2048L;

    private static final long VTOYEFI_SECTORS = 65536L;
    private static final long VTOYEFI_BYTES =
        VTOYEFI_SECTORS * LOGICAL_SECTOR_SIZE;

    private static final long CORE_SECTORS = 2047L;
    private static final long CORE_BYTES =
        CORE_SECTORS * LOGICAL_SECTOR_SIZE;

    private static final long BOOT_IMAGE_BYTES = 512L;

    private static final long MAX_MBR_SECTORS =
        0x100000000L;

    private static final int TRANSFER_SECTORS = 128;

    private static final String ASSET_BOOT =
        "boot.img";

    private static final String ASSET_CORE =
        "core.img";

    private static final String ASSET_VTOYEFI =
        "ventoy.disk.img";

    private final Context context;

    private volatile String lastError = "";

    public interface ProgressListener {

        void onProgress(
            int percent,
            String message
        );
    }

    private static final class Layout {

        long totalSectors;

        long part1Start;
        long part1Sectors;

        long part2Start;
        long part2Sectors;
    }

    public VentoyInstaller(
        Context context
    ) {

        if (context == null) {
            throw new IllegalArgumentException(
                "context == null"
            );
        }

        this.context =
            context.getApplicationContext();
    }

    public String getLastError() {
        return lastError;
    }

    private int fail(
        int code,
        String message
    ) {

        lastError =
            message == null
            ? ""
            : message;

        return code;
    }

    private void progress(
        ProgressListener listener,
        int percent,
        String message
    ) {

        if (listener == null) {
            return;
        }

        listener.onProgress(
            percent,
            message
        );
    }

    public int installMbr(
        UsbDevice device,
        ProgressListener listener
    ) {

        lastError = "";

        if (device == null) {

            return fail(
                ERROR_ARGUMENT,
                "USB device is null."
            );
        }

        progress(
            listener,
            2,
            "Opening USB device..."
        );

        UsbVentoy usb =
            new UsbVentoy(context);

        if (
            !usb.hasPermission(
                device
            )
        ) {

            return fail(
                ERROR_USB,
                "USB permission is not granted."
            );
        }

        int openResult =
            usb.open(
                device
            );

        if (
            openResult !=
            UsbVentoy.RESULT_OK
        ) {

            return fail(
                ERROR_USB,
                prefixError(
                    "Could not open USB device",
                    usb.getLastError()
                )
            );
        }

        try {

            if (
                usb.getBlockSize() !=
                LOGICAL_SECTOR_SIZE
            ) {

                return fail(
                    ERROR_UNSUPPORTED_MEDIA,
                    "Ventoy MBR installation currently requires a 512-byte logical sector size."
                );
            }

            long totalSectors =
                usb.getBlockCount();

            Layout layout =
                calculateLayout(
                    totalSectors
                );

            if (layout == null) {

                return fail(
                    ERROR_LAYOUT,
                    lastError.length() == 0
                        ? "Could not calculate Ventoy disk layout."
                        : lastError
                );
            }

            progress(
                listener,
                5,
                "Checking Ventoy assets..."
            );

            byte[] bootImage;

            try {

                bootImage =
                    readExactAsset(
                        ASSET_BOOT,
                        BOOT_IMAGE_BYTES
                    );

                validateAssetLength(
                    ASSET_CORE,
                    CORE_BYTES
                );

                validateAssetLength(
                    ASSET_VTOYEFI,
                    VTOYEFI_BYTES
                );

            } catch (IOException e) {

                return fail(
                    ERROR_ASSET,
                    "Ventoy asset validation failed: " +
                    safeMessage(e)
                );
            }

            byte[] diskUuid =
                new byte[16];

            new SecureRandom().nextBytes(
                diskUuid
            );

            byte[] mbr =
                buildMbr(
                    bootImage,
                    diskUuid,
                    layout
                );

            progress(
                listener,
                10,
                "Writing MBR partition table..."
            );

            if (
                !usb.writeData(
                    0,
                    mbr
                )
            ) {

                return fail(
                    ERROR_WRITE,
                    prefixError(
                        "Could not write Ventoy MBR",
                        usb.getLastError()
                    )
                );
            }

            progress(
                listener,
                18,
                "Formatting Ventoy data partition as exFAT..."
            );

            int volumeSerial =
                (
                    (diskUuid[0] & 0xFF) |
                    ((diskUuid[1] & 0xFF) << 8) |
                    ((diskUuid[2] & 0xFF) << 16) |
                    ((diskUuid[3] & 0xFF) << 24)
                );

            int formatResult =
                usb.formatExfat(
                    layout.part1Start,
                    layout.part1Sectors,
                    volumeSerial
                );

            if (
                formatResult !=
                UsbVentoy.RESULT_OK
            ) {

                return fail(
                    ERROR_FORMAT,
                    prefixError(
                        "Could not format Ventoy exFAT partition",
                        usb.getLastError()
                    )
                );
            }

            progress(
                listener,
                40,
                "Writing Ventoy BIOS bootloader..."
            );

            int coreResult =
                writeAssetExact(
                    usb,
                    ASSET_CORE,
                    1L,
                    CORE_BYTES,
                    40,
                    55,
                    listener
                );

            if (
                coreResult !=
                RESULT_OK
            ) {
                return coreResult;
            }

            progress(
                listener,
                56,
                "Writing Ventoy EFI partition..."
            );

            int efiResult =
                writeAssetExact(
                    usb,
                    ASSET_VTOYEFI,
                    layout.part2Start,
                    VTOYEFI_BYTES,
                    56,
                    96,
                    listener
                );

            if (
                efiResult !=
                RESULT_OK
            ) {
                return efiResult;
            }

            /*
             * Ventoy aligns partition 2 to 4 KiB. Depending on the total
             * sector count this can leave 0..7 sectors after VTOYEFI.
             * Clear them so a stale backup GPT header cannot survive there.
             */
            long trailingStart =
                layout.part2Start +
                layout.part2Sectors;

            long trailingSectors =
                layout.totalSectors -
                trailingStart;

            if (
                trailingSectors > 0
            ) {

                byte[] zeros =
                    new byte[
                        (int)
                        trailingSectors *
                        LOGICAL_SECTOR_SIZE
                    ];

                if (
                    !usb.writeBlocks(
                        trailingStart,
                        zeros,
                        (int)
                        trailingSectors
                    )
                ) {

                    return fail(
                        ERROR_WRITE,
                        prefixError(
                            "Could not clear trailing partition-table sectors",
                            usb.getLastError()
                        )
                    );
                }
            }

            progress(
                listener,
                97,
                "Synchronizing USB cache..."
            );

            if (
                !usb.synchronizeCache()
            ) {

                return fail(
                    ERROR_SYNC,
                    prefixError(
                        "Ventoy data was written but USB cache synchronization failed",
                        usb.getLastError()
                    )
                );
            }

            progress(
                listener,
                100,
                "Ventoy installation completed."
            );

            lastError = "";

            return RESULT_OK;

        } finally {

            usb.close();
        }
    }

    private Layout calculateLayout(
        long totalSectors
    ) {

        if (
            totalSectors <= 0
        ) {

            fail(
                ERROR_LAYOUT,
                "USB capacity is invalid."
            );

            return null;
        }

        /*
         * MBR uses 32-bit LBA fields.
         * Ventoy's default MBR installer also rejects disks over 2 TiB.
         */
        if (
            totalSectors >
            MAX_MBR_SECTORS
        ) {

            fail(
                ERROR_UNSUPPORTED_MEDIA,
                "The USB drive is too large for the current MBR installer. GPT support is required for media over 2 TiB."
            );

            return null;
        }

        long part1End =
            totalSectors -
            VTOYEFI_SECTORS -
            1L;

        long part2Start =
            part1End + 1L;

        long mod =
            part2Start % 8L;

        if (
            mod > 0
        ) {

            part1End -=
                mod;

            part2Start =
                part1End + 1L;
        }

        long part1Sectors =
            part1End -
            PART1_START_LBA +
            1L;

        if (
            part1Sectors < 4096L
        ) {

            fail(
                ERROR_LAYOUT,
                "USB drive is too small for the Ventoy partition layout."
            );

            return null;
        }

        long part2End =
            part2Start +
            VTOYEFI_SECTORS -
            1L;

        if (
            part2Start <=
                PART1_START_LBA ||
            part2End >=
                totalSectors
        ) {

            fail(
                ERROR_LAYOUT,
                "Calculated Ventoy partition layout is outside the USB capacity."
            );

            return null;
        }

        if (
            PART1_START_LBA >
                0xFFFFFFFFL ||
            part1Sectors >
                0xFFFFFFFFL ||
            part2Start >
                0xFFFFFFFFL ||
            VTOYEFI_SECTORS >
                0xFFFFFFFFL
        ) {

            fail(
                ERROR_LAYOUT,
                "Calculated Ventoy MBR partition values exceed 32-bit LBA limits."
            );

            return null;
        }

        Layout layout =
            new Layout();

        layout.totalSectors =
            totalSectors;

        layout.part1Start =
            PART1_START_LBA;

        layout.part1Sectors =
            part1Sectors;

        layout.part2Start =
            part2Start;

        layout.part2Sectors =
            VTOYEFI_SECTORS;

        return layout;
    }

    private byte[] buildMbr(
        byte[] bootImage,
        byte[] diskUuid,
        Layout layout
    ) {

        if (
            bootImage == null ||
            bootImage.length !=
                LOGICAL_SECTOR_SIZE
        ) {

            throw new IllegalArgumentException(
                "bootImage must be exactly 512 bytes."
            );
        }

        if (
            diskUuid == null ||
            diskUuid.length != 16
        ) {

            throw new IllegalArgumentException(
                "diskUuid must be exactly 16 bytes."
            );
        }

        byte[] mbr =
            new byte[
                LOGICAL_SECTOR_SIZE
            ];

        /*
         * Official Ventoy writes only the first 446 bytes of boot.img so
         * the partition table created separately is preserved.
         */
        System.arraycopy(
            bootImage,
            0,
            mbr,
            0,
            446
        );

        /*
         * Ventoy stores a 16-byte disk UUID at byte offset 384.
         */
        System.arraycopy(
            diskUuid,
            0,
            mbr,
            384,
            16
        );

        /*
         * The MBR disk signature is bytes 12..15 of that UUID.
         */
        System.arraycopy(
            diskUuid,
            12,
            mbr,
            440,
            4
        );

        writePartitionEntry(
            mbr,
            446,
            true,
            0x07,
            layout.part1Start,
            layout.part1Sectors
        );

        writePartitionEntry(
            mbr,
            462,
            false,
            0xEF,
            layout.part2Start,
            layout.part2Sectors
        );

        mbr[510] =
            (byte) 0x55;

        mbr[511] =
            (byte) 0xAA;

        return mbr;
    }

    private static void writePartitionEntry(
        byte[] mbr,
        int offset,
        boolean active,
        int partitionType,
        long startLba,
        long sectorCount
    ) {

        mbr[offset] =
            active
            ? (byte) 0x80
            : (byte) 0x00;

        putChs(
            mbr,
            offset + 1,
            startLba
        );

        mbr[offset + 4] =
            (byte)
            (partitionType & 0xFF);

        long endLba =
            startLba +
            sectorCount -
            1L;

        putChs(
            mbr,
            offset + 5,
            endLba
        );

        putLe32(
            mbr,
            offset + 8,
            startLba
        );

        putLe32(
            mbr,
            offset + 12,
            sectorCount
        );
    }

    private static void putChs(
        byte[] buffer,
        int offset,
        long lba
    ) {

        final int heads =
            255;

        final int sectorsPerTrack =
            63;

        long cylinder =
            lba /
            (
                (long) heads *
                sectorsPerTrack
            );

        if (
            cylinder > 1023L
        ) {

            buffer[offset] =
                (byte) 0xFE;

            buffer[offset + 1] =
                (byte) 0xFF;

            buffer[offset + 2] =
                (byte) 0xFF;

            return;
        }

        long remainder =
            lba %
            (
                (long) heads *
                sectorsPerTrack
            );

        int head =
            (int)
            (
                remainder /
                sectorsPerTrack
            );

        int sector =
            (int)
            (
                remainder %
                sectorsPerTrack
            ) +
            1;

        int cyl =
            (int) cylinder;

        buffer[offset] =
            (byte)
            (head & 0xFF);

        buffer[offset + 1] =
            (byte)
            (
                (sector & 0x3F) |
                ((cyl >> 2) & 0xC0)
            );

        buffer[offset + 2] =
            (byte)
            (cyl & 0xFF);
    }

    private static void putLe32(
        byte[] buffer,
        int offset,
        long value
    ) {

        buffer[offset] =
            (byte)
            (value & 0xFF);

        buffer[offset + 1] =
            (byte)
            (
                (value >>> 8) &
                0xFF
            );

        buffer[offset + 2] =
            (byte)
            (
                (value >>> 16) &
                0xFF
            );

        buffer[offset + 3] =
            (byte)
            (
                (value >>> 24) &
                0xFF
            );
    }

    private byte[] readExactAsset(
        String assetName,
        long expectedBytes
    ) throws IOException {

        if (
            expectedBytes < 0 ||
            expectedBytes >
                Integer.MAX_VALUE
        ) {

            throw new IOException(
                "Invalid asset length: " +
                assetName
            );
        }

        InputStream input =
            null;

        try {

            input =
                context
                    .getAssets()
                    .open(
                        assetName
                    );

            byte[] data =
                new byte[
                    (int) expectedBytes
                ];

            readFully(
                input,
                data,
                0,
                data.length
            );

            if (
                input.read() != -1
            ) {

                throw new IOException(
                    assetName +
                    " is larger than expected."
                );
            }

            return data;

        } finally {

            if (
                input != null
            ) {

                try {
                    input.close();
                } catch (
                    IOException ignored
                ) {
                }
            }
        }
    }

    private void validateAssetLength(
        String assetName,
        long expectedBytes
    ) throws IOException {

        InputStream input =
            null;

        try {

            input =
                context
                    .getAssets()
                    .open(
                        assetName
                    );

            byte[] buffer =
                new byte[
                    64 * 1024
                ];

            long total =
                0;

            while (true) {

                int read =
                    input.read(
                        buffer
                    );

                if (
                    read < 0
                ) {
                    break;
                }

                if (
                    read == 0
                ) {
                    continue;
                }

                total +=
                    read;

                if (
                    total >
                    expectedBytes
                ) {

                    throw new IOException(
                        assetName +
                        " is larger than expected."
                    );
                }
            }

            if (
                total !=
                expectedBytes
            ) {

                throw new IOException(
                    assetName +
                    " has unexpected size. Expected " +
                    expectedBytes +
                    " bytes, found " +
                    total +
                    "."
                );
            }

        } finally {

            if (
                input != null
            ) {

                try {
                    input.close();
                } catch (
                    IOException ignored
                ) {
                }
            }
        }
    }

    private int writeAssetExact(
        UsbVentoy usb,
        String assetName,
        long startLba,
        long expectedBytes,
        int progressStart,
        int progressEnd,
        ProgressListener listener
    ) {

        if (
            expectedBytes <= 0 ||
            expectedBytes %
                LOGICAL_SECTOR_SIZE != 0
        ) {

            return fail(
                ERROR_ASSET,
                "Invalid expected asset size for " +
                assetName +
                "."
            );
        }

        InputStream input =
            null;

        try {

            input =
                context
                    .getAssets()
                    .open(
                        assetName
                    );

            int bufferBytes =
                TRANSFER_SECTORS *
                LOGICAL_SECTOR_SIZE;

            byte[] buffer =
                new byte[
                    bufferBytes
                ];

            long remaining =
                expectedBytes;

            long written =
                0;

            long currentLba =
                startLba;

            while (
                remaining > 0
            ) {

                int chunk =
                    (int)
                    Math.min(
                        (long) buffer.length,
                        remaining
                    );

                readFully(
                    input,
                    buffer,
                    0,
                    chunk
                );

                byte[] writeBuffer;

                if (
                    chunk ==
                    buffer.length
                ) {

                    writeBuffer =
                        buffer;

                } else {

                    writeBuffer =
                        new byte[
                            chunk
                        ];

                    System.arraycopy(
                        buffer,
                        0,
                        writeBuffer,
                        0,
                        chunk
                    );
                }

                int sectors =
                    chunk /
                    LOGICAL_SECTOR_SIZE;

                if (
                    !usb.writeBlocks(
                        currentLba,
                        writeBuffer,
                        sectors
                    )
                ) {

                    return fail(
                        ERROR_WRITE,
                        prefixError(
                            "Could not write " +
                            assetName,
                            usb.getLastError()
                        )
                    );
                }

                currentLba +=
                    sectors;

                remaining -=
                    chunk;

                written +=
                    chunk;

                int percent =
                    progressStart +
                    (int)
                    (
                        (
                            (long)
                            (
                                progressEnd -
                                progressStart
                            ) *
                            written
                        ) /
                        expectedBytes
                    );

                progress(
                    listener,
                    percent,
                    "Writing " +
                    assetName +
                    "..."
                );
            }

            if (
                input.read() != -1
            ) {

                return fail(
                    ERROR_ASSET,
                    assetName +
                    " is larger than expected."
                );
            }

            return RESULT_OK;

        } catch (
            IOException e
        ) {

            return fail(
                ERROR_ASSET,
                "Could not read " +
                assetName +
                ": " +
                safeMessage(e)
            );

        } finally {

            if (
                input != null
            ) {

                try {
                    input.close();
                } catch (
                    IOException ignored
                ) {
                }
            }
        }
    }

    private static void readFully(
        InputStream input,
        byte[] buffer,
        int offset,
        int length
    ) throws IOException {

        int total =
            0;

        while (
            total <
            length
        ) {

            int read =
                input.read(
                    buffer,
                    offset + total,
                    length - total
                );

            if (
                read < 0
            ) {

                throw new IOException(
                    "Unexpected end of asset."
                );
            }

            if (
                read == 0
            ) {
                continue;
            }

            total +=
                read;
        }
    }

    private static String prefixError(
        String prefix,
        String detail
    ) {

        if (
            detail == null ||
            detail.length() == 0
        ) {

            return prefix + ".";
        }

        return
            prefix +
            ": " +
            detail;
    }

    private static String safeMessage(
        Throwable throwable
    ) {

        if (
            throwable == null
        ) {

            return "Unknown error.";
        }

        String message =
            throwable.getMessage();

        if (
            message == null ||
            message.length() == 0
        ) {

            return
                throwable
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }
}
