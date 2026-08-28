import java.io.DataOutputStream;

public class USBGadgetMode {

    public static boolean enableMassStorage(String isoOrImgPath, boolean asCdRom) {
        String cdromFlag = asCdRom ? "1" : "0";
        String[] commands = {
			
            "setprop sys.usb.config none\n",
            "echo '' > /sys/kernel/config/usb_gadget/g1/UDC 2>/dev/null || true\n",

            "echo '" + isoOrImgPath + "' > /sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0/file\n",
            "echo '" + cdromFlag + "' > /sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0/cdrom\n",

            "UDC_NAME=$(ls /sys/class/udc | head -n 1)\n",
            "echo $UDC_NAME > /sys/kernel/config/usb_gadget/g1/UDC\n"
        };
        return runRoot(commands);
    }

    private static boolean runRoot(String[] cmds) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            for (String cmd : cmds) os.writeBytes(cmd);
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}