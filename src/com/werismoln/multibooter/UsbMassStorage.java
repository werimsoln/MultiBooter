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
    
    static {
        System.loadLibrary("scsi");
    }

    public static native byte[] generateWrite10CBW(int tag, int lba, short sectorCount);
    public static native byte[] generateReadCapacityCBW(int tag);
    public static native long parseReadCapacity(byte[] capacityData);
    
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
            
            long totalSectors = getFlashDriveCapacity(connection, inEndpoint, outEndpoint);
            if (totalSectors > 0) {
                long capacityBytes = totalSectors * 512L;
                Toast.makeText(context, "Kapasite: " + (capacityBytes / (1024*1024)) + " MB", Toast.LENGTH_LONG).show();
            }

            // 2. İstediğin sektörden (LBA) başlayarak veri yazma testi
            /* 
             byte[] myData = new byte[512 * 128]; // Örn: 128 sektörlük veri (64KB)
             int startLba = 2048; // Verinin yazılmaya başlanacağı sektör
             boolean success = writeDataToSector(connection, inEndpoint, outEndpoint, startLba, myData);
            */
            
        }
    }

    /**
     * USB Belleğin toplam sektör sayısını SCSI READ CAPACITY (10) ile okur.
     */
    public long getFlashDriveCapacity(UsbDeviceConnection connection, UsbEndpoint inEndpoint, UsbEndpoint outEndpoint) {
        int timeout = 3000;
        
        byte[] cbw = generateReadCapacityCBW(1); 
        
        int cbwResult = connection.bulkTransfer(outEndpoint, cbw, cbw.length, timeout);
        
        if (cbwResult == cbw.length) {
            byte[] capacityData = new byte[8];
            int dataResult = connection.bulkTransfer(inEndpoint, capacityData, capacityData.length, timeout);
            
            if (dataResult == 8) {
                byte[] csw = new byte[13];
                connection.bulkTransfer(inEndpoint, csw, csw.length, timeout);
                
                return parseReadCapacity(capacityData);
            }
        }
        return -1;
    }

    public boolean writeDataToSector(UsbDeviceConnection connection, UsbEndpoint inEndpoint, UsbEndpoint outEndpoint, int startLba, byte[] data) {
        int timeout = 3000;
        
        short sectorCount = (short) (data.length / 512);

        byte[] cbw = generateWrite10CBW(2, startLba, sectorCount);

        int cbwResult = connection.bulkTransfer(outEndpoint, cbw, cbw.length, timeout);

        if (cbwResult == cbw.length) {
        
		int dataResult = connection.bulkTransfer(outEndpoint, data, data.length, timeout);

            if (dataResult == data.length) {
				
                byte[] csw = new byte[13];
                int cswResult = connection.bulkTransfer(inEndpoint, csw, csw.length, timeout);
                
                return cswResult == 13;
            }
        }
        return false;
    }
}