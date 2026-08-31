/******************************************************************************
 * TftpMode.java
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
import android.os.Build;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.IOException;

public final class TftpMode {

    private static final String PREFS = "multibooter_tftp";
    private static final String WORK_DIR = "tftp";
    private static final String DNSMASQ_FILE = "dnsmasq";
    private static final String CONFIG_FILE = "dnsmasq.conf";
    private static final String PID_FILE = "dnsmasq.pid";
    private static final String LOG_FILE = "dnsmasq.log";
    private static final String LEASE_FILE = "dnsmasq.leases";

    private static volatile String lastError = "";

    private TftpMode() {}

    public static final class SessionInfo {
        public final String interfaceName;
        public final String tftpRootPath;
        public final String bootFile;
        public final String serverIp;
        public final String dhcpStart;
        public final String dhcpEnd;
        public final String dnsmasqAbi;

        SessionInfo(
            String interfaceName,
            String tftpRootPath,
            String bootFile,
            String serverIp,
            String dhcpStart,
            String dhcpEnd,
            String dnsmasqAbi
        ) {
            this.interfaceName = safe(interfaceName);
            this.tftpRootPath = safe(tftpRootPath);
            this.bootFile = safe(bootFile);
            this.serverIp = safe(serverIp);
            this.dhcpStart = safe(dhcpStart);
            this.dhcpEnd = safe(dhcpEnd);
            this.dnsmasqAbi = safe(dnsmasqAbi);
        }
    }

    private static final class BinaryChoice {
        final String abi;
        final String asset;

        BinaryChoice(String abi, String asset) {
            this.abi = abi;
            this.asset = asset;
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    public static String getLastError() {
        return lastError;
    }

    public static String getSelectedDnsmasqAbi() {
        BinaryChoice c = chooseBinary();
        return c == null ? "unsupported" : c.abi;
    }

    public static String getSelectedDnsmasqAsset() {
        BinaryChoice c = chooseBinary();
        return c == null ? "" : c.asset;
    }

    public static boolean hasRootAccess() {
        Process p = null;
        DataOutputStream out = null;

        try {
            p = Runtime.getRuntime().exec("su");
            out = new DataOutputStream(p.getOutputStream());
            out.writeBytes("id\n");
            out.writeBytes("exit\n");
            out.flush();

            if (p.waitFor() != 0) {
                lastError = "Root access was not granted.";
                return false;
            }

            lastError = "";
            return true;
        } catch (Throwable e) {
            lastError = "Root check failed: " + e;
            return false;
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    public static boolean startPxe(
        Context context,
        String interfaceName,
        String tftpRootPath,
        String bootFile,
        String serverIp,
        String dhcpStart,
        String dhcpEnd
    ) {
        lastError = "";

        if (context == null) {
            lastError = "context == null";
            return false;
        }

        interfaceName = safe(interfaceName).trim();
        tftpRootPath = safe(tftpRootPath).trim();
        bootFile = safe(bootFile).trim();
        serverIp = safe(serverIp).trim();
        dhcpStart = safe(dhcpStart).trim();
        dhcpEnd = safe(dhcpEnd).trim();

        if (!safeInterface(interfaceName)) {
            lastError = "Invalid network interface name.";
            return false;
        }

        if (!ipv4(serverIp) || !ipv4(dhcpStart) || !ipv4(dhcpEnd)) {
            lastError = "Server and DHCP addresses must be IPv4 addresses.";
            return false;
        }

        if (!same24(serverIp, dhcpStart) || !same24(serverIp, dhcpEnd)) {
            lastError = "Server IP and DHCP range must be in the same /24 subnet.";
            return false;
        }

        if (ipToLong(dhcpStart) > ipToLong(dhcpEnd)) {
            lastError = "DHCP start address is greater than DHCP end address.";
            return false;
        }

        if (!safeBootFile(bootFile)) {
            lastError = "Boot file must be a relative path inside the TFTP root.";
            return false;
        }

        File root;
        File boot;

        try {
            root = new File(tftpRootPath).getCanonicalFile();
            boot = new File(root, bootFile).getCanonicalFile();
        } catch (IOException e) {
            lastError = "Could not resolve TFTP paths: " + e;
            return false;
        }

        if (!root.isDirectory()) {
            lastError = "TFTP root does not exist: " + root;
            return false;
        }

        String prefix = root.getAbsolutePath();
        if (!prefix.endsWith(File.separator)) prefix += File.separator;

        if (!boot.getAbsolutePath().startsWith(prefix)) {
            lastError = "Boot file escapes the TFTP root.";
            return false;
        }

        if (!boot.isFile() || !boot.canRead()) {
            lastError = "Boot file does not exist or is not readable: " + boot;
            return false;
        }

        Context app = context.getApplicationContext();
        File work = getWorkDir(app);

        if (!work.exists() && !work.mkdirs()) {
            lastError = "Could not create TFTP work directory.";
            return false;
        }

        BinaryChoice choice = chooseBinary();
        if (choice == null) {
            lastError = "Unsupported Android CPU architecture.";
            return false;
        }

        File binary = extractBinary(app, work, choice);
        if (binary == null) return false;

        File config = new File(work, CONFIG_FILE);
        File pid = new File(work, PID_FILE);
        File log = new File(work, LOG_FILE);
        File leases = new File(work, LEASE_FILE);

        try {
            touch(log);
            touch(leases);
            writeConfig(
                config, pid, log, leases,
                interfaceName, root, bootFile,
                serverIp, dhcpStart, dhcpEnd
            );
        } catch (Throwable e) {
            lastError = "Could not create dnsmasq configuration: " + e;
            return false;
        }

        CommandResult test = runRoot(
            shell(binary.getAbsolutePath()) +
            " --test --conf-file=" +
            shell(config.getAbsolutePath()) +
            " 2>&1"
        );

        if (test.exitCode != 0) {
            lastError = "dnsmasq configuration test failed: " + test.output;
            return false;
        }

        String cidr = serverIp + "/24";

        String startCommand =
            "(ip link set " + interfaceName + " up 2>/dev/null || " +
            "toybox ip link set " + interfaceName + " up 2>/dev/null) && " +
            "(ip addr replace " + shell(cidr) + " dev " + interfaceName + " 2>/dev/null || " +
            "toybox ip addr replace " + shell(cidr) + " dev " + interfaceName + " 2>/dev/null) && " +
            "rm -f " + shell(pid.getAbsolutePath()) + " && " +
            shell(binary.getAbsolutePath()) +
            " --conf-file=" + shell(config.getAbsolutePath()) +
            " 2>&1";

        CommandResult started = runRoot(startCommand);

        if (started.exitCode != 0) {
            lastError = "dnsmasq start failed: " + started.output;
            return false;
        }

        try { Thread.sleep(350L); } catch (InterruptedException ignored) {}

        if (!isPxeRunning(app)) {
            lastError = "dnsmasq returned success but no running PID was found.";
            return false;
        }

        saveSession(
            app,
            new SessionInfo(
                interfaceName,
                root.getAbsolutePath(),
                bootFile,
                serverIp,
                dhcpStart,
                dhcpEnd,
                choice.abi
            )
        );

        lastError = "";
        return true;
    }

    public static boolean stopPxe(Context context) {
        if (context == null) {
            lastError = "context == null";
            return false;
        }

        Context app = context.getApplicationContext();
        File pid = new File(getWorkDir(app), PID_FILE);
        SessionInfo s = getLastSession(app);

        StringBuilder cmd = new StringBuilder();
        cmd.append("if test -s ").append(shell(pid.getAbsolutePath())).append("; then ");
        cmd.append("P=$(cat ").append(shell(pid.getAbsolutePath())).append(" 2>/dev/null); ");
        cmd.append("if test -n \"$P\"; then kill \"$P\" 2>/dev/null || true; fi; ");
        cmd.append("fi; rm -f ").append(shell(pid.getAbsolutePath())).append("; ");

        if (safeInterface(s.interfaceName) && ipv4(s.serverIp)) {
            String cidr = s.serverIp + "/24";
            cmd.append("(ip addr del ").append(shell(cidr)).append(" dev ")
               .append(s.interfaceName)
               .append(" 2>/dev/null || toybox ip addr del ")
               .append(shell(cidr)).append(" dev ").append(s.interfaceName)
               .append(" 2>/dev/null || true)");
        } else {
            cmd.append("true");
        }

        CommandResult r = runRoot(cmd.toString());

        if (r.exitCode != 0) {
            lastError = "Could not stop MultiBooter dnsmasq: " + r.output;
            return false;
        }

        lastError = "";
        return true;
    }

    public static boolean isPxeRunning(Context context) {
        if (context == null) return false;

        File pid = new File(
            getWorkDir(context.getApplicationContext()),
            PID_FILE
        );

        CommandResult r = runRoot(
            "test -s " + shell(pid.getAbsolutePath()) +
            " && P=$(cat " + shell(pid.getAbsolutePath()) + " 2>/dev/null)" +
            " && test -n \"$P\" && kill -0 \"$P\" 2>/dev/null"
        );

        return r.exitCode == 0;
    }

    public static SessionInfo getLastSession(Context context) {
        if (context == null) {
            return new SessionInfo("", "", "", "", "", "", "");
        }

        SharedPreferences p = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        return new SessionInfo(
            p.getString("iface", ""),
            p.getString("root", ""),
            p.getString("boot", ""),
            p.getString("server", ""),
            p.getString("dhcp_start", ""),
            p.getString("dhcp_end", ""),
            p.getString("abi", "")
        );
    }

    public static String getLogPath(Context context) {
        if (context == null) return "";
        return new File(getWorkDir(context.getApplicationContext()), LOG_FILE)
            .getAbsolutePath();
    }

    private static void saveSession(Context c, SessionInfo s) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("iface", s.interfaceName)
            .putString("root", s.tftpRootPath)
            .putString("boot", s.bootFile)
            .putString("server", s.serverIp)
            .putString("dhcp_start", s.dhcpStart)
            .putString("dhcp_end", s.dhcpEnd)
            .putString("abi", s.dnsmasqAbi)
            .apply();
    }

    private static File getWorkDir(Context c) {
        return new File(c.getNoBackupFilesDir(), WORK_DIR);
    }

    private static BinaryChoice chooseBinary() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null) return null;

        for (String abi : abis) {
            if ("arm64-v8a".equals(abi))
                return new BinaryChoice(abi, "dnsmasq-arm64-v8a");
            if ("armeabi-v7a".equals(abi))
                return new BinaryChoice(abi, "dnsmasq-armeabi-v7a");
            if ("x86_64".equals(abi))
                return new BinaryChoice(abi, "dnsmasq-x86_64");
            if ("x86".equals(abi))
                return new BinaryChoice(abi, "dnsmasq-x86");
        }
        return null;
    }

    private static File extractBinary(
        Context context,
        File work,
        BinaryChoice choice
    ) {
        File outFile = new File(work, DNSMASQ_FILE);
        InputStream in = null;
        FileOutputStream out = null;

        try {
            in = context.getAssets().open(choice.asset);
            out = new FileOutputStream(outFile, false);

            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.flush();
        } catch (Throwable e) {
            lastError = "Could not extract " + choice.asset + ": " + e;
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }

        if (outFile.length() <= 0) {
            lastError = "Extracted dnsmasq is empty.";
            return null;
        }

        outFile.setReadable(true, true);
        outFile.setWritable(true, true);
        outFile.setExecutable(true, true);

        CommandResult chmod = runRoot(
            "chmod 700 " + shell(outFile.getAbsolutePath())
        );

        if (chmod.exitCode != 0) {
            lastError = "Could not chmod dnsmasq: " + chmod.output;
            return null;
        }

        return outFile;
    }

    private static void writeConfig(
        File config,
        File pid,
        File log,
        File leases,
        String iface,
        File root,
        String bootFile,
        String serverIp,
        String dhcpStart,
        String dhcpEnd
    ) throws IOException {
        rejectConfigValue(root.getAbsolutePath());
        rejectConfigValue(bootFile);

        BufferedWriter w = null;
        try {
            w = new BufferedWriter(
                new OutputStreamWriter(
                    new FileOutputStream(config, false),
                    "UTF-8"
                )
            );

            line(w, "port=0");
            line(w, "user=root");
            line(w, "group=root");
            line(w, "interface=" + iface);
            line(w, "bind-interfaces");
            line(w, "dhcp-authoritative");
            line(w, "dhcp-range=" + dhcpStart + "," + dhcpEnd + ",255.255.255.0,12h");
            line(w, "dhcp-option=3," + serverIp);
            line(w, "enable-tftp");
            line(w, "tftp-root=" + root.getAbsolutePath());
            line(w, "dhcp-boot=" + bootFile);
            line(w, "log-dhcp");
            line(w, "log-facility=" + log.getAbsolutePath());
            line(w, "pid-file=" + pid.getAbsolutePath());
            line(w, "dhcp-leasefile=" + leases.getAbsolutePath());
            w.flush();
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }

    private static void line(BufferedWriter w, String s) throws IOException {
        w.write(s);
        w.newLine();
    }

    private static void touch(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IOException("Could not create " + parent);

        if (!f.exists() && !f.createNewFile())
            throw new IOException("Could not create " + f);

        f.setReadable(true, false);
        f.setWritable(true, false);
    }

    private static CommandResult runRoot(String command) {
        Process p = null;
        BufferedReader reader = null;

        try {
            p = Runtime.getRuntime().exec(
                new String[] { "su", "-c", command }
            );

            reader = new BufferedReader(
                new InputStreamReader(p.getInputStream())
            );

            StringBuilder text = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (text.length() < 4096) {
                    if (text.length() > 0) text.append('\n');
                    text.append(line);
                }
            }

            return new CommandResult(p.waitFor(), text.toString());
        } catch (Throwable e) {
            return new CommandResult(-1, e.toString());
        } finally {
            if (reader != null) try { reader.close(); } catch (Throwable ignored) {}
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static boolean safeInterface(String s) {
        if (s == null || s.length() == 0 || s.length() > 64) return false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok =
                (c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') ||
                c == '_' || c == '-' || c == '.' || c == ':';

            if (!ok) return false;
        }
        return true;
    }

    private static boolean safeBootFile(String s) {
        if (s == null || s.length() == 0 ||
            s.startsWith("/") || s.startsWith("\\") ||
            s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 ||
            s.indexOf(',') >= 0) {
            return false;
        }

        String[] parts = s.replace('\\', '/').split("/");
        for (String part : parts) {
            if ("..".equals(part)) return false;
        }
        return true;
    }

    private static void rejectConfigValue(String s) throws IOException {
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf(',') >= 0)
            throw new IOException("Unsupported character in dnsmasq value.");
    }

    private static boolean ipv4(String s) {
        if (s == null) return false;
        String[] p = s.split("\\.", -1);
        if (p.length != 4) return false;

        for (String x : p) {
            if (x.length() == 0 || x.length() > 3) return false;
            int n = 0;
            for (int i = 0; i < x.length(); i++) {
                char c = x.charAt(i);
                if (c < '0' || c > '9') return false;
                n = n * 10 + (c - '0');
            }
            if (n > 255) return false;
        }
        return true;
    }

    private static boolean same24(String a, String b) {
        String[] aa = a.split("\\.");
        String[] bb = b.split("\\.");
        return aa.length == 4 && bb.length == 4 &&
            aa[0].equals(bb[0]) &&
            aa[1].equals(bb[1]) &&
            aa[2].equals(bb[2]);
    }

    private static long ipToLong(String s) {
        String[] p = s.split("\\.");
        long v = 0;
        for (int i = 0; i < 4; i++)
            v = (v << 8) | Integer.parseInt(p[i]);
        return v;
    }

    private static String shell(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
