/******************************************************************************
 * UsbGadget.java
 *
 * Copyright (c) 2026, Volkan Belek <vlkanblek@gmail.com>
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
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * ConfigFS Mass-Storage helper.
 *
 * Important Samsung/vendor-kernel compatibility rule:
 *   NEVER create a temporary mass_storage.* instance just to probe support,
 *   and NEVER remove the MultiBooter function instance during normal stop.
 *
 * Some Samsung kernels accept the first mass_storage instance after boot,
 * but return ENODEV for every later instance after that first instance is
 * rmdir()'d.  Therefore MultiBooter reuses any existing mass_storage.*
 * instance (for example the vendor's mass_storage.usb0).  If none exists it
 * creates one persistent instance:
 *
 *   functions/mass_storage.multibooter
 *
 * The instance is reused until reboot and is never removed by a normal stop.
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
        public final String massStorageFunction;
        public final String gadgetRoot;
        public final String configPath;
        public final String currentUdc;
        public final String message;

        ProbeInfo(
            boolean rootGranted,
            boolean configFsFound,
            boolean massStorageSupported,
            String massStorageFunction,
            String gadgetRoot,
            String configPath,
            String currentUdc,
            String message
        ) {
            this.rootGranted = rootGranted;
            this.configFsFound = configFsFound;
            this.massStorageSupported = massStorageSupported;
            this.massStorageFunction = safe(massStorageFunction);
            this.gadgetRoot = safe(gadgetRoot);
            this.configPath = safe(configPath);
            this.currentUdc = safe(currentUdc);
            this.message = safe(message);
        }
    }

    public static final class SessionInfo {

        public final boolean active;
        public final String imagePath;
        public final boolean stagedImage;
        public final boolean cdRom;
        public final boolean readOnly;
        public final String gadgetRoot;
        public final String configPath;
        public final String functionPath;
        public final String linkPath;
        public final boolean createdLink;
        public final String originalUdc;
        public final String originalUsbConfig;
        public final String boundUdc;

        SessionInfo(
            boolean active,
            String imagePath,
            boolean stagedImage,
            boolean cdRom,
            boolean readOnly,
            String gadgetRoot,
            String configPath,
            String functionPath,
            String linkPath,
            boolean createdLink,
            String originalUdc,
            String originalUsbConfig,
            String boundUdc
        ) {
            this.active = active;
            this.imagePath = safe(imagePath);
            this.stagedImage = stagedImage;
            this.cdRom = cdRom;
            this.readOnly = readOnly;
            this.gadgetRoot = safe(gadgetRoot);
            this.configPath = safe(configPath);
            this.functionPath = safe(functionPath);
            this.linkPath = safe(linkPath);
            this.createdLink = createdLink;
            this.originalUdc = safe(originalUdc);
            this.originalUsbConfig = safe(originalUsbConfig);
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
                "",
                lastError
            );
        }

        String script =
            "G=''; C=''; U=''; MS='unknown'; MSF=''; SRC='unknown'; " +
            "for P in /config/usb_gadget/* /sys/kernel/config/usb_gadget/*; do " +
            "  if test -d \"$P\"; then G=\"$P\"; break; fi; " +
            "done; " +
            "if test -z \"$G\"; then echo 'ERR=NO_GADGET'; exit 20; fi; " +
            "for P in \"$G\"/configs/*; do " +
            "  if test -d \"$P\"; then C=\"$P\"; break; fi; " +
            "done; " +
            "if test -z \"$C\"; then echo 'ERR=NO_CONFIG'; exit 21; fi; " +
            "U=$(cat \"$G/UDC\" 2>/dev/null); " +

            // Best signal: any usable mass_storage instance already exists.
            // Reuse it instead of creating a second one; some vendor kernels
            // return ENODEV for every instance after the first.
            "for P in \"$G\"/functions/mass_storage.*; do " +
            "  if test -d \"$P\"; then MSF=${P##*/}; break; fi; " +
            "done; " +
            "if test -n \"$MSF\"; then " +
            "  MS=1; SRC='existing-instance'; " +
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
            "echo \"MSF=$MSF\"; " +
            "echo \"SRC=$SRC\";";

        CommandResult result = runRoot(script);

        String gadget = valueOf(result.output, "G=");
        String config = valueOf(result.output, "C=");
        String udc = valueOf(result.output, "U=");
        String massStorageFunction = valueOf(result.output, "MSF=");
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
                massStorageFunction,
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
        } else if ("existing-instance".equals(source)) {
            message =
                "Mass Storage function " + massStorageFunction +
                " is already present and will be reused.";
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
            massStorageFunction,
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
        return enableMassStorageFile(
            context,
            imagePath,
            false,
            asCdRom,
            readOnly
        );
    }

    public static boolean enableMassStorage(
        Context context,
        String imageLabel,
        Uri imageUri,
        boolean asCdRom,
        boolean readOnly
    ) {
        if (imageUri == null) {
            return enableMassStorageFile(
                context,
                imageLabel,
                false,
                asCdRom,
                readOnly
            );
        }

        if (context == null) {
            lastError = "context == null";
            return false;
        }

        if (hasSavedActiveSession(context)) {
            lastError = "Stop the current USB Gadget session before selecting another backing file.";
            return false;
        }

        File staged = copyDocumentToBackingFile(context, imageUri);
        if (staged == null) {
            return false;
        }

        boolean enabled = enableMassStorageFile(
            context,
            staged.getAbsolutePath(),
            true,
            asCdRom,
            readOnly
        );

        if (!enabled) {
            staged.delete();
        }

        return enabled;
    }

    private static boolean enableMassStorageFile(
        Context context,
        String imagePath,
        boolean stagedImage,
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
            image = new File(imagePath).getAbsoluteFile();
        } catch (Throwable e) {
            lastError = "Could not resolve image path: " + e;
            return false;
        }

        /*
         * Android exposes primary storage through FUSE at /storage/emulated/0.
         * The kernel opens the LUN backing file directly, so prefer the
         * underlying /data/media/0 path when the picker returned a FUSE path.
         */
        final String backingPath =
            kernelBackingPath(image.getAbsolutePath());

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

        /*
         * Without all-files access the app process cannot see files picked
         * through the document picker. The kernel opens the backing file as
         * root anyway, so verify it as root instead.
         */
        CommandResult imageCheck = runRoot(
            "test -f " + shell(backingPath) +
            " && test -r " + shell(backingPath)
        );

        if (imageCheck.exitCode != 0) {
            lastError =
                "ISO/IMG file does not exist or is not readable by root: " +
                backingPath;
            return false;
        }

        final String gadget = probe.gadgetRoot;
        final String config = probe.configPath;
        final String functionName =
            probe.massStorageFunction.length() > 0
                ? probe.massStorageFunction
                : FUNCTION_NAME;
        final String function =
            gadget + "/functions/" + functionName;
        final String legacyLink =
            config + "/" + LINK_NAME;
        final String originalUdc = probe.currentUdc;

        final boolean effectiveReadOnly =
            asCdRom || readOnly;

        final String cdromValue = asCdRom ? "1" : "0";
        final String roValue = effectiveReadOnly ? "1" : "0";

        /*
         * Remember Android's current USB composition before touching it.  The
         * marker lets a later Gadget-screen probe recover if this process is
         * killed after sys.usb.config is switched to "none".
         */
        CommandResult usbConfig = runRoot(
            "getprop sys.usb.config 2>/dev/null"
        );

        final String originalUsbConfig =
            safe(usbConfig.output).trim();

        savePendingUsb(
            context.getApplicationContext(),
            gadget,
            originalUdc,
            originalUsbConfig
        );

        /*
         * Stage 1: let Android's USB init release the gadget first.  Writing
         * sys.usb.config=none stops adbd and removes f1..fN links on standard
         * Android init scripts, so Android does not race us for the UDC.
         */
        CommandResult unbind = runRoot(
            "setprop sys.usb.config none 2>/dev/null || true; " +
            "stop adbd 2>/dev/null || true; " +
            "G=" + shell(gadget) + "; " +
            "printf '\\n' > \"$G/UDC\" 2>/dev/null || true; " +
            "test -z \"$(cat \"$G/UDC\" 2>/dev/null)\" || exit 30;"
        );

        if (unbind.exitCode != 0) {
            restoreUsb(
                gadget,
                originalUdc,
                originalUsbConfig
            );
            clearPendingUsb(context.getApplicationContext());
            lastError = explainEnableError(unbind.exitCode, unbind.output);
            return false;
        }

        sleepQuietly(500L);

        /*
         * Stage 2: create once, then reuse forever for this boot.
         *
         * Absolutely no rmdir "$F" here.  Reuse any existing mass_storage.*
         * instance (some vendor kernels only allow the first one) and reuse
         * an existing config link when Android already made one.
         */
        String configure =
            "C=" + shell(config) + "; " +
            "F=" + shell(function) + "; " +
            "L=" + shell(legacyLink) + "; " +
            "IMG=" + shell(backingPath) + "; " +
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
            "LINK=''; " +
            "for P in \"$C\"/*; do " +
            "  if test -L \"$P\"; then " +
            "    T=$(readlink -f \"$P\" 2>/dev/null || true); " +
            "    if test -z \"$T\"; then " +
            "      T=$(readlink \"$P\" 2>/dev/null || true); " +
            "    fi; " +
            "    if test \"$T\" = \"$F\" || " +
            "       test \"${T##*/}\" = \"${F##*/}\"; then " +
            "      LINK=\"$P\"; break; " +
            "    fi; " +
            "  fi; " +
            "done; " +
            "CREATED=0; " +
            "if test -z \"$LINK\"; then " +
            "  N=1; " +
            "  while test -e \"$C/f$N\" || test -L \"$C/f$N\"; do " +
            "    N=$((N+1)); " +
            "  done; " +
            "  LINK=\"$C/f$N\"; " +
            "  ln -s \"$F\" \"$LINK\" || exit 37; " +
            "  CREATED=1; " +
            "fi; " +
            "echo \"LINK=$LINK\"; " +
            "echo \"CREATED=$CREATED\";";

        CommandResult configured = runRoot(configure);

        if (configured.exitCode != 0) {
            cleanupFailedEnable(
                gadget,
                function,
                legacyLink,
                false,
                originalUdc,
                originalUsbConfig
            );
            clearPendingUsb(context.getApplicationContext());
            lastError =
                explainEnableError(
                    configured.exitCode,
                    configured.output
                );
            return false;
        }

        String link = valueOf(configured.output, "LINK=");
        boolean createdLink =
            "1".equals(valueOf(configured.output, "CREATED="));

        if (link.length() == 0) {
            cleanupFailedEnable(
                gadget,
                function,
                legacyLink,
                false,
                originalUdc,
                originalUsbConfig
            );
            clearPendingUsb(context.getApplicationContext());
            lastError =
                "Could not link Mass Storage into the active USB configuration";
            return false;
        }

        sleepQuietly(100L);

        /*
         * Stage 3: bind the controller reported by Android, then fall back to
         * the previous UDC or the first available controller.
         */
        String bindScript =
            "G=" + shell(gadget) + "; " +
            "UDC=$(getprop sys.usb.controller 2>/dev/null); " +
            "if test -z \"$UDC\"; then UDC=" + shell(originalUdc) + "; fi; " +
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
                createdLink,
                originalUdc,
                originalUsbConfig
            );
            clearPendingUsb(context.getApplicationContext());
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
                stagedImage,
                asCdRom,
                effectiveReadOnly,
                gadget,
                config,
                function,
                link,
                createdLink,
                originalUdc,
                originalUsbConfig,
                bound
            )
        );

        clearPendingUsb(context.getApplicationContext());

        lastError = "";
        return true;
    }

    /**
     * Disable the exported medium but KEEP the mass_storage function instance.
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

        boolean removeLink =
            session.createdLink ||
            session.linkPath.endsWith("/" + LINK_NAME);

        String detachScript =
            "F=" + shell(session.functionPath) + "; " +
            "L=" + shell(session.linkPath) + "; " +
            (
                removeLink
                    ? "if test -L \"$L\"; then rm \"$L\" 2>/dev/null || exit 50; fi; "
                    : ""
            ) +
            "if test -e \"$F/lun.0/file\"; then " +
            "  printf '\\n' > \"$F/lun.0/file\" 2>/dev/null || exit 53; " +
            "fi; " +
            // IMPORTANT: no rmdir "$F".
            (
                removeLink
                    ? "test ! -L \"$L\" || exit 54;"
                    : "true;"
            );

        CommandResult detach = runRoot(detachScript);

        sleepQuietly(100L);

        CommandResult restore = restoreUsb(
            session.gadgetRoot,
            session.originalUdc,
            session.originalUsbConfig
        );

        boolean restored = restore.exitCode == 0;
        String restoreOutput = restore.output;
        int restoreCode = restore.exitCode;

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
                    "USB restore: " + restoreOutput +
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
        clearPendingUsb(context.getApplicationContext());

        if (session.stagedImage) {
            File stagedImage = new File(session.imagePath);
            if (stagedImage.exists()) {
                stagedImage.delete();
            }
        }

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
                false,
                true,
                "",
                "",
                "",
                "",
                false,
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
            prefs.getBoolean("staged_image", false),
            prefs.getBoolean("cdrom", false),
            prefs.getBoolean("readonly", true),
            prefs.getString("gadget", ""),
            prefs.getString("config", ""),
            prefs.getString("function", ""),
            prefs.getString("link", ""),
            prefs.getBoolean("created_link", false),
            prefs.getString("original_udc", ""),
            prefs.getString("original_usb_config", ""),
            prefs.getString("bound_udc", "")
        );
    }

    private static void saveSession(Context context, SessionInfo session) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("active", session.active)
            .putString("image", session.imagePath)
            .putBoolean("staged_image", session.stagedImage)
            .putBoolean("cdrom", session.cdRom)
            .putBoolean("readonly", session.readOnly)
            .putString("gadget", session.gadgetRoot)
            .putString("config", session.configPath)
            .putString("function", session.functionPath)
            .putString("link", session.linkPath)
            .putBoolean("created_link", session.createdLink)
            .putString("original_udc", session.originalUdc)
            .putString("original_usb_config", session.originalUsbConfig)
            .putString("bound_udc", session.boundUdc)
            .apply();
    }

    private static void clearSession(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply();
    }

    private static void savePendingUsb(
        Context context,
        String gadget,
        String originalUdc,
        String originalUsbConfig
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pending_restore", true)
            .putLong("pending_time", System.currentTimeMillis())
            .putString("pending_gadget", safe(gadget))
            .putString("pending_udc", safe(originalUdc))
            .putString("pending_usb_config", safe(originalUsbConfig))
            .apply();
    }

    private static void clearPendingUsb(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("pending_restore")
            .remove("pending_time")
            .remove("pending_gadget")
            .remove("pending_udc")
            .remove("pending_usb_config")
            .apply();
    }

    /**
     * Restores USB if a previous START was interrupted before the session was
     * saved.  The Gadget screen calls this before probing so a killed app does
     * not leave Android's USB composition switched to "none".
     */
    public static void restorePendingUsb(Context context) {

        if (context == null) {
            return;
        }

        SharedPreferences prefs =
            context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (!prefs.getBoolean("pending_restore", false)) {
            return;
        }

        long pendingTime =
            prefs.getLong("pending_time", 0L);

        if (
            pendingTime > 0L &&
            System.currentTimeMillis() - pendingTime < 15000L
        ) {
            return;
        }

        String gadget =
            prefs.getString("pending_gadget", "");

        String originalUdc =
            prefs.getString("pending_udc", "");

        String originalUsbConfig =
            prefs.getString("pending_usb_config", "");

        if (gadget.length() > 0) {
            restoreUsb(
                gadget,
                originalUdc,
                originalUsbConfig
            );
        }

        clearPendingUsb(context.getApplicationContext());
    }

    private static File copyDocumentToBackingFile(
        Context context,
        Uri uri
    ) {
        InputStream input = null;
        FileOutputStream output = null;
        File temporary = null;
        File target = null;

        try {
            File directory = context.getExternalFilesDir("backing");
            if (directory == null) {
                directory = new File(context.getFilesDir(), "backing");
            }

            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Could not create app-specific backing directory.");
            }

            String suffix = Long.toString(System.nanoTime());
            temporary = new File(directory, "backing-" + suffix + ".part");
            target = new File(directory, "backing-" + suffix + ".img");

            input = context.getContentResolver().openInputStream(uri);
            if (input == null) {
                throw new IOException("ContentResolver returned no input stream.");
            }

            output = new FileOutputStream(temporary, false);
            byte[] buffer = new byte[65536];
            long copied = 0L;
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                copied += count;
            }

            output.flush();
            output.close();
            output = null;
            input.close();
            input = null;

            if (copied <= 0L) {
                throw new IOException("The selected backing file is empty.");
            }

            if (!temporary.renameTo(target)) {
                throw new IOException("Could not finalize the staged backing file.");
            }

            lastError = "";
            return target;

        } catch (Throwable e) {
            lastError = "Could not copy SAF backing file into app storage: " + e;

            if (temporary != null) {
                temporary.delete();
            }
            if (target != null) {
                target.delete();
            }

            return null;

        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignored) {
                }
            }

            if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignored) {
                }
            }
        }
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
        boolean removeLink,
        String originalUdc,
        String originalUsbConfig
    ) {
        runRoot(
            "G=" + shell(gadget) + "; " +
            "F=" + shell(function) + "; " +
            "L=" + shell(link) + "; " +
            "printf '\\n' > \"$G/UDC\" 2>/dev/null || true; " +
            (
                removeLink
                    ? "if test -L \"$L\"; then rm \"$L\" 2>/dev/null || true; fi; "
                    : ""
            ) +
            "if test -e \"$F/lun.0/file\"; then " +
            "  printf '\\n' > \"$F/lun.0/file\" 2>/dev/null || true; " +
            "fi;"
        );

        sleepQuietly(100L);

        restoreUsb(
            gadget,
            originalUdc,
            originalUsbConfig
        );
    }

    /**
     * Restores the Android USB composition captured before START.
     *
     * sys.usb.config is the property Android init watches: setting it back
     * recreates ffs.adb / mtp links and binds the UDC again.  If init does not
     * bind the controller quickly, fall back to the saved UDC.
     */
    private static CommandResult restoreUsb(
        String gadget,
        String originalUdc,
        String originalUsbConfig
    ) {
        String config =
            safe(originalUsbConfig).trim();

        String script =
            "G=" + shell(gadget) + "; " +
            "UDC=" + shell(originalUdc) + "; " +
            "CFG=" + shell(config) + "; " +
            "if test -n \"$CFG\" && test \"$CFG\" != none; then " +
            "  start adbd 2>/dev/null || true; " +
            "  setprop sys.usb.config \"$CFG\" 2>/dev/null || true; " +
            "  sleep 2; " +
            "fi; " +
            "if test -n \"$UDC\"; then " +
            "  if test -z \"$(cat \"$G/UDC\" 2>/dev/null)\"; then " +
            "    printf '%s\\n' \"$UDC\" > \"$G/UDC\" 2>/dev/null || true; " +
            "  fi; " +
            "fi;";

        return runRoot(script);
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

    /**
     * The kernel opens a ConfigFS LUN backing file directly, bypassing the
     * FUSE /storage view used by Android apps.  Map the primary-storage
     * aliases to the underlying /data/media/<user> path.
     */
    private static String kernelBackingPath(String path) {

        String value =
            safe(path).trim();

        if (value.length() == 0) {
            return value;
        }

        String primary =
            "/storage/emulated/0";

        if (value.equals(primary)) {
            return "/data/media/0";
        }

        if (value.startsWith(primary + "/")) {
            return "/data/media/0" + value.substring(primary.length());
        }

        String[] aliases = {
            "/sdcard",
            "/mnt/sdcard",
            "/storage/self/primary"
        };

        for (String alias : aliases) {

            if (value.equals(alias)) {
                return "/data/media/0";
            }

            if (value.startsWith(alias + "/")) {
                return "/data/media/0" + value.substring(alias.length());
            }
        }

        String emulated =
            "/storage/emulated/";

        if (value.startsWith(emulated)) {

            String rest =
                value.substring(emulated.length());

            int slash =
                rest.indexOf('/');

            String user =
                slash < 0
                    ? rest
                    : rest.substring(0, slash);

            if (user.length() > 0 && isDigits(user)) {
                return
                    "/data/media/" +
                    user +
                    (
                        slash < 0
                            ? ""
                            : rest.substring(slash)
                    );
            }
        }

        return value;
    }

    private static boolean isDigits(String value) {

        if (value.length() == 0) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            if (
                value.charAt(i) < '0' ||
                value.charAt(i) > '9'
            ) {
                return false;
            }
        }

        return true;
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
