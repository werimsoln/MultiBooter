/******************************************************************************
 * UsbAnalyzer.java
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Looks at a whole drive and describes it: partition scheme (MBR / GPT),
 * every partition with its file system, free space of exFAT partitions, boot
 * images found on them, space that belongs to no partition, and whether the
 * drive has Ventoy. Read-only.
 */
public final class UsbAnalyzer {

    /** The drive, addressed in bytes from its start. */
    public interface Device {

        byte[] read(long offset, int length) throws IOException;

        long sizeBytes();

        int sectorSize();
    }

    public static final class Partition {

        public int number;
        public long startBytes;
        public long sizeBytes;
        public String type = "";
        public String name = "";
        public String filesystem = "";
        public String label = "";
        public boolean bootable;

        /** Only for exFAT partitions that were scanned. */
        public ExFatReader.Info exfat;
    }

    public static final class Report {

        public String scheme = "none";
        public long diskBytes;
        public int sectorSize;
        public final List<Partition> partitions = new ArrayList<Partition>();

        /** Bytes that no partition covers (including the start gap). */
        public long unallocatedBytes;

        public VentoyDetector.Info ventoy;

        public String isoOnDisk = "";

        public final List<String> notes = new ArrayList<String>();

        public String error = "";
    }

    public interface Control extends ExFatReader.Control {
    }

    private static final long MIB = 1024L * 1024L;

    private UsbAnalyzer() {
    }

    public static Report analyze(
        Device device,
        ExFatReader.Options options,
        Control control
    ) {

        Report report = new Report();

        report.diskBytes = device.sizeBytes();
        report.sectorSize = device.sectorSize();

        try {

            run(device, options, control, report);

        } catch (IOException e) {

            report.error = "Read error: " + e.getMessage();

        } catch (RuntimeException e) {

            report.error = "Unexpected data on the drive.";
        }

        return report;
    }

    private static void run(
        Device device,
        ExFatReader.Options options,
        Control control,
        Report report
    ) throws IOException {

        int sector = device.sectorSize();

        byte[] head = device.read(0, sector * 34);

        boolean mbrSignature =
            head.length >= 512 &&
            (head[510] & 0xFF) == 0x55 &&
            (head[511] & 0xFF) == 0xAA;

        boolean gpt =
            mbrSignature &&
            (head[450] & 0xFF) == 0xEE &&
            head.length >= sector + 8 &&
            matches(head, sector, "EFI PART");

        if (gpt) {

            report.scheme = "GPT";
            readGpt(device, head, report);

        } else if (mbrSignature) {

            report.scheme = "MBR";
            readMbr(head, sector, report);
        }

        // A drive written with a plain ISO has no partition table, or a
        // hybrid one. Check for an ISO 9660 image from the start.
        byte[] iso = safeRead(device, 32768, 8);

        if (iso != null && matches(iso, 1, "CD001")) {

            report.isoOnDisk =
                "An ISO 9660 image starts at the beginning of the drive " +
                "(for example written by the ISO writer).";
        }

        // Partition contents.
        for (Partition partition : report.partitions) {

            if (control != null && control.cancelled()) {
                return;
            }

            detectFilesystem(device, partition);

            if ("exFAT".equals(partition.filesystem)) {

                scanExFat(device, partition, options, control);
            }
        }

        if (report.partitions.isEmpty() && report.isoOnDisk.length() == 0) {

            byte[] first = safeRead(device, 0, 4096);

            Partition whole = new Partition();

            whole.startBytes = 0;
            whole.sizeBytes = device.sizeBytes();

            if (first != null) {

                String fs = signature(first, device, whole);

                if (fs.length() > 0) {

                    whole.number = 1;
                    whole.type = "Whole drive";
                    whole.filesystem = fs;

                    report.partitions.add(whole);

                    if ("exFAT".equals(fs)) {
                        scanExFat(device, whole, options, control);
                    }
                }
            }
        }

        // Ventoy layout (512-byte sectors only).
        if (sector == 512 && report.partitions.size() >= 2) {

            report.ventoy =
                VentoyDetector.detect(
                    new VentoyDetector.SectorReader() {

                        private Device d;

                        VentoyDetector.SectorReader with(Device device) {
                            d = device;
                            return this;
                        }

                        @Override
                        public byte[] read(long lba, int count)
                            throws IOException {

                            return d.read(lba * 512, count * 512);
                        }
                    }.with(device),
                    device.sizeBytes() / 512
                );
        }

        computeUnallocated(report);

        notes(report);
    }

    // ---------------------------------------------------------------------
    // Partition tables
    // ---------------------------------------------------------------------

    private static void readMbr(byte[] mbr, int sector, Report report) {

        for (int i = 0; i < 4; i++) {

            int o = 446 + i * 16;

            int type = mbr[o + 4] & 0xFF;

            long start = le32(mbr, o + 8);
            long count = le32(mbr, o + 12);

            if (type == 0 || count == 0) {
                continue;
            }

            Partition p = new Partition();

            p.number = i + 1;
            p.startBytes = start * sector;
            p.sizeBytes = count * sector;
            p.bootable = (mbr[o] & 0xFF) == 0x80;
            p.type = mbrType(type);

            if (type == 0x05 || type == 0x0F || type == 0x85) {
                report.notes.add(
                    "Partition " + p.number + " is an extended partition; " +
                    "the partitions inside it are not listed."
                );
            }

            report.partitions.add(p);
        }
    }

    private static void readGpt(Device device, byte[] head, Report report)
        throws IOException {

        int sector = device.sectorSize();

        long entriesLba = le64(head, sector + 72);
        long count = le32(head, sector + 80);
        long size = le32(head, sector + 84);

        if (size < 128 || size > 512 || count == 0 || count > 256) {

            report.notes.add("The GPT header has unusual values.");
            return;
        }

        byte[] entries =
            device.read(entriesLba * sector, (int) (count * size));

        for (int i = 0; i < count; i++) {

            int o = (int) (i * size);

            boolean empty = true;

            for (int k = 0; k < 16; k++) {

                if (entries[o + k] != 0) {
                    empty = false;
                    break;
                }
            }

            if (empty) {
                continue;
            }

            long first = le64(entries, o + 32);
            long last = le64(entries, o + 40);

            Partition p = new Partition();

            p.number = i + 1;
            p.startBytes = first * sector;
            p.sizeBytes = (last - first + 1) * sector;
            p.type = gptType(guid(entries, o));
            p.name = utf16(entries, o + 56, 36);

            report.partitions.add(p);
        }
    }

    private static String mbrType(int type) {

        switch (type) {
            case 0x01: return "FAT12";
            case 0x04: case 0x06: case 0x0E: return "FAT16";
            case 0x07: return "NTFS / exFAT";
            case 0x0B: case 0x0C: return "FAT32";
            case 0x82: return "Linux swap";
            case 0x83: return "Linux";
            case 0xEE: return "GPT protective";
            case 0xEF: return "EFI System";
            case 0xFD: return "Linux RAID";
            default: return String.format(Locale.US, "type 0x%02X", type);
        }
    }

    private static String gptType(String guid) {

        if (guid.equals("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"))
            return "Basic data";

        if (guid.equals("C12A7328-F81F-11D2-BA4B-00A0C93EC93B"))
            return "EFI System";

        if (guid.equals("0FC63DAF-8483-4772-8E79-3D69D8477DE4"))
            return "Linux filesystem";

        if (guid.equals("E3C9E316-0B5C-4DB8-817D-F92DF00215AE"))
            return "Microsoft reserved";

        if (guid.equals("21686148-6449-6E6F-744E-656564454649"))
            return "BIOS boot";

        if (guid.equals("0657FD6D-A4AB-43C4-84E5-0933C84B4F4F"))
            return "Linux swap";

        return guid;
    }

    // ---------------------------------------------------------------------
    // File systems
    // ---------------------------------------------------------------------

    private static void detectFilesystem(Device device, Partition p)
        throws IOException {

        byte[] first = safeRead(device, p.startBytes, 4096);

        if (first == null) {
            return;
        }

        p.filesystem = signature(first, device, p);
    }

    private static String signature(byte[] first, Device device, Partition p) {

        if (matches(first, 3, "EXFAT   ")) return "exFAT";
        if (matches(first, 3, "NTFS    ")) return "NTFS";
        if (matches(first, 3, "-FVE-FS-")) return "BitLocker";
        if (matches(first, 82, "FAT32   ")) return "FAT32";
        if (matches(first, 54, "FAT16   ")) return "FAT16";
        if (matches(first, 54, "FAT12   ")) return "FAT12";

        if (
            first.length >= 1082 &&
            (first[1080] & 0xFF) == 0x53 &&
            (first[1081] & 0xFF) == 0xEF
        ) {
            return "ext2/3/4";
        }

        if (matches(first, 0, "LUKS")) return "LUKS";

        return "";
    }

    private static void scanExFat(
        final Device device,
        final Partition partition,
        ExFatReader.Options options,
        ExFatReader.Control control
    ) {

        final long base = partition.startBytes;

        partition.exfat =
            ExFatReader.analyze(
                new ExFatReader.Bytes() {

                    @Override
                    public byte[] read(long offset, int length)
                        throws IOException {

                        return readAligned(device, base + offset, length);
                    }
                },
                options,
                control
            );

        if (partition.exfat.valid) {
            partition.label = partition.exfat.label;
        }
    }

    /** Reads any byte range, even if the drive needs whole sectors. */
    private static byte[] readAligned(Device device, long offset, int length)
        throws IOException {

        int sector = device.sectorSize();

        long start = offset - offset % sector;
        long end = offset + length;

        long alignedEnd = ((end + sector - 1) / sector) * sector;

        byte[] raw = device.read(start, (int) (alignedEnd - start));

        byte[] out = new byte[length];

        System.arraycopy(raw, (int) (offset - start), out, 0, length);

        return out;
    }

    // ---------------------------------------------------------------------
    // Free space and notes
    // ---------------------------------------------------------------------

    private static void computeUnallocated(Report report) {

        List<Partition> sorted = new ArrayList<Partition>(report.partitions);

        Collections.sort(
            sorted,
            new Comparator<Partition>() {

                @Override
                public int compare(Partition a, Partition b) {

                    return a.startBytes < b.startBytes
                        ? -1
                        : (a.startBytes == b.startBytes ? 0 : 1);
                }
            }
        );

        long covered = 0;
        long cursor = 0;

        for (Partition p : sorted) {

            if (p.startBytes > cursor) {
                covered += 0;
            }

            covered += p.sizeBytes;

            cursor = Math.max(cursor, p.startBytes + p.sizeBytes);
        }

        // The first MiB (partition table, boot code) and the backup GPT at
        // the end are reserved by design, not wasted space.
        long reserved =
            Math.min(MIB, sorted.isEmpty() ? 0 : sorted.get(0).startBytes);

        if ("GPT".equals(report.scheme)) {
            reserved += 33L * report.sectorSize;
        }

        report.unallocatedBytes =
            Math.max(0, report.diskBytes - covered - reserved);
    }

    private static void notes(Report report) {

        for (Partition p : report.partitions) {

            if (p.startBytes % 4096 != 0) {

                report.notes.add(
                    "Partition " + p.number +
                    " does not start on a 4 KiB boundary (slower writes on " +
                    "some drives)."
                );
            }

            if (
                p.exfat != null &&
                p.exfat.valid &&
                p.exfat.volumeBytes + MIB < p.sizeBytes
            ) {

                report.notes.add(
                    "The exFAT file system of partition " + p.number +
                    " is smaller than its partition (" +
                    format(p.exfat.volumeBytes) + " of " +
                    format(p.sizeBytes) + ")."
                );
            }
        }

        if (
            report.unallocatedBytes > 16 * MIB &&
            report.partitions.size() > 0
        ) {

            report.notes.add(
                format(report.unallocatedBytes) +
                " of the drive is not part of any partition."
            );
        }
    }

    // ---------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------

    public static String format(long bytes) {

        if (bytes < 0) {
            return "?";
        }

        double value = bytes;

        String[] units = { "B", "KiB", "MiB", "GiB", "TiB" };

        int unit = 0;

        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }

        return unit == 0
            ? String.format(Locale.US, "%d B", bytes)
            : String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    /** A readable summary for the result dialog. */
    public static String describe(Report report, int maxImages) {

        StringBuilder text = new StringBuilder();

        if (report.error.length() > 0) {
            text.append("Could not analyze the drive: ")
                .append(report.error)
                .append('\n');
            return text.toString();
        }

        text.append("Drive: ")
            .append(format(report.diskBytes))
            .append("  \u2022  ")
            .append(
                "none".equals(report.scheme)
                    ? "no partition table"
                    : report.scheme
            )
            .append("  \u2022  ")
            .append(report.sectorSize)
            .append("-byte sectors\n");

        if (report.ventoy != null && report.ventoy.layoutFound) {

            text.append("Ventoy ")
                .append(
                    report.ventoy.version.length() == 0
                        ? "(version unknown)"
                        : report.ventoy.version
                )
                .append(" is installed (")
                .append(report.ventoy.gpt ? "GPT" : "MBR")
                .append(")\n");
        }

        if (report.isoOnDisk.length() > 0) {
            text.append(report.isoOnDisk).append('\n');
        }

        for (Partition p : report.partitions) {

            text.append('\n')
                .append("Partition ")
                .append(p.number)
                .append(":  ")
                .append(format(p.sizeBytes));

            if (p.filesystem.length() > 0) {
                text.append("  \u2022  ").append(p.filesystem);
            }

            if (p.label.length() > 0) {
                text.append("  \"").append(p.label).append('"');
            } else if (p.name.length() > 0) {
                text.append("  \"").append(p.name).append('"');
            }

            text.append("  \u2022  ").append(p.type);

            if (p.bootable) {
                text.append("  \u2022  active");
            }

            text.append('\n');

            if (p.exfat != null) {
                describeExFat(text, p.exfat, maxImages);
            }
        }

        if (report.unallocatedBytes > MIB) {

            text.append('\n')
                .append("Not in any partition: ")
                .append(format(report.unallocatedBytes))
                .append('\n');
        }

        for (String note : report.notes) {
            text.append("\n\u2022 ").append(note);
        }

        return text.toString();
    }

    private static void describeExFat(
        StringBuilder text,
        ExFatReader.Info info,
        int maxImages
    ) {

        if (!info.valid) {

            text.append("  exFAT could not be read: ")
                .append(info.error)
                .append('\n');

            return;
        }

        if (info.freeClusters >= 0) {

            long total = info.clusterCount * info.clusterBytes;

            text.append("  Used ")
                .append(format(info.usedBytes()))
                .append("  \u2022  Free ")
                .append(format(info.freeBytes()))
                .append(" (")
                .append(
                    total == 0
                        ? 0
                        : Math.round(info.freeBytes() * 100.0 / total)
                )
                .append(" %)  \u2022  clusters ")
                .append(format(info.clusterBytes))
                .append('\n');

        } else {

            text.append("  Free space could not be read.\n");
        }

        text.append("  ")
            .append(info.filesSeen)
            .append(" files, ")
            .append(info.directoriesSeen)
            .append(" folders");

        if (info.truncated) {
            text.append(info.cancelled ? " (stopped)" : " (scan limit reached)");
        }

        text.append('\n');

        if (info.images.isEmpty()) {

            text.append("  No ISO / IMG / WIM / VHD files found.\n");
            return;
        }

        List<ExFatReader.Found> images =
            new ArrayList<ExFatReader.Found>(info.images);

        Collections.sort(
            images,
            new Comparator<ExFatReader.Found>() {

                @Override
                public int compare(ExFatReader.Found a, ExFatReader.Found b) {

                    return a.path.compareToIgnoreCase(b.path);
                }
            }
        );

        long total = 0;

        for (ExFatReader.Found f : images) {
            total += f.size;
        }

        text.append("  ")
            .append(images.size())
            .append(" boot images, ")
            .append(format(total))
            .append(" in total:\n");

        int shown = Math.min(maxImages, images.size());

        for (int i = 0; i < shown; i++) {

            text.append("    ")
                .append(images.get(i).path)
                .append("  (")
                .append(format(images.get(i).size))
                .append(")\n");
        }

        if (images.size() > shown) {

            text.append("    ... and ")
                .append(images.size() - shown)
                .append(" more\n");
        }
    }

    // ---------------------------------------------------------------------

    private static byte[] safeRead(Device device, long offset, int length) {

        try {

            if (offset + length > device.sizeBytes()) {
                return null;
            }

            return readAligned(device, offset, length);

        } catch (IOException e) {

            return null;
        }
    }

    private static boolean matches(byte[] data, int offset, String text) {

        if (data == null || offset + text.length() > data.length) {
            return false;
        }

        for (int i = 0; i < text.length(); i++) {

            if (data[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }

        return true;
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

    private static String guid(byte[] b, int o) {

        return String.format(
            Locale.US,
            "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X",
            le32(b, o),
            (b[o + 4] & 0xFF) | ((b[o + 5] & 0xFF) << 8),
            (b[o + 6] & 0xFF) | ((b[o + 7] & 0xFF) << 8),
            b[o + 8] & 0xFF,
            b[o + 9] & 0xFF,
            b[o + 10] & 0xFF,
            b[o + 11] & 0xFF,
            b[o + 12] & 0xFF,
            b[o + 13] & 0xFF,
            b[o + 14] & 0xFF,
            b[o + 15] & 0xFF
        );
    }

    private static String utf16(byte[] b, int offset, int chars) {

        StringBuilder text = new StringBuilder();

        for (int i = 0; i < chars; i++) {

            int c = (b[offset + i * 2] & 0xFF) | ((b[offset + i * 2 + 1] & 0xFF) << 8);

            if (c == 0) {
                break;
            }

            text.append((char) c);
        }

        return text.toString();
    }
}
