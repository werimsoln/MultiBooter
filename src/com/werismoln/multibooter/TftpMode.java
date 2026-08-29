package com.werismoln.multibooter;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class TftpMode {

    private static final String DNSMASQ_ASSET = "dnsmasq";
    private static final String DNSMASQ_FILE = "dnsmasq";

    private static final boolean LIBRARY_LOADED;

    private static volatile String lastError = "";

    static {
        boolean loaded = false;

        try {
            System.loadLibrary("tftp");
            loaded = true;
        } catch (Throwable e) {
            lastError = "libtftp.so could not be loaded: " + e;
        }

        LIBRARY_LOADED = loaded;
    }

    private TftpMode() {
    }

    public static boolean isLibraryLoaded() {
        return LIBRARY_LOADED;
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean startPxe(
        Context context,
        String interfaceName,
        String tftpRootPath
    ) {

        if (!LIBRARY_LOADED) {
            if (lastError.length() == 0) {
                lastError = "libtftp.so is not loaded.";
            }
            return false;
        }

        if (context == null) {
            lastError = "context == null";
            return false;
        }

        if (
            interfaceName == null ||
            interfaceName.trim().length() == 0
        ) {
            lastError = "Network interface name is empty.";
            return false;
        }

        if (
            tftpRootPath == null ||
            tftpRootPath.trim().length() == 0
        ) {
            lastError = "TFTP root path is empty.";
            return false;
        }

        File tftpRoot =
            new File(tftpRootPath);

        if (
            !tftpRoot.exists() ||
            !tftpRoot.isDirectory()
        ) {
            lastError =
                "TFTP root directory does not exist: " +
                tftpRootPath;
            return false;
        }

        try {

            File dnsmasq =
                prepareDnsmasq(
                    context.getApplicationContext()
                );

            if (dnsmasq == null) {
                return false;
            }

            boolean result =
                TftpNative.startPxeServer(
                    dnsmasq.getAbsolutePath(),
                    interfaceName.trim(),
                    tftpRoot.getAbsolutePath()
                );

            if (!result) {
                lastError =
                    "libtftp.so could not start the PXE/TFTP server.";
                return false;
            }

            lastError = "";
            return true;

        } catch (Throwable e) {

            lastError =
                "PXE/TFTP start failed: " + e;

            return false;
        }
    }

    public static boolean stopPxe() {

        if (!LIBRARY_LOADED) {
            if (lastError.length() == 0) {
                lastError = "libtftp.so is not loaded.";
            }
            return false;
        }

        try {

            boolean result =
                TftpNative.stopPxeServer();

            if (!result) {
                lastError =
                    "libtftp.so could not stop dnsmasq.";
                return false;
            }

            lastError = "";
            return true;

        } catch (Throwable e) {

            lastError =
                "PXE/TFTP stop failed: " + e;

            return false;
        }
    }

    private static File prepareDnsmasq(
        Context context
    ) {

        File destination =
            new File(
                context.getFilesDir(),
                DNSMASQ_FILE
            );

        InputStream input = null;
        FileOutputStream output = null;

        try {

            input =
                context.getAssets().open(
                    DNSMASQ_ASSET
                );

            output =
                new FileOutputStream(
                    destination,
                    false
                );

            byte[] buffer =
                new byte[16 * 1024];

            int read;

            while (
                (read = input.read(buffer)) != -1
            ) {

                output.write(
                    buffer,
                    0,
                    read
                );
            }

            output.flush();

        } catch (Exception e) {

            lastError =
                "dnsmasq asset extraction failed: " + e;

            return null;

        } finally {

            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
            }

            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
        }

        if (
            !destination.setExecutable(
                true,
                true
            )
        ) {

            Process process = null;

            try {

                process =
                    Runtime.getRuntime().exec(
                        new String[] {
                            "su",
                            "-c",
                            "chmod 700 " +
                            shellQuote(
                                destination.getAbsolutePath()
                            )
                        }
                    );

                int exit =
                    process.waitFor();

                if (exit != 0) {
                    lastError =
                        "Could not make dnsmasq executable.";
                    return null;
                }

            } catch (Exception e) {

                lastError =
                    "chmod dnsmasq failed: " + e;

                return null;

            } finally {

                if (process != null) {
                    process.destroy();
                }
            }
        }

        return destination;
    }

    private static String shellQuote(
        String value
    ) {

        if (value == null) {
            return "''";
        }

        return "'" +
            value.replace(
                "'",
                "'\\''"
            ) +
            "'";
    }
}

final class TftpNative {

    private TftpNative() {
    }

    static native boolean startPxeServer(
        String binaryPath,
        String iface,
        String tftpRoot
    );

    static native boolean stopPxeServer();
}
