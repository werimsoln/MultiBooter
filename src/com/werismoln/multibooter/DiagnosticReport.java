/******************************************************************************
 * DiagnosticReport.java
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

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.PowerManager;

import com.werismoln.multibooter.settings.SettingsManager;

import java.util.Locale;
import java.util.Map;

/**
 * Plain-text diagnostic information. The report never contains USB serial
 * numbers, device names, file names or the full log; text from the log is
 * masked by DebugLog. The user always sees the report before it is shared.
 */
public final class DiagnosticReport {

    private DiagnosticReport() {
    }

    public static String systemInfo(Context context) {

        StringBuilder text = new StringBuilder();

        text.append("App: ").append(appVersion(context)).append('\n');
        text.append("Android: ")
            .append(Build.VERSION.RELEASE)
            .append(" (API ")
            .append(Build.VERSION.SDK_INT)
            .append(")\n");
        text.append("Device: ")
            .append(Build.MANUFACTURER)
            .append(' ')
            .append(Build.MODEL)
            .append('\n');
        text.append("ABI: ")
            .append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
            .append('\n');
        text.append("Kernel: ")
            .append(System.getProperty("os.version"))
            .append('\n');
        text.append("Locale: ").append(Locale.getDefault()).append('\n');

        text.append("Root (last check): ")
            .append(RootHelper.isRootSaved(context) ? "granted" : "not granted")
            .append('\n');

        NotificationManager manager =
            (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        text.append("Notifications: ")
            .append(
                manager != null && manager.areNotificationsEnabled()
                ? "enabled"
                : "disabled"
            )
            .append('\n');

        PowerManager power =
            (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        text.append("Battery optimization exempt: ")
            .append(
                power != null &&
                power.isIgnoringBatteryOptimizations(context.getPackageName())
                ? "yes"
                : "no"
            )
            .append('\n');

        return text.toString();
    }

    public static String usbInfo(Context context) {

        UsbManager manager =
            (UsbManager) context.getSystemService(Context.USB_SERVICE);

        if (manager == null) {
            return "USB host service is not available.\n";
        }

        Map<String, UsbDevice> devices = manager.getDeviceList();

        if (devices.isEmpty()) {
            return "No USB device is connected.\n";
        }

        StringBuilder text = new StringBuilder();

        int number = 1;

        for (UsbDevice device : devices.values()) {

            text.append("Device ").append(number++).append(": ")
                .append(String.format(
                    Locale.US,
                    "%04x:%04x",
                    device.getVendorId(),
                    device.getProductId()
                ))
                .append(" class=").append(device.getDeviceClass())
                .append(" permission=")
                .append(manager.hasPermission(device) ? "granted" : "not granted")
                .append('\n');

            for (int i = 0; i < device.getInterfaceCount(); i++) {

                UsbInterface usbInterface = device.getInterface(i);

                text.append("  interface ").append(i)
                    .append(": class=").append(usbInterface.getInterfaceClass())
                    .append(" subclass=").append(usbInterface.getInterfaceSubclass())
                    .append(" protocol=").append(usbInterface.getInterfaceProtocol())
                    .append(
                        usbInterface.getInterfaceClass() ==
                            UsbConstants.USB_CLASS_MASS_STORAGE
                        ? " (mass storage)"
                        : ""
                    )
                    .append('\n');
            }
        }

        return text.toString();
    }

    public static String settingsInfo(Context context) {

        Map<String, Object> values = SettingsManager.get(context).dump();

        if (values.isEmpty()) {
            return "All settings use their defaults.\n";
        }

        StringBuilder text = new StringBuilder();

        for (Map.Entry<String, Object> entry : values.entrySet()) {

            text.append(entry.getKey())
                .append(" = ")
                .append(DebugLog.mask(String.valueOf(entry.getValue())))
                .append('\n');
        }

        return text.toString();
    }

    public static String build(Context context) {

        StringBuilder text = new StringBuilder();

        text.append("MultiBooter diagnostic report\n");
        text.append("==============================\n\n");

        text.append("[System]\n").append(systemInfo(context)).append('\n');
        text.append("[USB devices]\n").append(usbInfo(context)).append('\n');
        text.append("[Settings that differ from the defaults]\n")
            .append(settingsInfo(context))
            .append('\n');

        text.append("[Last log lines (masked)]\n");

        java.util.List<String> lines = DebugLog.tail(context, 60);

        if (lines.isEmpty()) {
            text.append("(no log; turn on Debug Mode to record one)\n");
        }

        for (String line : lines) {
            text.append(line).append('\n');
        }

        return text.toString();
    }

    private static String appVersion(Context context) {

        try {

            PackageInfo info =
                context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    0
                );

            long code =
                Build.VERSION.SDK_INT >= 28
                ? info.getLongVersionCode()
                : info.versionCode;

            return info.versionName + " (" + code + ")";

        } catch (Exception e) {

            return "unknown";
        }
    }
}
