package com.werismoln.multibooter;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.Map;

public class UsbMassStorage {

    public static final String ACTION_USB_PERMISSION =
        "com.werismoln.multibooter.USB_PERMISSION";

    public static final int RESULT_OK = 0;

    public static final int ERROR_ARGUMENT = -1;
    public static final int ERROR_NOT_FOUND = -2;
    public static final int ERROR_PERMISSION = -3;
    public static final int ERROR_OPEN = -4;
    public static final int ERROR_INTERFACE = -5;
    public static final int ERROR_ENDPOINT = -6;
    public static final int ERROR_SCSI = -7;

    private static final int TIMEOUT_MS = 5000;

    private static final int CBW_SIGNATURE =
        0x43425355;

    private static final int CSW_SIGNATURE =
        0x53425355;

    private static final int CBW_LENGTH = 31;
    private static final int CSW_LENGTH = 13;

    private static final int SCSI_READ_CAPACITY_10 =
        0x25;

    private static final int SCSI_WRITE_10 =
        0x2A;

    private static final int SCSI_SYNCHRONIZE_CACHE_10 =
        0x35;

    private static final int SCSI_SUBCLASS =
        0x06;

    private static final int BOT_PROTOCOL =
        0x50;

    private final Context context;
    private final UsbManager usbManager;

    private UsbDevice device;
    private UsbInterface usbInterface;
    private UsbEndpoint inEndpoint;
    private UsbEndpoint outEndpoint;
    private UsbDeviceConnection connection;

    private int nextTag = 1;

    private long totalSectors = 0;
    private int logicalBlockSize = 0;

    private volatile String lastError = "";

    public UsbMassStorage(
        Context context
    ) {

        if (context == null) {
            throw new IllegalArgumentException(
                "context == null"
            );
        }

        this.context =
            context.getApplicationContext();

        this.usbManager =
            (UsbManager)
            this.context.getSystemService(
                Context.USB_SERVICE
            );
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isOpen() {

        return
            connection != null &&
            usbInterface != null &&
            inEndpoint != null &&
            outEndpoint != null;
    }

    public long getTotalSectors() {
        return totalSectors;
    }

    public int getLogicalBlockSize() {
        return logicalBlockSize;
    }

    public long getCapacityBytes() {

        if (
            totalSectors <= 0 ||
            logicalBlockSize <= 0 ||
            totalSectors >
                Long.MAX_VALUE /
                logicalBlockSize
        ) {
            return -1;
        }

        return
            totalSectors *
            (long)logicalBlockSize;
    }

    public UsbDevice findMassStorageDevice() {

        if (usbManager == null) {
            lastError =
                "UsbManager is unavailable.";
            return null;
        }

        Map<String, UsbDevice> devices =
            usbManager.getDeviceList();

        for (
            UsbDevice candidate :
            devices.values()
        ) {

            if (
                findMassStorageInterface(
                    candidate
                ) != null
            ) {

                lastError = "";
                return candidate;
            }
        }

        lastError =
            "USB Mass Storage device was not found.";

        return null;
    }

    private UsbInterface findMassStorageInterface(
        UsbDevice usbDevice
    ) {

        if (usbDevice == null) {
            return null;
        }

        for (
            int i = 0;
            i < usbDevice.getInterfaceCount();
            i++
        ) {

            UsbInterface candidate =
                usbDevice.getInterface(i);

            if (
                candidate.getInterfaceClass() ==
                    UsbConstants.USB_CLASS_MASS_STORAGE &&
                candidate.getInterfaceSubclass() ==
                    SCSI_SUBCLASS &&
                candidate.getInterfaceProtocol() ==
                    BOT_PROTOCOL
            ) {
                return candidate;
            }
        }

        return null;
    }

    public boolean hasPermission(
        UsbDevice usbDevice
    ) {

        return
            usbManager != null &&
            usbDevice != null &&
            usbManager.hasPermission(
                usbDevice
            );
    }

    public int requestPermission(
        UsbDevice usbDevice
    ) {

        if (
            usbManager == null ||
            usbDevice == null
        ) {

            lastError =
                "Invalid USB permission request.";

            return ERROR_ARGUMENT;
        }

        if (
            usbManager.hasPermission(
                usbDevice
            )
        ) {

            lastError = "";
            return RESULT_OK;
        }

        Intent intent =
            new Intent(
                ACTION_USB_PERMISSION
            );

        intent.setPackage(
            context.getPackageName()
        );

        PendingIntent permissionIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT |
                PendingIntent.FLAG_IMMUTABLE
            );

        usbManager.requestPermission(
            usbDevice,
            permissionIntent
        );

        lastError =
            "USB permission requested.";

        return ERROR_PERMISSION;
    }

    public int findAndConnectMassStorage() {

        UsbDevice found =
            findMassStorageDevice();

        if (found == null) {
            return ERROR_NOT_FOUND;
        }

        if (!hasPermission(found)) {

            requestPermission(
                found
            );

            return ERROR_PERMISSION;
        }

        return open(
            found
        );
    }

    public synchronized int open(
        UsbDevice usbDevice
    ) {

        close();

        if (
            usbManager == null ||
            usbDevice == null
        ) {

            lastError =
                "Invalid USB device.";

            return ERROR_ARGUMENT;
        }

        if (
            !usbManager.hasPermission(
                usbDevice
            )
        ) {

            lastError =
                "USB permission has not been granted.";

            return ERROR_PERMISSION;
        }

        UsbInterface massInterface =
            findMassStorageInterface(
                usbDevice
            );

        if (massInterface == null) {

            lastError =
                "SCSI/BOT Mass Storage interface was not found.";

            return ERROR_INTERFACE;
        }

        UsbEndpoint bulkIn = null;
        UsbEndpoint bulkOut = null;

        for (
            int i = 0;
            i < massInterface.getEndpointCount();
            i++
        ) {

            UsbEndpoint endpoint =
                massInterface.getEndpoint(i);

            if (
                endpoint.getType() !=
                UsbConstants.USB_ENDPOINT_XFER_BULK
            ) {
                continue;
            }

            if (
                endpoint.getDirection() ==
                UsbConstants.USB_DIR_IN
            ) {

                bulkIn =
                    endpoint;

            } else if (
                endpoint.getDirection() ==
                UsbConstants.USB_DIR_OUT
            ) {

                bulkOut =
                    endpoint;
            }
        }

        if (
            bulkIn == null ||
            bulkOut == null
        ) {

            lastError =
                "Bulk IN/OUT endpoints were not found.";

            return ERROR_ENDPOINT;
        }

        UsbDeviceConnection opened =
            usbManager.openDevice(
                usbDevice
            );

        if (opened == null) {

            lastError =
                "UsbManager.openDevice() failed.";

            return ERROR_OPEN;
        }

        if (
            !opened.claimInterface(
                massInterface,
                true
            )
        ) {

            opened.close();

            lastError =
                "claimInterface() failed.";

            return ERROR_INTERFACE;
        }

        this.device =
            usbDevice;

        this.usbInterface =
            massInterface;

        this.inEndpoint =
            bulkIn;

        this.outEndpoint =
            bulkOut;

        this.connection =
            opened;

        this.totalSectors = 0;
        this.logicalBlockSize = 0;

        if (!readCapacity()) {

            close();
            return ERROR_SCSI;
        }

        lastError = "";
        return RESULT_OK;
    }

    public synchronized void close() {

        if (
            connection != null &&
            usbInterface != null
        ) {

            try {
                connection.releaseInterface(
                    usbInterface
                );
            } catch (Throwable ignored) {
            }
        }

        if (connection != null) {

            try {
                connection.close();
            } catch (Throwable ignored) {
            }
        }

        device = null;
        usbInterface = null;
        inEndpoint = null;
        outEndpoint = null;
        connection = null;

        totalSectors = 0;
        logicalBlockSize = 0;
    }

    private synchronized int allocateTag() {

        int tag =
            nextTag++;

        if (nextTag == 0) {
            nextTag = 1;
        }

        return tag;
    }

    private static void putLe32(
        byte[] buffer,
        int offset,
        int value
    ) {

        buffer[offset] =
            (byte)value;

        buffer[offset + 1] =
            (byte)(value >>> 8);

        buffer[offset + 2] =
            (byte)(value >>> 16);

        buffer[offset + 3] =
            (byte)(value >>> 24);
    }

    private static int readLe32(
        byte[] buffer,
        int offset
    ) {

        return
            (buffer[offset] & 0xFF) |
            ((buffer[offset + 1] & 0xFF) << 8) |
            ((buffer[offset + 2] & 0xFF) << 16) |
            ((buffer[offset + 3] & 0xFF) << 24);
    }

    private static long readBe32(
        byte[] buffer,
        int offset
    ) {

        return
            ((long)(buffer[offset] & 0xFF) << 24) |
            ((long)(buffer[offset + 1] & 0xFF) << 16) |
            ((long)(buffer[offset + 2] & 0xFF) << 8) |
            ((long)(buffer[offset + 3] & 0xFF));
    }

    private static void putBe32(
        byte[] buffer,
        int offset,
        long value
    ) {

        buffer[offset] =
            (byte)(value >>> 24);

        buffer[offset + 1] =
            (byte)(value >>> 16);

        buffer[offset + 2] =
            (byte)(value >>> 8);

        buffer[offset + 3] =
            (byte)value;
    }

    private static void putBe16(
        byte[] buffer,
        int offset,
        int value
    ) {

        buffer[offset] =
            (byte)(value >>> 8);

        buffer[offset + 1] =
            (byte)value;
    }

    private static byte[] createCbw(
        int tag,
        int transferBytes,
        int direction,
        byte[] cdb,
        int cdbLength
    ) {

        byte[] cbw =
            new byte[CBW_LENGTH];

        putLe32(
            cbw,
            0,
            CBW_SIGNATURE
        );

        putLe32(
            cbw,
            4,
            tag
        );

        putLe32(
            cbw,
            8,
            transferBytes
        );

        cbw[12] =
            (byte)direction;

        cbw[13] = 0;
        cbw[14] =
            (byte)cdbLength;

        System.arraycopy(
            cdb,
            0,
            cbw,
            15,
            cdbLength
        );

        return cbw;
    }

    private byte[] createReadCapacityCbw(
        int tag
    ) {

        byte[] cdb =
            new byte[10];

        cdb[0] =
            (byte)SCSI_READ_CAPACITY_10;

        return createCbw(
            tag,
            8,
            0x80,
            cdb,
            10
        );
    }

    private byte[] createWrite10Cbw(
        int tag,
        long lba,
        int sectors
    ) {

        long bytes =
            (long)sectors *
            logicalBlockSize;

        if (
            bytes >
            Integer.MAX_VALUE
        ) {
            return null;
        }

        byte[] cdb =
            new byte[10];

        cdb[0] =
            (byte)SCSI_WRITE_10;

        putBe32(
            cdb,
            2,
            lba
        );

        putBe16(
            cdb,
            7,
            sectors
        );

        return createCbw(
            tag,
            (int)bytes,
            0x00,
            cdb,
            10
        );
    }

    private byte[] createSynchronizeCacheCbw(
        int tag
    ) {

        byte[] cdb =
            new byte[10];

        cdb[0] =
            (byte)SCSI_SYNCHRONIZE_CACHE_10;

        return createCbw(
            tag,
            0,
            0x00,
            cdb,
            10
        );
    }

    private boolean readCapacity() {

        if (!isOpen()) {
            return false;
        }

        int tag =
            allocateTag();

        byte[] cbw =
            createReadCapacityCbw(
                tag
            );

        if (
            bulkOut(
                cbw
            ) != cbw.length
        ) {

            lastError =
                "READ CAPACITY(10) CBW failed.";

            return false;
        }

        byte[] capacity =
            new byte[8];

        if (
            bulkIn(
                capacity
            ) != capacity.length
        ) {

            lastError =
                "READ CAPACITY(10) data failed.";

            return false;
        }

        if (
            !readCsw(
                tag
            )
        ) {
            return false;
        }

        long lastLba =
            readBe32(
                capacity,
                0
            );

        long blockLength =
            readBe32(
                capacity,
                4
            );

        if (
            lastLba == 0xFFFFFFFFL
        ) {

            lastError =
                "Device requires READ CAPACITY(16).";

            return false;
        }

        if (
            blockLength < 512 ||
            blockLength > 4096 ||
            (blockLength &
                (blockLength - 1)) != 0
        ) {

            lastError =
                "Unsupported logical block size: " +
                blockLength;

            return false;
        }

        totalSectors =
            lastLba + 1L;

        logicalBlockSize =
            (int)blockLength;

        return true;
    }

    public long getFlashDriveCapacity() {

        if (!isOpen()) {
            return -1;
        }

        if (
            totalSectors <= 0 &&
            !readCapacity()
        ) {
            return -1;
        }

        return totalSectors;
    }

    public synchronized boolean writeDataToSector(
        long startLba,
        byte[] data
    ) {

        if (!isOpen()) {

            lastError =
                "USB device is not open.";

            return false;
        }

        if (
            data == null ||
            data.length == 0 ||
            logicalBlockSize <= 0 ||
            data.length %
                logicalBlockSize != 0
        ) {

            lastError =
                "Data must contain complete logical blocks.";

            return false;
        }

        long sectorCountLong =
            data.length /
            logicalBlockSize;

        if (
            startLba < 0 ||
            startLba >
                0xFFFFFFFFL ||
            sectorCountLong <= 0 ||
            sectorCountLong >
                0xFFFFL
        ) {

            lastError =
                "WRITE(10) LBA or block count is out of range.";

            return false;
        }

        long lastLba =
            startLba +
            sectorCountLong -
            1;

        if (
            lastLba < startLba ||
            lastLba >
                0xFFFFFFFFL ||
            (
                totalSectors > 0 &&
                lastLba >=
                    totalSectors
            )
        ) {

            lastError =
                "WRITE(10) crosses the media boundary.";

            return false;
        }

        int sectors =
            (int)sectorCountLong;

        int tag =
            allocateTag();

        byte[] cbw =
            createWrite10Cbw(
                tag,
                startLba,
                sectors
            );

        if (cbw == null) {

            lastError =
                "WRITE(10) transfer is too large.";

            return false;
        }

        if (
            bulkOut(
                cbw
            ) != cbw.length
        ) {

            lastError =
                "WRITE(10) CBW failed.";

            return false;
        }

        if (
            bulkOut(
                data
            ) != data.length
        ) {

            lastError =
                "WRITE(10) data stage failed.";

            return false;
        }

        if (!readCsw(tag)) {
            return false;
        }

        lastError = "";
        return true;
    }

    public synchronized boolean synchronizeCache() {

        if (!isOpen()) {

            lastError =
                "USB device is not open.";

            return false;
        }

        int tag =
            allocateTag();

        byte[] cbw =
            createSynchronizeCacheCbw(
                tag
            );

        if (
            bulkOut(
                cbw
            ) != cbw.length
        ) {

            lastError =
                "SYNCHRONIZE CACHE CBW failed.";

            return false;
        }

        return readCsw(tag);
    }

    private int bulkOut(
        byte[] data
    ) {

        int transferred = 0;

        while (
            transferred <
            data.length
        ) {

            int result =
                connection.bulkTransfer(
                    outEndpoint,
                    data,
                    transferred,
                    data.length -
                        transferred,
                    TIMEOUT_MS
                );

            if (result <= 0) {
                return -1;
            }

            transferred +=
                result;
        }

        return transferred;
    }

    private int bulkIn(
        byte[] data
    ) {

        int transferred = 0;

        while (
            transferred <
            data.length
        ) {

            int result =
                connection.bulkTransfer(
                    inEndpoint,
                    data,
                    transferred,
                    data.length -
                        transferred,
                    TIMEOUT_MS
                );

            if (result <= 0) {
                return -1;
            }

            transferred +=
                result;
        }

        return transferred;
    }

    private boolean readCsw(
        int expectedTag
    ) {

        byte[] csw =
            new byte[CSW_LENGTH];

        if (
            bulkIn(
                csw
            ) != csw.length
        ) {

            lastError =
                "BOT CSW could not be read.";

            return false;
        }

        int signature =
            readLe32(
                csw,
                0
            );

        int tag =
            readLe32(
                csw,
                4
            );

        int status =
            csw[12] & 0xFF;

        if (
            signature !=
                CSW_SIGNATURE
        ) {

            lastError =
                "Invalid BOT CSW signature.";

            return false;
        }

        if (
            tag !=
                expectedTag
        ) {

            lastError =
                "BOT CSW tag mismatch.";

            return false;
        }

        if (status != 0) {

            lastError =
                "SCSI command failed. CSW status=" +
                status;

            return false;
        }

        return true;
    }
}
