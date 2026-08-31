# ============================================================
# Android Activities
# ============================================================

-keep class com.werismoln.multibooter.** extends android.app.Activity {
    *;
}

# ============================================================
# Native / JNI
# ============================================================

-keepclasseswithmembers,allowoptimization,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers class com.werismoln.multibooter.UsbVentoy {
    int writeSectorsFromNative(long, byte[], int);
    int flushFromNative();
}

# ============================================================
# Application
# ============================================================

-keep class com.werismoln.multibooter.** extends android.app.Application {
    *;
}

# ============================================================
# Custom Views
# ============================================================

-keep class com.werismoln.multibooter.** extends android.view.View {
    *;
}

# ============================================================
# Enums
# ============================================================

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}