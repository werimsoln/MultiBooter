package com.werismoln.multibooter;

import java.io.File;

/**
 * Java JNI wrapper for libfunctionfs.so.
 *
 * libfunctionfs.so contains the FunctionFS event loop and the read-only
 * USB Mass Storage BOT/SCSI backend. No standalone ffs_gadget executable
 * is required.
 */
public final class FunctionFileSystem {

    private static final boolean LIBRARY_LOADED;

    private static volatile String javaLastError = "";

    static {

        boolean loaded = false;

        try {

            System.loadLibrary(
                "functionfs"
            );

            loaded = true;

        } catch (Throwable e) {

            javaLastError =
                "libfunctionfs.so could not be loaded: " +
                e;
        }

        LIBRARY_LOADED =
            loaded;
    }

    private FunctionFileSystem() {
    }

    private static native boolean prepareNative();

    private static native boolean startNative(
        String isoPath
    );

    private static native boolean stopNative();

    private static native boolean isRunningNative();

    private static native String getLastErrorNative();

    public static boolean isLibraryLoaded() {
        return LIBRARY_LOADED;
    }

    /**
     * Mounts/prepares /dev/usb-ffs/multiboot through root when needed.
     *
     * The native library itself still runs as the normal application UID;
     * the FunctionFS mount is prepared with endpoint ownership assigned to
     * that UID/GID.
     */
    public static boolean prepare() {

        if (!LIBRARY_LOADED) {

            if (
                javaLastError.length() == 0
            ) {

                javaLastError =
                    "libfunctionfs.so is not loaded.";
            }

            return false;
        }

        try {

            boolean result =
                prepareNative();

            if (!result) {

                javaLastError =
                    safeNativeError();

                return false;
            }

            javaLastError = "";
            return true;

        } catch (Throwable e) {

            javaLastError =
                "FunctionFS preparation failed: " +
                e;

            return false;
        }
    }

    /**
     * Starts the FunctionFS backend for a local filesystem ISO path.
     *
     * This remains a root-dependent feature because FunctionFS mount /
     * ConfigFS setup requires privileged access on normal Android devices.
     */
    public static boolean start(
        String isoPath
    ) {

        if (!LIBRARY_LOADED) {

            if (
                javaLastError.length() == 0
            ) {

                javaLastError =
                    "libfunctionfs.so is not loaded.";
            }

            return false;
        }

        if (
            isoPath == null ||
            isoPath.trim().length() == 0
        ) {

            javaLastError =
                "ISO path is empty.";

            return false;
        }

        File iso =
            new File(
                isoPath
            );

        if (
            !iso.exists() ||
            !iso.isFile() ||
            !iso.canRead()
        ) {

            javaLastError =
                "ISO file is not readable: " +
                isoPath;

            return false;
        }

        try {

            boolean result =
                startNative(
                    iso.getAbsolutePath()
                );

            if (!result) {

                javaLastError =
                    safeNativeError();

                return false;
            }

            javaLastError = "";
            return true;

        } catch (Throwable e) {

            javaLastError =
                "FunctionFS start failed: " +
                e;

            return false;
        }
    }

    public static boolean stop() {

        if (!LIBRARY_LOADED) {

            if (
                javaLastError.length() == 0
            ) {

                javaLastError =
                    "libfunctionfs.so is not loaded.";
            }

            return false;
        }

        try {

            boolean result =
                stopNative();

            if (!result) {

                javaLastError =
                    safeNativeError();

                return false;
            }

            javaLastError = "";
            return true;

        } catch (Throwable e) {

            javaLastError =
                "FunctionFS stop failed: " +
                e;

            return false;
        }
    }

    public static boolean isRunning() {

        if (!LIBRARY_LOADED) {
            return false;
        }

        try {

            return isRunningNative();

        } catch (Throwable e) {

            javaLastError =
                "FunctionFS status check failed: " +
                e;

            return false;
        }
    }

    public static String getLastError() {

        if (
            javaLastError != null &&
            javaLastError.length() > 0
        ) {
            return javaLastError;
        }

        if (!LIBRARY_LOADED) {
            return "libfunctionfs.so is not loaded.";
        }

        return safeNativeError();
    }

    private static String safeNativeError() {

        try {

            String error =
                getLastErrorNative();

            return
                error == null
                ? ""
                : error;

        } catch (Throwable e) {

            return
                "Could not obtain native FunctionFS error: " +
                e;
        }
    }
}
