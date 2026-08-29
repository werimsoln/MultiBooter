package com.werismoln.multibooter;

import java.io.File;

public final class UsbGadget {

    private static final boolean LIBRARY_LOADED;

    private static volatile String lastError = "";

    static {

        boolean loaded = false;

        try {
            System.loadLibrary("gadget");
            loaded = true;
        } catch (Throwable e) {
            lastError =
                "libgadget.so could not be loaded: " + e;
        }

        LIBRARY_LOADED = loaded;
    }

    private UsbGadget() {
    }

    public static native boolean enableMassStorageNative(
        String isoPath,
        boolean asCdRom
    );

    public static native boolean disableMassStorageNative();

    public static boolean isLibraryLoaded() {
        return LIBRARY_LOADED;
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean enableMassStorage(
        String isoOrImgPath,
        boolean asCdRom
    ) {

        if (!LIBRARY_LOADED) {
            if (lastError.length() == 0) {
                lastError =
                    "libgadget.so is not loaded.";
            }
            return false;
        }

        if (
            isoOrImgPath == null ||
            isoOrImgPath.trim().length() == 0
        ) {

            lastError =
                "ISO/IMG path is empty.";

            return false;
        }

        File image =
            new File(
                isoOrImgPath
            );

        if (
            !image.exists() ||
            !image.isFile()
        ) {

            lastError =
                "ISO/IMG file does not exist: " +
                isoOrImgPath;

            return false;
        }

        if (!image.canRead()) {

            lastError =
                "ISO/IMG file is not readable: " +
                isoOrImgPath;

            return false;
        }

        try {

            boolean result =
                enableMassStorageNative(
                    image.getAbsolutePath(),
                    asCdRom
                );

            if (!result) {

                lastError =
                    "libgadget.so could not enable mass-storage gadget mode.";

                return false;
            }

            lastError = "";
            return true;

        } catch (Throwable e) {

            lastError =
                "USB Gadget enable failed: " + e;

            return false;
        }
    }

    public static boolean disableMassStorage() {

        if (!LIBRARY_LOADED) {

            if (lastError.length() == 0) {
                lastError =
                    "libgadget.so is not loaded.";
            }

            return false;
        }

        try {

            boolean result =
                disableMassStorageNative();

            if (!result) {

                lastError =
                    "libgadget.so could not disable mass-storage gadget mode.";

                return false;
            }

            lastError = "";
            return true;

        } catch (Throwable e) {

            lastError =
                "USB Gadget disable failed: " + e;

            return false;
        }
    }
}
