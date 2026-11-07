package com.werismoln.multibooter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Creates every kind of virtual drive from synthetic boot assets and checks
 * the result with the partition analyzer and the exFAT reader.
 * Pure Java, no external tools.
 */
public class VirtualDiskTest {

    static int failures = 0;

    static void check(boolean condition, String what) {

        System.out.println((condition ? "PASS " : "FAIL ") + what);

        if (!condition) {
            failures++;
        }
    }

    static final VirtualDisk.Assets ASSETS = new VirtualDisk.Assets() {

        @Override
        public InputStream open(String name) throws IOException {

            if (name.equals("boot.img")) {
                byte[] boot = new byte[512];
                boot[92] = 1;
                return new ByteArrayInputStream(boot);
            }

            if (name.equals("core.img")) {
                byte[] core = new byte[1048064];
                core[500] = 2;
                return new ByteArrayInputStream(core);
            }

            return new ByteArrayInputStream(new byte[32 * 1024 * 1024]);
        }
    };

    static UsbAnalyzer.Report analyze(final File image) throws IOException {

        final RandomAccessFile file = new RandomAccessFile(image, "r");

        try {

            return UsbAnalyzer.analyze(
                new UsbAnalyzer.Device() {

                    @Override
                    public byte[] read(long offset, int length) throws IOException {
                        byte[] data = new byte[length];
                        file.seek(offset);
                        file.readFully(data);
                        return data;
                    }

                    @Override
                    public long sizeBytes() {
                        try {
                            return file.length();
                        } catch (IOException e) {
                            return 0;
                        }
                    }

                    @Override
                    public int sectorSize() {
                        return 512;
                    }
                },
                new ExFatReader.Options(),
                null
            );

        } finally {

            file.close();
        }
    }

    public static void main(String[] args) throws Exception {

        long size = 700L * 1024 * 1024;

        for (VirtualDisk.Kind kind : VirtualDisk.Kind.values()) {

            File image = File.createTempFile("virtual-" + kind, ".img");

            try {

                VirtualDisk.create(image, size, kind, ASSETS, null);

                check(image.length() == size, kind + ": image has the requested size");

                UsbAnalyzer.Report r = analyze(image);

                if (kind == VirtualDisk.Kind.BLANK) {

                    check(r.partitions.isEmpty() && r.scheme.equals("none"),
                        "BLANK: no partition table");

                    continue;
                }

                UsbAnalyzer.Partition p1 = r.partitions.get(0);

                check(p1.filesystem.equals("exFAT"), kind + ": first partition is exFAT");

                check(p1.exfat != null && p1.exfat.valid, kind + ": exFAT reads back");

                check(
                    p1.exfat.freeBytes() > p1.sizeBytes * 9 / 10,
                    kind + ": nearly all of the exFAT space is free"
                );

                if (kind.isVentoy()) {

                    check(
                        r.scheme.equals(kind == VirtualDisk.Kind.VENTOY_GPT ? "GPT" : "MBR"),
                        kind + ": partition scheme"
                    );

                    check(r.partitions.size() == 2, kind + ": two partitions");

                    check(
                        r.partitions.get(1).sizeBytes == 32L * 1024 * 1024,
                        kind + ": VTOYEFI is 32 MiB"
                    );

                    check(p1.label.equals("Ventoy"), kind + ": label is Ventoy");

                    check(
                        r.ventoy != null && r.ventoy.layoutFound,
                        kind + ": Ventoy layout recognized"
                    );

                } else {

                    check(r.partitions.size() == 1, kind + ": one partition");
                }

            } finally {

                image.delete();
            }
        }

        // too small / too big sizes are refused and leave no file behind
        File small = new File(System.getProperty("java.io.tmpdir"), "tiny-virtual.img");

        try {

            VirtualDisk.create(small, 1024 * 1024, VirtualDisk.Kind.VENTOY_MBR, ASSETS, null);

            check(false, "a 1 MiB Ventoy drive must be refused");

        } catch (IOException expected) {

            check(!small.exists(), "refused size leaves no file");
        }

        // cancel removes the partial file
        File cancelled = File.createTempFile("cancelled", ".img");

        try {

            VirtualDisk.create(
                cancelled,
                size,
                VirtualDisk.Kind.VENTOY_MBR,
                ASSETS,
                new VirtualDisk.Progress() {

                    @Override
                    public void onProgress(int percent, String text) {
                    }

                    @Override
                    public boolean cancelled() {
                        return true;
                    }
                }
            );

            check(false, "cancel must stop the creation");

        } catch (IOException expected) {

            check(!cancelled.exists(), "cancel deletes the partial image");
        }

        System.out.println(failures == 0 ? "ALL OK" : "FAILURES: " + failures);

        System.exit(failures == 0 ? 0 : 1);
    }
}
