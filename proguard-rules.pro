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