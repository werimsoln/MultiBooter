# ============================================================
# Android Activities
# ============================================================

-keep class com.werismoln.multibooter.MainActivity {
    *;
}

-keep class com.werismoln.multibooter.BootManager {
    *;
}

# ============================================================
# Native / JNI
# ============================================================

-keepclasseswithmembers,allowoptimization,includedescriptorclasses class * {
    native <methods>;
}

# UsbVentoy native callbacks are looked up by NAME from libexfat.so.
# They are not ordinary Java call sites, so R8 must not rename/remove them.
-keepclassmembers class com.werismoln.multibooter.UsbVentoy {
    int writeSectorsFromNative(long, byte[], int);
    int flushFromNative();
}

# ============================================================
# Keep Application class if one exists
# ============================================================

-keep class com.werismoln.multibooter.** extends android.app.Application {
    *;
}

# ============================================================
# Keep custom Views
# ============================================================

-keep class com.werismoln.multibooter.** extends android.view.View {
    *;
}

# ============================================================
# Keep enum values / valueOf
# ============================================================

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
