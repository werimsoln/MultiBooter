package com.werismoln.multibooter;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public final class FunctionFileSystem {

    private static final String PREFS =
        "multibooter_functionfs";

    private static final String FFS_MOUNT =
        "/dev/usb-ffs/multiboot";

    private static final String FUNCTION_NAME =
        "ffs.multiboot";

    private static final String LINK_NAME =
        "multibooter_functionfs";

    private static final boolean LIBRARY_LOADED;

    private static volatile String javaLastError =
        "";

    static {

        boolean loaded =
            false;

        try {

            System.loadLibrary(
                "functionfs"
            );

            loaded =
                true;

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

    public static final class ProbeInfo {

        public final boolean libraryLoaded;
        public final boolean rootGranted;
        public final boolean configFsFound;
        public final boolean functionFsSupported;
        public final boolean functionFsMounted;
        public final String gadgetRoot;
        public final String configPath;
        public final String currentUdc;
        public final String message;

        ProbeInfo(
            boolean libraryLoaded,
            boolean rootGranted,
            boolean configFsFound,
            boolean functionFsSupported,
            boolean functionFsMounted,
            String gadgetRoot,
            String configPath,
            String currentUdc,
            String message
        ) {

            this.libraryLoaded =
                libraryLoaded;

            this.rootGranted =
                rootGranted;

            this.configFsFound =
                configFsFound;

            this.functionFsSupported =
                functionFsSupported;

            this.functionFsMounted =
                functionFsMounted;

            this.gadgetRoot =
                safe(gadgetRoot);

            this.configPath =
                safe(configPath);

            this.currentUdc =
                safe(currentUdc);

            this.message =
                safe(message);
        }

        public boolean isReady() {

            return
                libraryLoaded &&
                rootGranted &&
                configFsFound &&
                functionFsSupported;
        }
    }

    public static final class SessionInfo {

        public final boolean active;
        public final String isoPath;
        public final String gadgetRoot;
        public final String configPath;
        public final String functionPath;
        public final String linkPath;
        public final String originalUdc;
        public final String boundUdc;
        public final boolean mountCreatedByUs;

        SessionInfo(
            boolean active,
            String isoPath,
            String gadgetRoot,
            String configPath,
            String functionPath,
            String linkPath,
            String originalUdc,
            String boundUdc,
            boolean mountCreatedByUs
        ) {

            this.active =
                active;

            this.isoPath =
                safe(isoPath);

            this.gadgetRoot =
                safe(gadgetRoot);

            this.configPath =
                safe(configPath);

            this.functionPath =
                safe(functionPath);

            this.linkPath =
                safe(linkPath);

            this.originalUdc =
                safe(originalUdc);

            this.boundUdc =
                safe(boundUdc);

            this.mountCreatedByUs =
                mountCreatedByUs;
        }
    }

    private static final class CommandResult {

        final int exitCode;
        final String output;

        CommandResult(
            int exitCode,
            String output
        ) {

            this.exitCode =
                exitCode;

            this.output =
                output == null
                    ? ""
                    : output.trim();
        }
    }

    public static boolean isLibraryLoaded() {

        return LIBRARY_LOADED;
    }

    public static String getLastError() {

        if (
            javaLastError != null &&
            javaLastError.length() > 0
        ) {

            return javaLastError;
        }

        if (!LIBRARY_LOADED) {

            return
                "libfunctionfs.so is not loaded.";
        }

        return safeNativeError();
    }

    public static ProbeInfo probe() {

        javaLastError =
            "";

        if (!LIBRARY_LOADED) {

            javaLastError =
                "libfunctionfs.so is not loaded.";

            return new ProbeInfo(
                false,
                false,
                false,
                false,
                false,
                "",
                "",
                "",
                javaLastError
            );
        }

        CommandResult root =
            runRoot(
                "id"
            );

        if (
            root.exitCode != 0
        ) {

            javaLastError =
                "Root access was not granted.";

            return new ProbeInfo(
                true,
                false,
                false,
                false,
                false,
                "",
                "",
                "",
                javaLastError
            );
        }

        String script =
            "G=''; C=''; U=''; FFS=0; MNT=0; " +
            "for P in /config/usb_gadget/* /sys/kernel/config/usb_gadget/*; do " +
            "  if test -d \"$P\"; then G=\"$P\"; break; fi; " +
            "done; " +
            "if test -z \"$G\"; then echo 'ERR=NO_GADGET'; exit 20; fi; " +
            "for P in \"$G\"/configs/*; do " +
            "  if test -d \"$P\"; then C=\"$P\"; break; fi; " +
            "done; " +
            "if test -z \"$C\"; then echo 'ERR=NO_CONFIG'; exit 21; fi; " +
            "U=$(cat \"$G/UDC\" 2>/dev/null); " +
            "for P in \"$G\"/functions/ffs.*; do " +
            "  if test -d \"$P\"; then FFS=1; break; fi; " +
            "done; " +
            "if test \"$FFS\" = 0; then " +
            "  TP=\"$G/functions/ffs.multibooter_probe_$$\"; " +
            "  if mkdir \"$TP\" 2>/dev/null; then " +
            "    FFS=1; rmdir \"$TP\" 2>/dev/null || true; " +
            "  fi; " +
            "fi; " +
            "if grep -q ' " + FFS_MOUNT + " functionfs ' /proc/mounts 2>/dev/null; then MNT=1; fi; " +
            "echo \"G=$G\"; " +
            "echo \"C=$C\"; " +
            "echo \"U=$U\"; " +
            "echo \"FFS=$FFS\"; " +
            "echo \"MNT=$MNT\";";

        CommandResult result =
            runRoot(
                script
            );

        String gadget =
            valueOf(
                result.output,
                "G="
            );

        String config =
            valueOf(
                result.output,
                "C="
            );

        String udc =
            valueOf(
                result.output,
                "U="
            );

        boolean ffsSupported =
            "1".equals(
                valueOf(
                    result.output,
                    "FFS="
                )
            );

        boolean mounted =
            "1".equals(
                valueOf(
                    result.output,
                    "MNT="
                )
            );

        if (
            result.exitCode != 0 ||
            gadget.length() == 0 ||
            config.length() == 0
        ) {

            String error =
                valueOf(
                    result.output,
                    "ERR="
                );

            if (
                "NO_GADGET".equals(
                    error
                )
            ) {

                javaLastError =
                    "ConfigFS USB gadget was not found.";

            } else if (
                "NO_CONFIG".equals(
                    error
                )
            ) {

                javaLastError =
                    "USB gadget exists but no ConfigFS configuration was found.";

            } else {

                javaLastError =
                    "FunctionFS probe failed: " +
                    result.output;
            }

            return new ProbeInfo(
                true,
                true,
                false,
                false,
                mounted,
                gadget,
                config,
                udc,
                javaLastError
            );
        }

        String message;

        if (ffsSupported) {

            message =
                "ConfigFS FunctionFS support is available.";

        } else {

            message =
                "ConfigFS exists, but the kernel did not expose a FunctionFS function.";
        }

        return new ProbeInfo(
            true,
            true,
            true,
            ffsSupported,
            mounted,
            gadget,
            config,
            udc,
            message
        );
    }

    public static boolean prepare() {

        if (!LIBRARY_LOADED) {

            javaLastError =
                "libfunctionfs.so is not loaded.";

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

            javaLastError =
                "";

            return true;

        } catch (Throwable e) {

            javaLastError =
                "FunctionFS preparation failed: " +
                e;

            return false;
        }
    }

    public static boolean start(
        Context context,
        String isoPath
    ) {

        javaLastError =
            "";

        if (!LIBRARY_LOADED) {

            javaLastError =
                "libfunctionfs.so is not loaded.";

            return false;
        }

        if (context == null) {

            javaLastError =
                "context == null";

            return false;
        }

        isoPath =
            safe(isoPath).trim();

        File iso;

        try {

            iso =
                new File(
                    isoPath
                ).getCanonicalFile();

        } catch (Throwable e) {

            javaLastError =
                "Could not resolve ISO path: " +
                e;

            return false;
        }

        if (
            !iso.exists() ||
            !iso.isFile() ||
            !iso.canRead()
        ) {

            javaLastError =
                "ISO file is not readable: " +
                iso.getAbsolutePath();

            return false;
        }

        if (
            isRunningNativeSafe()
        ) {

            javaLastError =
                "FunctionFS native backend is already running.";

            return false;
        }

        ProbeInfo probe =
            probe();

        if (!probe.isReady()) {

            if (
                javaLastError.length() == 0
            ) {

                javaLastError =
                    probe.message;
            }

            return false;
        }

        String function =
            probe.gadgetRoot +
            "/functions/" +
            FUNCTION_NAME;

        String link =
            probe.configPath +
            "/" +
            LINK_NAME;

        boolean mountCreatedByUs =
            !probe.functionFsMounted;

        /*
         * FunctionFS ConfigFS integration sequence:
         *
         * 1. Unbind the current UDC.
         * 2. Create ffs.multiboot and link it into the existing config.
         * 3. Mount FunctionFS and let native code write ep0 descriptors.
         * 4. Rebind the UDC. The kernel can now ENABLE the function.
         */
        String createConfig =
            "G=" + shell(probe.gadgetRoot) + "; " +
            "F=" + shell(function) + "; " +
            "L=" + shell(link) + "; " +
            "printf '\\n' > \"$G/UDC\" || exit 30; " +
            "if test -L \"$L\"; then rm \"$L\" || exit 31; fi; " +
            "if test -d \"$F\"; then rmdir \"$F\" 2>/dev/null || exit 32; fi; " +
            "mkdir \"$F\" || exit 33; " +
            "ln -s \"$F\" \"$L\" || exit 34;";

        CommandResult configured =
            runRoot(
                createConfig
            );

        if (
            configured.exitCode != 0
        ) {

            restoreAfterStartFailure(
                probe.gadgetRoot,
                function,
                link,
                probe.currentUdc,
                false
            );

            javaLastError =
                explainConfigError(
                    configured.exitCode,
                    configured.output
                );

            return false;
        }

        if (!prepare()) {

            restoreAfterStartFailure(
                probe.gadgetRoot,
                function,
                link,
                probe.currentUdc,
                mountCreatedByUs
            );

            return false;
        }

        try {

            if (
                !startNative(
                    iso.getAbsolutePath()
                )
            ) {

                javaLastError =
                    safeNativeError();

                restoreAfterStartFailure(
                    probe.gadgetRoot,
                    function,
                    link,
                    probe.currentUdc,
                    mountCreatedByUs
                );

                return false;
            }

        } catch (Throwable e) {

            javaLastError =
                "FunctionFS native start failed: " +
                e;

            restoreAfterStartFailure(
                probe.gadgetRoot,
                function,
                link,
                probe.currentUdc,
                mountCreatedByUs
            );

            return false;
        }

        String udc =
            probe.currentUdc;

        if (
            udc.length() == 0
        ) {

            CommandResult findUdc =
                runRoot(
                    "for P in /sys/class/udc/*; do " +
                    "  if test -e \"$P\"; then echo \"UDC=${P##*/}\"; break; fi; " +
                    "done"
                );

            udc =
                valueOf(
                    findUdc.output,
                    "UDC="
                );
        }

        if (
            udc.length() == 0
        ) {

            stopNativeSafe();

            restoreAfterStartFailure(
                probe.gadgetRoot,
                function,
                link,
                probe.currentUdc,
                mountCreatedByUs
            );

            javaLastError =
                "No USB Device Controller (UDC) was found.";

            return false;
        }

        CommandResult bind =
            runRoot(
                "printf '%s\\n' " +
                shell(udc) +
                " > " +
                shell(
                    probe.gadgetRoot +
                    "/UDC"
                )
            );

        if (
            bind.exitCode != 0
        ) {

            stopNativeSafe();

            restoreAfterStartFailure(
                probe.gadgetRoot,
                function,
                link,
                probe.currentUdc,
                mountCreatedByUs
            );

            javaLastError =
                "Could not bind FunctionFS gadget to UDC " +
                udc +
                ": " +
                bind.output;

            return false;
        }

        saveSession(
            context.getApplicationContext(),
            new SessionInfo(
                true,
                iso.getAbsolutePath(),
                probe.gadgetRoot,
                probe.configPath,
                function,
                link,
                probe.currentUdc,
                udc,
                mountCreatedByUs
            )
        );

        /*
         * Native g_running becomes true before UDC bind. The event thread then
         * waits for FunctionFS BIND/ENABLE and starts the SCSI thread on ENABLE.
         */
        if (
            !isRunningNativeSafe()
        ) {

            javaLastError =
                safeNativeError();

            stop(
                context
            );

            if (
                javaLastError.length() == 0
            ) {

                javaLastError =
                    "FunctionFS native event thread stopped unexpectedly.";
            }

            return false;
        }

        javaLastError =
            "";

        return true;
    }

    /*
     * Compatibility wrapper retained for old callers.
     *
     * This only starts the native backend and does NOT create the ConfigFS
     * ffs.multiboot function. New application code should always call
     * start(Context, String).
     */
    public static boolean start(
        String isoPath
    ) {

        if (!LIBRARY_LOADED) {

            javaLastError =
                "libfunctionfs.so is not loaded.";

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

            javaLastError =
                "";

            return true;

        } catch (Throwable e) {

            javaLastError =
                "FunctionFS native start failed: " +
                e;

            return false;
        }
    }

    public static boolean stop(
        Context context
    ) {

        javaLastError =
            "";

        if (context == null) {

            javaLastError =
                "context == null";

            return false;
        }

        SessionInfo session =
            getSession(
                context
            );

        if (
            !session.active
        ) {

            return stopNativeSafe();
        }

        /*
         * Unbind first. This wakes/tears down FunctionFS endpoints before
         * pthread_join() waits for the native SCSI/event threads.
         */
        CommandResult unbind =
            runRoot(
                "printf '\\n' > " +
                shell(
                    session.gadgetRoot +
                    "/UDC"
                ) +
                " 2>/dev/null || true"
            );

        try {

            Thread.sleep(
                150L
            );

        } catch (InterruptedException ignored) {
        }

        boolean nativeStopped =
            stopNativeSafe();

        String cleanupScript =
            "F=" + shell(session.functionPath) + "; " +
            "L=" + shell(session.linkPath) + "; " +
            "if test -L \"$L\"; then rm \"$L\" 2>/dev/null || exit 50; fi; " +
            "if test -d \"$F\"; then rmdir \"$F\" 2>/dev/null || exit 51; fi;";

        CommandResult cleanup =
            runRoot(
                cleanupScript
            );

        boolean mountClean =
            true;

        String mountError =
            "";

        if (
            session.mountCreatedByUs
        ) {

            CommandResult unmount =
                runRoot(
                    "(umount " +
                    shell(FFS_MOUNT) +
                    " 2>/dev/null || toybox umount " +
                    shell(FFS_MOUNT) +
                    " 2>/dev/null || true); " +
                    "rmdir " +
                    shell(FFS_MOUNT) +
                    " 2>/dev/null || true"
                );

            mountClean =
                unmount.exitCode ==
                0;

            mountError =
                unmount.output;
        }

        boolean restored =
            true;

        String restoreError =
            "";

        if (
            session.originalUdc.length() >
            0
        ) {

            CommandResult restore =
                runRoot(
                    "printf '%s\\n' " +
                    shell(
                        session.originalUdc
                    ) +
                    " > " +
                    shell(
                        session.gadgetRoot +
                        "/UDC"
                    )
                );

            restored =
                restore.exitCode ==
                0;

            restoreError =
                restore.output;
        }

        if (
            nativeStopped &&
            cleanup.exitCode == 0 &&
            mountClean &&
            restored
        ) {

            clearSession(
                context.getApplicationContext()
            );

            javaLastError =
                "";

            return true;
        }

        StringBuilder error =
            new StringBuilder();

        if (!nativeStopped) {

            appendError(
                error,
                "native stop: " +
                safeNativeError()
            );
        }

        if (
            cleanup.exitCode !=
            0
        ) {

            appendError(
                error,
                "ConfigFS cleanup: " +
                cleanup.output +
                " (exit " +
                cleanup.exitCode +
                ")"
            );
        }

        if (!mountClean) {

            appendError(
                error,
                "FunctionFS unmount: " +
                mountError
            );
        }

        if (!restored) {

            appendError(
                error,
                "UDC restore: " +
                restoreError
            );
        }

        javaLastError =
            error.length() == 0
                ? "FunctionFS stop failed."
                : error.toString();

        return false;
    }

    public static boolean stop() {

        return stopNativeSafe();
    }

    public static boolean isRunning() {

        return isRunningNativeSafe();
    }

    public static boolean isActive(
        Context context
    ) {

        if (
            context == null ||
            !isRunningNativeSafe()
        ) {

            return false;
        }

        SessionInfo session =
            getSession(
                context
            );

        if (
            !session.active ||
            session.functionPath.length() == 0 ||
            session.linkPath.length() == 0 ||
            session.gadgetRoot.length() == 0
        ) {

            return false;
        }

        CommandResult state =
            runRoot(
                "test -d " +
                shell(
                    session.functionPath
                ) +
                " && test -L " +
                shell(
                    session.linkPath
                ) +
                " && test -n \"$(cat " +
                shell(
                    session.gadgetRoot +
                    "/UDC"
                ) +
                " 2>/dev/null)\""
            );

        return
            state.exitCode ==
            0;
    }

    public static boolean hasSavedActiveSession(
        Context context
    ) {

        return
            getSession(
                context
            ).active;
    }

    public static SessionInfo getSession(
        Context context
    ) {

        if (context == null) {

            return new SessionInfo(
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                false
            );
        }

        SharedPreferences prefs =
            context.getApplicationContext()
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                );

        return new SessionInfo(
            prefs.getBoolean(
                "active",
                false
            ),
            prefs.getString(
                "iso",
                ""
            ),
            prefs.getString(
                "gadget",
                ""
            ),
            prefs.getString(
                "config",
                ""
            ),
            prefs.getString(
                "function",
                ""
            ),
            prefs.getString(
                "link",
                ""
            ),
            prefs.getString(
                "original_udc",
                ""
            ),
            prefs.getString(
                "bound_udc",
                ""
            ),
            prefs.getBoolean(
                "mount_created",
                false
            )
        );
    }

    public static String getMountPath() {

        return FFS_MOUNT;
    }

    private static void saveSession(
        Context context,
        SessionInfo session
    ) {

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                "active",
                session.active
            )
            .putString(
                "iso",
                session.isoPath
            )
            .putString(
                "gadget",
                session.gadgetRoot
            )
            .putString(
                "config",
                session.configPath
            )
            .putString(
                "function",
                session.functionPath
            )
            .putString(
                "link",
                session.linkPath
            )
            .putString(
                "original_udc",
                session.originalUdc
            )
            .putString(
                "bound_udc",
                session.boundUdc
            )
            .putBoolean(
                "mount_created",
                session.mountCreatedByUs
            )
            .apply();
    }

    private static void clearSession(
        Context context
    ) {

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .clear()
            .apply();
    }

    private static void restoreAfterStartFailure(
        String gadgetRoot,
        String functionPath,
        String linkPath,
        String originalUdc,
        boolean unmount
    ) {

        stopNativeSafe();

        String script =
            "G=" + shell(gadgetRoot) + "; " +
            "F=" + shell(functionPath) + "; " +
            "L=" + shell(linkPath) + "; " +
            "printf '\\n' > \"$G/UDC\" 2>/dev/null || true; " +
            "if test -L \"$L\"; then rm \"$L\" 2>/dev/null || true; fi; " +
            "if test -d \"$F\"; then rmdir \"$F\" 2>/dev/null || true; fi;";

        runRoot(
            script
        );

        if (unmount) {

            runRoot(
                "(umount " +
                shell(FFS_MOUNT) +
                " 2>/dev/null || toybox umount " +
                shell(FFS_MOUNT) +
                " 2>/dev/null || true); " +
                "rmdir " +
                shell(FFS_MOUNT) +
                " 2>/dev/null || true"
            );
        }

        if (
            originalUdc != null &&
            originalUdc.length() > 0
        ) {

            runRoot(
                "printf '%s\\n' " +
                shell(
                    originalUdc
                ) +
                " > " +
                shell(
                    gadgetRoot +
                    "/UDC"
                )
            );
        }
    }

    private static boolean stopNativeSafe() {

        if (!LIBRARY_LOADED) {

            javaLastError =
                "libfunctionfs.so is not loaded.";

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

            return true;

        } catch (Throwable e) {

            javaLastError =
                "FunctionFS native stop failed: " +
                e;

            return false;
        }
    }

    private static boolean isRunningNativeSafe() {

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

    private static String safeNativeError() {

        if (!LIBRARY_LOADED) {

            return
                "libfunctionfs.so is not loaded.";
        }

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

    private static String explainConfigError(
        int exitCode,
        String output
    ) {

        String detail =
            output.length() == 0
                ? ""
                : ": " + output;

        switch (exitCode) {

            case 30:
                return
                    "Could not unbind the current USB Device Controller" +
                    detail;

            case 32:
                return
                    "A stale ffs.multiboot function could not be removed" +
                    detail;

            case 33:
                return
                    "Kernel rejected creation of ffs.multiboot" +
                    detail;

            case 34:
                return
                    "Could not link ffs.multiboot into the active USB configuration" +
                    detail;

            default:
                return
                    "FunctionFS ConfigFS setup failed with exit code " +
                    exitCode +
                    detail;
        }
    }

    private static CommandResult runRoot(
        String command
    ) {

        Process process =
            null;

        BufferedReader reader =
            null;

        try {

            ProcessBuilder builder =
                new ProcessBuilder(
                    "su",
                    "-c",
                    command
                );

            builder.redirectErrorStream(
                true
            );

            process =
                builder.start();

            reader =
                new BufferedReader(
                    new InputStreamReader(
                        process.getInputStream()
                    )
                );

            StringBuilder output =
                new StringBuilder();

            String line;

            while (
                (
                    line =
                        reader.readLine()
                ) != null
            ) {

                if (
                    output.length() <
                    8192
                ) {

                    if (
                        output.length() >
                        0
                    ) {

                        output.append(
                            '\n'
                        );
                    }

                    output.append(
                        line
                    );
                }
            }

            int exit =
                process.waitFor();

            return new CommandResult(
                exit,
                output.toString()
            );

        } catch (Throwable e) {

            return new CommandResult(
                -1,
                e.toString()
            );

        } finally {

            if (reader != null) {

                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }

            if (process != null) {

                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String valueOf(
        String output,
        String prefix
    ) {

        if (
            output == null ||
            prefix == null
        ) {

            return "";
        }

        String[] lines =
            output.split(
                "\\r?\\n"
            );

        for (
            String line :
            lines
        ) {

            if (
                line.startsWith(
                    prefix
                )
            ) {

                return
                    line.substring(
                        prefix.length()
                    ).trim();
            }
        }

        return "";
    }

    private static String shell(
        String value
    ) {

        if (value == null) {
            return "''";
        }

        return
            "'" +
            value.replace(
                "'",
                "'\\''"
            ) +
            "'";
    }

    private static void appendError(
        StringBuilder builder,
        String value
    ) {

        if (
            value == null ||
            value.length() == 0
        ) {

            return;
        }

        if (
            builder.length() >
            0
        ) {

            builder.append(
                "\n"
            );
        }

        builder.append(
            value
        );
    }

    private static String safe(
        String value
    ) {

        return
            value == null
                ? ""
                : value;
    }
}
