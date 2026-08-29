package com.werismoln.multibooter;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Java wrapper for the PXE/TFTP mode.
 *
 * The repository's libtftp.c exports JNI symbols for TftpNative, not
 * TftpMode.  Therefore this source contains a package-private TftpNative
 * bridge whose class name exactly matches the existing native symbols.
 */
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

    /**
     * Starts dnsmasq through libtftp.so.
     *
     * This mode requires root because the current native implementation
     * configures the selected interface and starts dnsmasq through su.
     */
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

    /**
     * Copies the APK asset to the application's private files directory.
     *
     * It is deliberately refreshed on each start so an updated APK cannot
     * accidentally keep an older previously-extracted dnsmasq binary.
     */
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

        /*
         * Java chmod first.
         */
        if (
            !destination.setExecutable(
                true,
                true
            )
        ) {

            /*
             * Fall back to the same root environment this mode already
             * requires.
             */
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

/**
 * JNI bridge name intentionally matches:
 *
 * Java_com_werismoln_multibooter_TftpNative_startPxeServer
 * Java_com_werismoln_multibooter_TftpNative_stopPxeServer
 */
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
