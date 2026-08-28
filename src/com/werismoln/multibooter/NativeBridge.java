package com.werismoln.multibooter;

public class NativeBridge {

    static {
        System.loadLibrary("gadget");
        System.loadLibrary("tftp");
        System.loadLibrary("scsi");
    }

    public static native String getNativeMessage();
}