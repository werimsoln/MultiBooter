/******************************************************************************
 * UsbGadget.java
 *
 * Copyright (c) 2026, werismoln <vlkanblek@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.werismoln.multibooter;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * ConfigFS Mass-Storage helper.
 *
 * Important Samsung/vendor-kernel compatibility rule:
 *   NEVER create a temporary mass_storage.* instance just to probe support,
 *   and NEVER remove the MultiBooter function instance during normal stop.
 *
 * Some Samsung kernels accept the first mass_storage instance after boot,
 * but return ENODEV for every later instance after that first instance is
 * rmdir()'d.  Therefore MultiBooter owns one persistent instance:
 *
 *   functions/mass_storage.multibooter
 *
 * It is created at the first real START and reused until reboot.
 */
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
            this.rootGranted = rootGranted;
            this.configFsFound = configFsFound;
            this.massStorageSupported = massStorageSupported;
            this.gadgetRoot = safe(gadgetRoot);
            this.configPath = safe(configPath);
            this.currentUdc = safe(currentUdc);
            this.message = safe(message);
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
            this.active = active;
            this.imagePath = safe(imagePath);
            this.cdRom = cdRom;
            this.readOnly = readOnly;
            this.gadgetRoot = safe(gadgetRoot);
            this.configPath = safe(configPath);
            this.functionPath = safe(functionPath);
            this.linkPath = safe(linkPath);
            this.originalUdc = safe(originalUdc);
            this.boundUdc = safe(boundUdc);
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output.trim();
        }
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean hasSavedActiveSession(Context context) {
        return getSession(context).active;
    }

    /**
     * Passive probe only.
     *
     * Do NOT mkdir/rmdir a temporary mass_storage function here.  On the
     * affected Samsung kernel that consumes the only working instance for
     * the current boot and all later mkdir() calls return ENODEV.
     */
    public static ProbeInfo probe() {

        lastError = "";

        CommandResult root = runRoot("id");

        if (root.exitCode != 0) {
            lastError = "Root access was not granted.";
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
            "G=''; C=''; U=''; MS='unknown'; SRC='unknown'; " +
            "for P in /config/usb_gadget/* /sys/kernel/config/usb_gadget/*; do " +
            "  if test -d \"$P\"; then G=\"$P\"; break; fi; " +
            "done; " +
            "if test -z \"$G\"; then echo 'ERR=NO_GADGET'; exit 20; fi; " +
            "for P in \"$G\"/configs/*; do " +
            "  if test -d \"$P\"; then C=\"$P\"; break; fi; " +
            "done; " +
            "if test -z \"$C\"; then echo 'ERR=NO_CONFIG'; exit 21; fi; " +
            "U=$(cat \"$G/UDC\" 2>/dev/null); " +

            // Best signal: our persistent instance already exists.
            "if test -d \"$G/functions/" + FUNCTION_NAME + "\"; then " +
            "  MS=1; SRC='persistent-instance'; " +
            "elif test -r /proc/config.gz; then " +
            // Android devices commonly expose kernel config through /proc/config.gz.
            "  if (zcat /proc/config.gz 2>/dev/null || " +
            "      toybox zcat /proc/config.gz 2>/dev/null || " +
            "      gzip -dc /proc/config.gz 2>/dev/null) " +
            "      | grep -q '^CONFIG_USB_CONFIGFS_MASS_STORAGE=y$'; then " +
            "    MS=1; SRC='kernel-config'; " +
            "  else " +
            "    MS=0; SRC='kernel-config'; " +
            "  fi; " +
            "elif test -d /sys/module/usb_f_mass_storage; then " +
            "  MS=1; SRC='loaded-module'; " +
            "else " +
            // Unknown is intentionally optimistic.  The only definitive runtime
            // test is creating the function, and that must be reserved for START.
            "  MS=1; SRC='not-destructively-tested'; " +
            "fi; " +
            "echo \"G=$G\"; " +
            "echo \"C=$C\"; " +
            "echo \"U=$U\"; " +
            "echo \"MS=$MS\"; " +
            "echo \"SRC=$SRC\";";

        CommandResult result = runRoot(script);

        String gadget = valueOf(result.output, "G=");
        String config = valueOf(result.output, "C=");
        String udc = valueOf(result.output, "U=");
        String source = valueOf(result.output, "SRC=");

        boolean massStorage =
            "1".equals(valueOf(result.output, "MS="));

        if (
            result.exitCode != 0 ||
            gadget.length() == 0 ||
            config.length() == 0
        ) {
            String error = valueOf(result.output, "ERR=");

            if ("NO_GADGET".equals(error)) {
                lastError = "ConfigFS USB gadget was not found.";
            } else if ("NO_CONFIG".equals(error)) {
                lastError =
                    "USB gadget exists but no ConfigFS configuration was found.";
            } else {
                lastError =
                    "USB gadget probe failed: " + result.output;
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

        if (!massStorage) {
            message =
                "Kernel config reports ConfigFS Mass Storage as disabled.";
        } else if ("persistent-instance".equals(source)) {
            message =
                "Mass Storage persistent instance is ready and will be reused.";
        } else if ("kernel-config".equals(source)) {
            message =
                "Kernel config reports ConfigFS Mass Storage support. " +
                "Probe did not create/delete a temporary function.";
        } else if ("loaded-module".equals(source)) {
            message =
                "usb_f_mass_storage is loaded. " +
                "Probe did not create/delete a temporary function.";
        } else {
            message =
                "ConfigFS is available. Mass Storage creation is intentionally " +
                "deferred until START to avoid vendor-kernel one-shot instance bugs.";
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

        lastError = "";

        if (context == null) {
            lastError = "context == null";
            return false;
        }

        imagePath = safe(imagePath).trim();

        if (imagePath.length() == 0) {
            lastError = "ISO/IMG path is empty.";
            return false;
        }

        final File image;

        try {
            image = new File(imagePath).getCanonicalFile();
        } catch (Throwable e) {
            lastError = "Could not resolve image path: " + e;
            return false;
        }

        if (!image.exists() || !image.isFile()) {
            lastError =
                "ISO/IMG file does not exist: " + image.getAbsolutePath();
            return false;
        }

        if (!image.canRead()) {
            lastError =
                "ISO/IMG is not readable by Android: " + image.getAbsolutePath();
            return false;
        }

        ProbeInfo probe = probe();

        if (
            !probe.rootGranted ||
            !probe.configFsFound ||
            !probe.massStorageSupported
        ) {
            if (lastError.length() == 0) {
                lastError = probe.message;
            }
            return false;
        }

        final String gadget = probe.gadgetRoot;
        final String config = probe.configPath;
        final String function =
            gadget + "/functions/" + FUNCTION_NAME;
        final String link =
            config + "/" + LINK_NAME;
        final String originalUdc = probe.currentUdc;

        final boolean effectiveReadOnly =
            asCdRom || readOnly;

        final String cdromValue = asCdRom ? "1" : "0";
        final String roValue = effectiveReadOnly ? "1" : "0";

        /*
         * Stage 1: disconnect first and give Samsung/vendor UDC code time to
         * finish the old configuration teardown before touching the LUN.
         */
        CommandResult unbind = runRoot(
            "G=" + shell(gadget) + "; " +
            "printf '\\n' > \"$G/UDC\" 2>/dev/null || exit 30;"
        );

        if (unbind.exitCode != 0) {
            lastError = explainEnableError(unbind.exitCode, unbind.output);
            return false;
        }

        sleepQuietly(250L);

        /*
         * Stage 2: create once, then reuse forever for this boot.
         *
         * Absolutely no rmdir "$F" here.
         */
        String configure =
            "F=" + shell(function) + "; " +
            "L=" + shell(link) + "; " +
            "IMG=" + shell(image.getAbsolutePath()) + "; " +
            "if test -L \"$L\"; then rm \"$L\" || exit 31; fi; " +
            "if test ! -d \"$F\"; then " +
            "  mkdir \"$F\" || exit 32; " +
            "fi; " +
            "if test ! -e \"$F/lun.0/file\"; then exit 40; fi; " +
            // Detach old medium before changing LUN personality.
            "printf '\\n' > \"$F/lun.0/file\" 2>/dev/null || true; " +
            "if test -e \"$F/lun.0/removable\"; then " +
            "  echo 1 > \"$F/lun.0/removable\" || exit 33; " +
            "fi; " +
            "if test -e \"$F/lun.0/ro\"; then " +
            "  echo " + roValue + " > \"$F/lun.0/ro\" || exit 34; " +
            "fi; " +
            "if test -e \"$F/lun.0/cdrom\"; then " +
            "  echo " + cdromValue + " > \"$F/lun.0/cdrom\" || exit 35; " +
            "fi; " +
            "printf '%s\\n' \"$IMG\" > \"$F/lun.0/file\" || exit 36; " +
            "ln -s \"$F\" \"$L\" || exit 37;";

        CommandResult configured = runRoot(configure);

        if (configured.exitCode != 0) {
            cleanupFailedEnable(
                gadget,
                function,
                link,
                originalUdc
            );
            lastError =
                explainEnableError(
                    configured.exitCode,
                    configured.output
                );
            return false;
        }

        sleepQuietly(100L);

        /*
         * Stage 3: bind the previous UDC, or the first available controller
         * when Android had left UDC empty before START.
         */
        String bindScript =
            "G=" + shell(gadget) + "; " +
            "UDC=" + shell(originalUdc) + "; " +
            "if test -z \"$UDC\"; then " +
            "  for P in /sys/class/udc/*; do " +
            "    if test -e \"$P\"; then UDC=${P##*/}; break; fi; " +
            "  done; " +
            "fi; " +
            "if test -z \"$UDC\"; then exit 38; fi; " +
            "printf '%s\\n' \"$UDC\" > \"$G/UDC\" || exit 39; " +
            "echo \"BOUND=$UDC\";";

        CommandResult boundResult = runRoot(bindScript);

        if (boundResult.exitCode != 0) {
            cleanupFailedEnable(
                gadget,
                function,
                link,
                originalUdc
            );
            lastError =
                explainEnableError(
                    boundResult.exitCode,
                    boundResult.output
                );
            return false;
        }

        String bound = valueOf(boundResult.output, "BOUND=");

        if (bound.length() == 0) {
            bound = originalUdc;
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

        lastError = "";
        return true;
    }

    /**
     * Disable the exported medium but KEEP functions/mass_storage.multibooter.
     *
     * The function directory is intentionally persistent until reboot.  This
     * avoids the Samsung ENODEV-after-rmdir bug confirmed on the target device.
     */
    public static boolean disableMassStorage(Context context) {

        lastError = "";

        if (context == null) {
            lastError = "context == null";
            return false;
        }

        SessionInfo session = getSession(context);

        if (!session.active) {
            return true;
        }

        CommandResult unbind = runRoot(
            "G=" + shell(session.gadgetRoot) + "; " +
            "printf '\\n' > \"$G/UDC\" 2>/dev/null || true;"
        );

        // Give the host disconnect and UDC teardown time to settle.
        sleepQuietly(250L);

        String detachScript =
            "F=" + shell(session.functionPath) + "; " +
            "L=" + shell(session.linkPath) + "; " +
            "if test -L \"$L\"; then rm \"$L\" 2>/dev/null || exit 50; fi; " +
            "if test -e \"$F/lun.0/file\"; then " +
            "  printf '\\n' > \"$F/lun.0/file\" 2>/dev/null || exit 53; " +
            "fi; " +
            // IMPORTANT: no rmdir "$F".
            "test ! -L \"$L\" || exit 54;";

        CommandResult detach = runRoot(detachScript);

        sleepQuietly(100L);

        boolean restored = true;
        String restoreOutput = "";
        int restoreCode = 0;

        if (session.originalUdc.length() > 0) {
            CommandResult restore = runRoot(
                "G=" + shell(session.gadgetRoot) + "; " +
                "printf '%s\\n' " +
                shell(session.originalUdc) +
                " > \"$G/UDC\""
            );

            restored = restore.exitCode == 0;
            restoreOutput = restore.output;
            restoreCode = restore.exitCode;
        }

        if (unbind.exitCode != 0 || detach.exitCode != 0 || !restored) {
            StringBuilder error = new StringBuilder();

            if (unbind.exitCode != 0) {
                appendError(
                    error,
                    "UDC unbind: " + unbind.output +
                    " (exit " + unbind.exitCode + ")"
                );
            }

            if (detach.exitCode != 0) {
                appendError(
                    error,
                    "Mass Storage detach: " + detach.output +
                    " (exit " + detach.exitCode + ")"
                );
            }

            if (!restored) {
                appendError(
                    error,
                    "UDC restore: " + restoreOutput +
                    " (exit " + restoreCode + ")"
                );
            }

            lastError =
                error.length() == 0
                    ? "Could not restore USB gadget configuration."
                    : error.toString();

            return false;
        }

        clearSession(context.getApplicationContext());
        lastError = "";
        return true;
    }

    public static boolean isMassStorageActive(Context context) {

        if (context == null) {
            return false;
        }

        SessionInfo session = getSession(context);

        if (
            !session.active ||
            session.gadgetRoot.length() == 0 ||
            session.functionPath.length() == 0 ||
            session.linkPath.length() == 0
        ) {
            return false;
        }

        String udcPath = session.gadgetRoot + "/UDC";
        String lunFile = session.functionPath + "/lun.0/file";

        CommandResult result = runRoot(
            "test -d " + shell(session.functionPath) +
            " && test -L " + shell(session.linkPath) +
            " && test -n \"$(cat " + shell(udcPath) +
            " 2>/dev/null)\"" +
            " && test -n \"$(cat " + shell(lunFile) +
            " 2>/dev/null)\""
        );

        return result.exitCode == 0;
    }

    public static SessionInfo getSession(Context context) {

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
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        return new SessionInfo(
            prefs.getBoolean("active", false),
            prefs.getString("image", ""),
            prefs.getBoolean("cdrom", false),
            prefs.getBoolean("readonly", true),
            prefs.getString("gadget", ""),
            prefs.getString("config", ""),
            prefs.getString("function", ""),
            prefs.getString("link", ""),
            prefs.getString("original_udc", ""),
            prefs.getString("bound_udc", "")
        );
    }

    private static void saveSession(Context context, SessionInfo session) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("active", session.active)
            .putString("image", session.imagePath)
            .putBoolean("cdrom", session.cdRom)
            .putBoolean("readonly", session.readOnly)
            .putString("gadget", session.gadgetRoot)
            .putString("config", session.configPath)
            .putString("function", session.functionPath)
            .putString("link", session.linkPath)
            .putString("original_udc", session.originalUdc)
            .putString("bound_udc", session.boundUdc)
            .apply();
    }

    private static void clearSession(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply();
    }

    /**
     * Failure cleanup also keeps the function instance alive.  If mkdir(F)
     * succeeded once, deleting it could make the current boot unrecoverable
     * on the affected Samsung kernel.
     */
    private static void cleanupFailedEnable(
        String gadget,
        String function,
        String link,
        String originalUdc
    ) {
        runRoot(
            "G=" + shell(gadget) + "; " +
            "F=" + shell(function) + "; " +
            "L=" + shell(link) + "; " +
            "printf '\\n' > \"$G/UDC\" 2>/dev/null || true; " +
            "if test -L \"$L\"; then rm \"$L\" 2>/dev/null || true; fi; " +
            "if test -e \"$F/lun.0/file\"; then " +
            "  printf '\\n' > \"$F/lun.0/file\" 2>/dev/null || true; " +
            "fi;"
        );

        sleepQuietly(100L);

        if (safe(originalUdc).length() > 0) {
            runRoot(
                "G=" + shell(gadget) + "; " +
                "printf '%s\\n' " + shell(originalUdc) +
                " > \"$G/UDC\" 2>/dev/null || true;"
            );
        }
    }

    private static String explainEnableError(int code, String output) {

        String detail =
            output == null || output.length() == 0
                ? ""
                : ": " + output;

        switch (code) {
            case 30:
                return "Could not unbind the current UDC" + detail;
            case 31:
                return "Could not remove the previous MultiBooter config link" + detail;
            case 32:
                return
                    "Kernel rejected creation of the persistent mass_storage function" +
                    detail;
            case 33:
                return "Could not configure LUN removable state" + detail;
            case 34:
                return "Could not configure LUN read-only state" + detail;
            case 35:
                return "Could not configure CD-ROM mode" + detail;
            case 36:
                return
                    "Kernel could not use the selected image as the gadget backing file" +
                    detail;
            case 37:
                return
                    "Could not link Mass Storage into the active USB configuration" +
                    detail;
            case 38:
                return "No USB Device Controller (UDC) was found" + detail;
            case 39:
                return "Could not bind the gadget to the USB Device Controller" + detail;
            case 40:
                return
                    "mass_storage function exists but lun.0/file is missing" +
                    detail;
            default:
                return
                    "USB Gadget enable failed with exit code " + code + detail;
        }
    }

    private static CommandResult runRoot(String command) {

        Process process = null;
        BufferedReader reader = null;

        try {
            ProcessBuilder builder =
                new ProcessBuilder("su", "-c", command);

            builder.redirectErrorStream(true);
            process = builder.start();

            reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            StringBuilder output = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (output.length() < 16384) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }

            int exit = process.waitFor();
            return new CommandResult(exit, output.toString());

        } catch (Throwable e) {
            return new CommandResult(-1, e.toString());

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

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void appendError(StringBuilder builder, String value) {
        if (builder.length() > 0) {
            builder.append("; ");
        }
        builder.append(value);
    }

    private static String valueOf(String output, String prefix) {

        if (output == null || prefix == null) {
            return "";
        }

        String[] lines = output.split("\\r?\\n");

        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }

        return "";
    }

    private static String shell(String value) {
        if (value == null) {
            return "''";
        }

        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
