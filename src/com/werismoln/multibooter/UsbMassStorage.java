import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.widget.Toast;

public class UsbMassStorage {

    private static final String ACTION_USB_PERMISSION = "com.werismoln.multibooter.USB_PERMISSION";
    private UsbManager usbManager;
    private Context context;

    public UsbMassStorage(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void findAndConnectMassStorage() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface usbInterface = device.getInterface(i);
                
                if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    if (usbManager.hasPermission(device)) {
                        communicateWithDevice(device, usbInterface);
                    } else {
                        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
                        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
                        usbManager.requestPermission(device, permissionIntent);
                    }
                    return;
                }
            }
        }
        Toast.makeText(context, "USB flash drive isn't found", Toast.LENGTH_SHORT).show();
    }

    private void communicateWithDevice(UsbDevice device, UsbInterface usbInterface) {
        UsbEndpoint inEndpoint = null;
        UsbEndpoint outEndpoint = null;

        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = usbInterface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    inEndpoint = ep;
                } else {
                    outEndpoint = ep;
                }
            }
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection != null && connection.claimInterface(usbInterface, true)) {
			
        }
    }
}