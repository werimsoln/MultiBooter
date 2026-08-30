package com.werismoln.multibooter;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public final class UsbGadget {

    private static final String PREFS =
        "multibooter_gadget";

    private static final String FUNCTION_NAME =
        "mass_storage.multibooter";

    private static final String LINK_NAME =
        "multibooter_mass_storage";

    private static volatile String lastError =
        "";

    private UsbGadget() {
    }

    public static final class ProbeInfo {

        public final boolean rootGranted;
        public final boolean configFsFound;
        public final boolean massStorageSupported;
        public final String gadgetRoot;
        public final String configPath;
        public final String currentUdc;
        public final String message;

        ProbeInfo(
            boolean rootGranted,
            boolean configFsFound,
            boolean massStorageSupported,
            String gadgetRoot,
            String configPath,
            String currentUdc,
            String message
        ) {

            this.rootGranted =
                rootGranted;

            this.configFsFound =
                configFsFound;

            this.massStorageSupported =
                massStorageSupported;

            this.gadgetRoot =
                safe(gadgetRoot);

            this.configPath =
                safe(configPath);

            this.currentUdc =
                safe(currentUdc);

            this.message =
                safe(message);
        }
    }

    public static final class SessionInfo {

        public final boolean active;
        public final String imagePath;
        public final boolean cdRom;
        public final boolean readOnly;
        public final String gadgetRoot;
        public final String configPath;
        public final String functionPath;
        public final String linkPath;
        public final String originalUdc;
        public final String boundUdc;

        SessionInfo(
            boolean active,
            String imagePath,
            boolean cdRom,
            boolean readOnly,
            String gadgetRoot,
            String configPath,
            String functionPath,
            String linkPath,
            String originalUdc,
            String boundUdc
        ) {

            this.active =
                active;

            this.imagePath =
                safe(imagePath);

            this.cdRom =
                cdRom;

            this.readOnly =
                readOnly;

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

    public static String getLastError() {

        return lastError;
    }

    public static boolean hasSavedActiveSession(
        Context context
    ) {

        return
            getSession(
                context
            ).active;
    }

    public static ProbeInfo probe() {

        lastError =
            "";

        CommandResult root =
            runRoot(
                "id"
            );

        if (
            root.exitCode != 0
        ) {

            lastError =
                "Root access was not granted.";

            return new ProbeInfo(
                false,
                false,
                false,
                "",
                "",
                "",
                lastError
            );
        }

        String script =
            "G=''; C=''; U=''; MS=0; " +
            "for P in /config/usb_gadget/* /sys/kernel/config/usb_gadget/*; do " +
            "  if test -d \"$P\"; then G=\"$P\"; break; fi; " +
            "done; " +
            "if test -z \"$G\"; then echo 'ERR=NO_GADGET'; exit 20; fi; " +
            "for P in \"$G\"/configs/*; do " +
            "  if test -d \"$P\"; then C=\"$P\"; break; fi; " +
            "done; " +
            "if test -z \"$C\"; then echo 'ERR=NO_CONFIG'; exit 21; fi; " +
            "U=$(cat \"$G/UDC\" 2>/dev/null); " +
            "TP=\"$G/functions/mass_storage.multibooter_probe_$$\"; " +
            "if mkdir \"$TP\" 2>/dev/null; then " +
            "  MS=1; rmdir \"$TP\" 2>/dev/null || true; " +
            "elif test -d \"$G/functions/" + FUNCTION_NAME + "\"; then " +
            "  MS=1; " +
            "fi; " +
            "echo \"G=$G\"; " +
            "echo \"C=$C\"; " +
            "echo \"U=$U\"; " +
            "echo \"MS=$MS\";";

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

        boolean massStorage =
            "1".equals(
                valueOf(
                    result.output,
                    "MS="
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

                lastError =
                    "ConfigFS USB gadget was not found.";

            } else if (
                "NO_CONFIG".equals(
                    error
                )
            ) {

                lastError =
                    "USB gadget exists but no ConfigFS configuration was found.";

            } else {

                lastError =
                    "USB gadget probe failed: " +
                    result.output;
            }

            return new ProbeInfo(
                true,
                false,
                false,
                gadget,
                config,
                udc,
                lastError
            );
        }

        String message;

        if (massStorage) {

            message =
                "ConfigFS and Mass Storage function are available.";

        } else {

            message =
                "ConfigFS is available, but the kernel rejected a Mass Storage function.";
        }

        return new ProbeInfo(
            true,
            true,
            massStorage,
            gadget,
            config,
            udc,
            message
        );
    }

    public static boolean enableMassStorage(
        Context context,
        String imagePath,
        boolean asCdRom,
        boolean readOnly
    ) {

        lastError =
            "";

        if (context == null) {

            lastError =
                "context == null";

            return false;
        }

        imagePath =
            safe(imagePath).trim();

        if (
            imagePath.length() == 0
        ) {

            lastError =
                "ISO/IMG path is empty.";

            return false;
        }

        File image;

        try {

            image =
                new File(
                    imagePath
                ).getCanonicalFile();

        } catch (Throwable e) {

            lastError =
                "Could not resolve image path: " +
                e;

            return false;
        }

        if (
            !image.exists() ||
            !image.isFile()
        ) {

            lastError =
                "ISO/IMG file does not exist: " +
                image.getAbsolutePath();

            return false;
        }

        if (
            !image.canRead()
        ) {

            lastError =
                "ISO/IMG is not readable by Android: " +
                image.getAbsolutePath();

            return false;
        }

        ProbeInfo probe =
            probe();

        if (
            !probe.rootGranted ||
            !probe.configFsFound ||
            !probe.massStorageSupported
        ) {

            if (
                lastError.length() == 0
            ) {

                lastError =
                    probe.message;
            }

            return false;
        }

        String gadget =
            probe.gadgetRoot;

        String config =
            probe.configPath;

        String function =
            gadget +
            "/functions/" +
            FUNCTION_NAME;

        String link =
            config +
            "/" +
            LINK_NAME;

        String originalUdc =
            probe.currentUdc;

        boolean effectiveReadOnly =
            asCdRom ||
            readOnly;

        String cdromValue =
            asCdRom
                ? "1"
                : "0";

        String roValue =
            effectiveReadOnly
                ? "1"
                : "0";

        String script =
            "G=" + shell(gadget) + "; " +
            "C=" + shell(config) + "; " +
            "F=" + shell(function) + "; " +
            "L=" + shell(link) + "; " +
            "IMG=" + shell(image.getAbsolutePath()) + "; " +
            "ORIG=$(cat \"$G/UDC\" 2>/dev/null); " +
            "printf '\\n' > \"$G/UDC\" || exit 30; " +
            "if test -L \"$L\"; then rm \"$L\" || exit 31; fi; " +
            "if test ! -d \"$F\"; then mkdir \"$F\" || exit 32; fi; " +
            "if test -e \"$F/lun.0/file\"; then printf '\\n' > \"$F/lun.0/file\" 2>/dev/null || true; fi; " +
            "if test -e \"$F/lun.0/removable\"; then echo 1 > \"$F/lun.0/removable\" || exit 33; fi; " +
            "if test -e \"$F/lun.0/ro\"; then echo " + roValue + " > \"$F/lun.0/ro\" || exit 34; fi; " +
            "if test -e \"$F/lun.0/cdrom\"; then echo " + cdromValue + " > \"$F/lun.0/cdrom\" || exit 35; fi; " +
            "printf '%s\\n' \"$IMG\" > \"$F/lun.0/file\" || exit 36; " +
            "ln -s \"$F\" \"$L\" || exit 37; " +
            "UDC=\"$ORIG\"; " +
            "if test -z \"$UDC\"; then " +
            "  for P in /sys/class/udc/*; do " +
            "    if test -e \"$P\"; then UDC=${P##*/}; break; fi; " +
            "  done; " +
            "fi; " +
            "if test -z \"$UDC\"; then exit 38; fi; " +
            "printf '%s\\n' \"$UDC\" > \"$G/UDC\" || exit 39; " +
            "echo \"BOUND=$UDC\";";

        CommandResult result =
            runRoot(
                script
            );

        if (
            result.exitCode != 0
        ) {

            cleanupFailedEnable(
                gadget,
                function,
                link,
                originalUdc
            );

            lastError =
                explainEnableError(
                    result.exitCode,
                    result.output
                );

            return false;
        }

        String bound =
            valueOf(
                result.output,
                "BOUND="
            );

        if (
            bound.length() == 0
        ) {

            bound =
                originalUdc;
        }

        saveSession(
            context.getApplicationContext(),
            new SessionInfo(
                true,
                image.getAbsolutePath(),
                asCdRom,
                effectiveReadOnly,
                gadget,
                config,
                function,
                link,
                originalUdc,
                bound
            )
        );

        lastError =
            "";

        return true;
    }

    public static boolean disableMassStorage(
        Context context
    ) {

        lastError =
            "";

        if (context == null) {

            lastError =
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

            lastError =
                "";

            return true;
        }

        String script =
            "G=" + shell(session.gadgetRoot) + "; " +
            "F=" + shell(session.functionPath) + "; " +
            "L=" + shell(session.linkPath) + "; " +
            "printf '\\n' > \"$G/UDC\" 2>/dev/null || true; " +
            "if test -e \"$F/lun.0/file\"; then printf '\\n' > \"$F/lun.0/file\" 2>/dev/null || true; fi; " +
            "if test -L \"$L\"; then rm \"$L\" 2>/dev/null || exit 50; fi; " +
            "if test -d \"$F\"; then rmdir \"$F\" 2>/dev/null || exit 51; fi; " +
            "ORIG=" + shell(session.originalUdc) + "; " +
            "if test -n \"$ORIG\"; then printf '%s\\n' \"$ORIG\" > \"$G/UDC\" || exit 52; fi;";

        CommandResult result =
            runRoot(
                script
            );

        if (
            result.exitCode != 0
        ) {

            lastError =
                "Could not restore USB gadget configuration: " +
                result.output +
                " (exit " +
                result.exitCode +
                ")";

            return false;
        }

        clearSession(
            context.getApplicationContext()
        );

        lastError =
            "";

        return true;
    }

    public static boolean isMassStorageActive(
        Context context
    ) {

        if (context == null) {
            return false;
        }

        SessionInfo session =
            getSession(
                context
            );

        if (
            !session.active ||
            session.gadgetRoot.length() == 0 ||
            session.functionPath.length() == 0 ||
            session.linkPath.length() == 0
        ) {

            return false;
        }

        String udcPath =
            session.gadgetRoot +
            "/UDC";

        String lunFile =
            session.functionPath +
            "/lun.0/file";

        CommandResult result =
            runRoot(
                "test -d " +
                shell(session.functionPath) +
                " && test -L " +
                shell(session.linkPath) +
                " && test -n \"$(cat " +
                shell(udcPath) +
                " 2>/dev/null)\" " +
                " && test -n \"$(cat " +
                shell(lunFile) +
                " 2>/dev/null)\""
            );

        return
            result.exitCode ==
            0;
    }

    public static SessionInfo getSession(
        Context context
    ) {

        if (context == null) {

            return new SessionInfo(
                false,
                "",
                false,
                true,
                "",
                "",
                "",
                "",
                "",
                ""
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
                "image",
                ""
            ),
            prefs.getBoolean(
                "cdrom",
                false
            ),
            prefs.getBoolean(
                "readonly",
                true
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
            )
        );
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
                "image",
                session.imagePath
            )
            .putBoolean(
                "cdrom",
                session.cdRom
            )
            .putBoolean(
                "readonly",
                session.readOnly
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

    private static void cleanupFailedEnable(
        String gadget,
        String function,
        String link,
        String originalUdc
    ) {

        String script =
            "G=" + shell(gadget) + "; " +
            "F=" + shell(function) + "; " +
            "L=" + shell(link) + "; " +
            "printf '\\n' > \"$G/UDC\" 2>/dev/null || true; " +
            "if test -e \"$F/lun.0/file\"; then printf '\\n' > \"$F/lun.0/file\" 2>/dev/null || true; fi; " +
            "if test -L \"$L\"; then rm \"$L\" 2>/dev/null || true; fi; " +
            "if test -d \"$F\"; then rmdir \"$F\" 2>/dev/null || true; fi; " +
            "ORIG=" + shell(originalUdc) + "; " +
            "if test -n \"$ORIG\"; then printf '%s\\n' \"$ORIG\" > \"$G/UDC\" 2>/dev/null || true; fi;";

        runRoot(
            script
        );
    }

    private static String explainEnableError(
        int code,
        String output
    ) {

        String detail =
            output.length() == 0
                ? ""
                : ": " + output;

        switch (code) {

            case 30:
                return
                    "Could not unbind the current UDC" +
                    detail;

            case 32:
                return
                    "Kernel rejected the mass_storage ConfigFS function" +
                    detail;

            case 34:
                return
                    "Could not configure LUN read-only state" +
                    detail;

            case 35:
                return
                    "Could not configure CD-ROM mode" +
                    detail;

            case 36:
                return
                    "Kernel could not use the selected image as the gadget backing file" +
                    detail;

            case 37:
                return
                    "Could not link Mass Storage into the active USB configuration" +
                    detail;

            case 38:
                return
                    "No USB Device Controller (UDC) was found" +
                    detail;

            case 39:
                return
                    "Could not bind the gadget to the USB Device Controller" +
                    detail;

            default:
                return
                    "USB Gadget enable failed with exit code " +
                    code +
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

    private static String safe(
        String value
    ) {

        return
            value == null
                ? ""
                : value;
    }
}
