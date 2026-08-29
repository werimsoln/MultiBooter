package com.werismoln.multibooter;

public class UsbGadget {

    static {
        System.loadLibrary("gadget");
    }

    public static native boolean enableMassStorageNative(String isoPath, boolean asCdRom);
    public static native boolean disableMassStorageNative();

    public static boolean enableMassStorage(String isoOrImgPath, boolean asCdRom) {
        return enableMassStorageNative(isoOrImgPath, asCdRom);
    }
}