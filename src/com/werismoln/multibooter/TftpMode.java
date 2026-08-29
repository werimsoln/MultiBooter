package com.werismoln.multibooter;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TftpMode {

    static {
        System.loadLibrary("tftp");
    }

    private static native boolean startPxeServer(String binaryPath, String iface, String tftpRoot);
    public static native boolean stopPxeServer();

    public static boolean startPxe(Context context, String interfaceName, String tftpRootPath) {
        try {
            File destFile = new File(context.getFilesDir(), "dnsmasq");
            if (!destFile.exists()) {
                InputStream is = context.getAssets().open("dnsmasq");
                FileOutputStream fos = new FileOutputStream(destFile);
                byte[] buffer = new byte[1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                is.close();
                fos.close();
            }

            Runtime.getRuntime().exec("chmod 755 " + destFile.getAbsolutePath()).waitFor();
            
            return startPxeServer(destFile.getAbsolutePath(), interfaceName, tftpRootPath);
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}