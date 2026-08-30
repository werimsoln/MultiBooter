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
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;

public final class UsbVentoy {

    private static final String TAG = "USBVENTOY";

    /*
     * ---------------------------------------------------------------------
     * PUBLIC RESULT CODES
     * ---------------------------------------------------------------------
     */

    public static final int RESULT_OK = 0;

    public static final int ERROR_ARGUMENT = -1;
    public static final int ERROR_USB_MANAGER = -2;
    public static final int ERROR_DEVICE_NOT_FOUND = -3;
    public static final int ERROR_PERMISSION_REQUIRED = -4;
    public static final int ERROR_INTERFACE_NOT_FOUND = -5;
    public static final int ERROR_ENDPOINT_NOT_FOUND = -6;
    public static final int ERROR_OPEN_DEVICE = -7;
    public static final int ERROR_CLAIM_INTERFACE = -8;
    public static final int ERROR_SCSI = -9;
    public static final int ERROR_UNSUPPORTED_BLOCK_SIZE = -10;
    public static final int ERROR_UNSUPPORTED_CAPACITY = -11;
    public static final int ERROR_IO = -12;
    public static final int ERROR_NATIVE_EXFAT = -13;
    public static final int ERROR_EXFAT_LIBRARY_UNAVAILABLE = -14;

    /*
     * libexfat.c return codes are preserved when nativeFormatExfat()
     * successfully enters the native formatter:
     *
     *   0  EXFAT_OK
     *  -1  EXFAT_ERROR_ARGUMENT
     *  -2  EXFAT_ERROR_GEOMETRY
     *  -3  EXFAT_ERROR_MEMORY
     *  -4  EXFAT_ERROR_IO
     *  -5  EXFAT_ERROR_OVERFLOW
     */

    /*
     * ---------------------------------------------------------------------
     * USB / BOT / SCSI CONSTANTS
     * ---------------------------------------------------------------------
     */

    public static final String ACTION_USB_PERMISSION =
        "com.werismoln.multibooter.USB_PERMISSION";

    private static final int USB_TIMEOUT_MS = 5000;

    private static final int CBW_LENGTH = 31;
    private static final int CSW_LENGTH = 13;

    private static final int CBW_SIGNATURE = 0x43425355;
    private static final int CSW_SIGNATURE = 0x53425355;

    private static final int SCSI_TEST_UNIT_READY = 0x00;
    private static final int SCSI_READ_CAPACITY_10 = 0x25;
    private static final int SCSI_WRITE_10 = 0x2A;
    private static final int SCSI_SYNCHRONIZE_CACHE_10 = 0x35;

    private static final int BOT_DIRECTION_OUT = 0x00;
    private static final int BOT_DIRECTION_IN = 0x80;

    private static final int MASS_STORAGE_SUBCLASS_SCSI = 0x06;
    private static final int MASS_STORAGE_PROTOCOL_BOT = 0x50;

    /*
     * WRITE(10):
     *
     * LBA             = unsigned 32 bit
     * Transfer Length = unsigned 16 bit
     */
    private static final long MAX_WRITE10_LBA = 0xFFFFFFFFL;
    private static final int MAX_WRITE10_BLOCKS = 0xFFFF;

    /*
     * 128 blocks at 512-byte logical block size = 64 KiB.
     *
     * This keeps individual Android bulkTransfer() operations moderate.
     */
    private static final int DEFAULT_TRANSFER_BLOCKS = 128;

    /*
     * exfat formatter special value:
     * automatic sectors-per-cluster selection.
     */
    public static final int EXFAT_AUTO_CLUSTER_SHIFT = 0xFF;

    /*
     * ---------------------------------------------------------------------
     * NATIVE LIBRARY STATE
     * ---------------------------------------------------------------------
     */

    private static final boolean SCSI_LIBRARY_LOADED;
    private static final boolean EXFAT_LIBRARY_LOADED;

    static {

        boolean scsiLoaded = false;
        boolean exfatLoaded = false;

        try {
            System.loadLibrary("scsi");
            scsiLoaded = true;
        } catch (Throwable ignored) {
        }

        try {
            System.loadLibrary("exfat");
            exfatLoaded = true;
        } catch (Throwable ignored) {
        }

        SCSI_LIBRARY_LOADED = scsiLoaded;
        EXFAT_LIBRARY_LOADED = exfatLoaded;
    }

    /*
     * ---------------------------------------------------------------------
     * INSTANCE STATE
     * ---------------------------------------------------------------------
     */

    private final Context context;
    private final UsbManager usbManager;

    private UsbDevice device;
    private UsbInterface massStorageInterface;
    private UsbEndpoint bulkInEndpoint;
    private UsbEndpoint bulkOutEndpoint;
    private UsbDeviceConnection connection;

    private int blockSize = 0;
    private long blockCount = 0;

    private int nextTag = 1;

    private volatile String lastError = "";

    /*
     * ---------------------------------------------------------------------
     * CAPACITY RESULT
     * ---------------------------------------------------------------------
     */

    public static final class Capacity {

        public final long blockCount;
        public final int blockSize;
        public final long totalBytes;

        private Capacity(
            long blockCount,
            int blockSize
        ) {

            this.blockCount = blockCount;
            this.blockSize = blockSize;

            if (
                blockCount > 0 &&
                blockSize > 0 &&
                blockCount <= Long.MAX_VALUE / blockSize
            ) {
                this.totalBytes =
                    blockCount * (long) blockSize;

            } else {

                this.totalBytes = -1;
            }
        }

        @Override
        public String toString() {

            return
                "Capacity{" +
                "blockCount=" + blockCount +
                ", blockSize=" + blockSize +
                ", totalBytes=" + totalBytes +
                '}';
        }
    }

    /*
     * ---------------------------------------------------------------------
     * CONSTRUCTOR
     * ---------------------------------------------------------------------
     */

    public UsbVentoy(Context context) {

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

    /*
     * ---------------------------------------------------------------------
     * LIBRARY STATE
     * ---------------------------------------------------------------------
     */

    public static boolean isScsiLibraryLoaded() {
        return SCSI_LIBRARY_LOADED;
    }

    public static boolean isExfatLibraryLoaded() {
        return EXFAT_LIBRARY_LOADED;
    }

    public String getLastError() {
        return lastError;
    }

    private int fail(
        int code,
        String message
    ) {

        lastError =
            message == null
            ? ""
            : message;

        return code;
    }

    /*
     * ---------------------------------------------------------------------
     * USB DEVICE DISCOVERY
     * ---------------------------------------------------------------------
     */

    /**
     * Finds the first USB Mass Storage device containing a BOT/SCSI
     * interface.
     */
    public UsbDevice findMassStorageDevice() {

        if (usbManager == null) {
            fail(
                ERROR_USB_MANAGER,
                "UsbManager is unavailable."
            );

            return null;
        }

        Map<String, UsbDevice> devices =
            usbManager.getDeviceList();

        for (UsbDevice usbDevice : devices.values()) {

            UsbInterface usbInterface =
                findMassStorageInterface(
                    usbDevice
                );

            if (usbInterface != null) {

                lastError = "";
                return usbDevice;
            }
        }

        fail(
            ERROR_DEVICE_NOT_FOUND,
            "USB Mass Storage device was not found."
        );

        return null;
    }

    /**
     * Finds the BOT + SCSI transparent command-set interface.
     */
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

            UsbInterface usbInterface =
                usbDevice.getInterface(i);

            if (
                usbInterface.getInterfaceClass() ==
                    UsbConstants.USB_CLASS_MASS_STORAGE &&
                usbInterface.getInterfaceSubclass() ==
                    MASS_STORAGE_SUBCLASS_SCSI &&
                usbInterface.getInterfaceProtocol() ==
                    MASS_STORAGE_PROTOCOL_BOT
            ) {
                return usbInterface;
            }
        }

        return null;
    }

    /*
     * ---------------------------------------------------------------------
     * USB PERMISSION
     * ---------------------------------------------------------------------
     */

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

    /**
     * Requests Android USB Host permission.
     *
     * The Activity/BroadcastReceiver should listen for ACTION_USB_PERMISSION.
     */
    public int requestPermission(
        UsbDevice usbDevice
    ) {

        if (usbManager == null) {

            return fail(
                ERROR_USB_MANAGER,
                "UsbManager is unavailable."
            );
        }

        if (usbDevice == null) {

            return fail(
                ERROR_ARGUMENT,
                "usbDevice == null"
            );
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

        /*
         * Keep the permission broadcast inside our own application.
         */
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

        return fail(
            ERROR_PERMISSION_REQUIRED,
            "USB permission was requested."
        );
    }

    /*
     * ---------------------------------------------------------------------
     * OPEN / CLOSE
     * ---------------------------------------------------------------------
     */

    public int openFirstMassStorageDevice() {

        UsbDevice usbDevice =
            findMassStorageDevice();

        if (usbDevice == null) {
            return ERROR_DEVICE_NOT_FOUND;
        }

        if (!hasPermission(usbDevice)) {

            requestPermission(
                usbDevice
            );

            return ERROR_PERMISSION_REQUIRED;
        }

        return open(
            usbDevice
        );
    }

    public synchronized int open(
        UsbDevice usbDevice
    ) {

        close();

        if (usbManager == null) {

            return fail(
                ERROR_USB_MANAGER,
                "UsbManager is unavailable."
            );
        }

        if (usbDevice == null) {

            return fail(
                ERROR_ARGUMENT,
                "usbDevice == null"
            );
        }

        if (
            !usbManager.hasPermission(
                usbDevice
            )
        ) {

            return fail(
                ERROR_PERMISSION_REQUIRED,
                "USB permission has not been granted."
            );
        }

        UsbInterface usbInterface =
            findMassStorageInterface(
                usbDevice
            );

        if (usbInterface == null) {

            return fail(
                ERROR_INTERFACE_NOT_FOUND,
                "SCSI/BOT Mass Storage interface was not found."
            );
        }

        UsbEndpoint inEndpoint = null;
        UsbEndpoint outEndpoint = null;

        for (
            int i = 0;
            i < usbInterface.getEndpointCount();
            i++
        ) {

            UsbEndpoint endpoint =
                usbInterface.getEndpoint(i);

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

                inEndpoint =
                    endpoint;

            } else if (
                endpoint.getDirection() ==
                UsbConstants.USB_DIR_OUT
            ) {

                outEndpoint =
                    endpoint;
            }
        }

        if (
            inEndpoint == null ||
            outEndpoint == null
        ) {

            return fail(
                ERROR_ENDPOINT_NOT_FOUND,
                "Bulk IN/OUT endpoints were not found."
            );
        }

        UsbDeviceConnection usbConnection =
            usbManager.openDevice(
                usbDevice
            );

        if (usbConnection == null) {

            return fail(
                ERROR_OPEN_DEVICE,
                "UsbManager.openDevice() failed."
            );
        }

        if (
            !usbConnection.claimInterface(
                usbInterface,
                true
            )
        ) {

            usbConnection.close();

            return fail(
                ERROR_CLAIM_INTERFACE,
                "claimInterface() failed."
            );
        }

        this.device =
            usbDevice;

        this.massStorageInterface =
            usbInterface;

        this.bulkInEndpoint =
            inEndpoint;

        this.bulkOutEndpoint =
            outEndpoint;

        this.connection =
            usbConnection;

        this.blockSize = 0;
        this.blockCount = 0;

        /*
         * BOT reset before the first command is not mandatory on every
         * device, so do not reset a healthy interface here.
         */

        Capacity capacity =
            readCapacity();

        if (capacity == null) {

            close();
            return ERROR_SCSI;
        }

        lastError = "";
        return RESULT_OK;
    }

    public synchronized void close() {

        if (
            connection != null &&
            massStorageInterface != null
        ) {

            try {

                connection.releaseInterface(
                    massStorageInterface
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
        massStorageInterface = null;
        bulkInEndpoint = null;
        bulkOutEndpoint = null;
        connection = null;

        blockSize = 0;
        blockCount = 0;
    }

    public synchronized boolean isOpen() {

        return
            connection != null &&
            massStorageInterface != null &&
            bulkInEndpoint != null &&
            bulkOutEndpoint != null;
    }

    public UsbDevice getDevice() {
        return device;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public long getBlockCount() {
        return blockCount;
    }

    /*
     * ---------------------------------------------------------------------
     * TAG HANDLING
     * ---------------------------------------------------------------------
     */

    private synchronized int allocateTag() {

        int tag =
            nextTag++;

        if (nextTag == 0) {
            nextTag = 1;
        }

        return tag;
    }

    /*
     * ---------------------------------------------------------------------
     * BYTE ORDER HELPERS
     * ---------------------------------------------------------------------
     */

    private static void putLe32(
        byte[] buffer,
        int offset,
        int value
    ) {

        buffer[offset] =
            (byte)(value & 0xFF);

        buffer[offset + 1] =
            (byte)((value >>> 8) & 0xFF);

        buffer[offset + 2] =
            (byte)((value >>> 16) & 0xFF);

        buffer[offset + 3] =
            (byte)((value >>> 24) & 0xFF);
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

    private static long readBe32Unsigned(
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
            (byte)((value >>> 24) & 0xFF);

        buffer[offset + 1] =
            (byte)((value >>> 16) & 0xFF);

        buffer[offset + 2] =
            (byte)((value >>> 8) & 0xFF);

        buffer[offset + 3] =
            (byte)(value & 0xFF);
    }

    private static void putBe16(
        byte[] buffer,
        int offset,
        int value
    ) {

        buffer[offset] =
            (byte)((value >>> 8) & 0xFF);

        buffer[offset + 1] =
            (byte)(value & 0xFF);
    }

    private static int log2Exact(
        int value
    ) {

        if (
            value <= 0 ||
            (value & (value - 1)) != 0
        ) {
            return -1;
        }

        int shift = 0;

        while (value > 1) {
            value >>>= 1;
            shift++;
        }

        return shift;
    }

    /*
     * ---------------------------------------------------------------------
     * BOT CBW BUILDERS
     * ---------------------------------------------------------------------
     *
     * These are intentionally generated correctly in Java.
     *
     * The repository's current libscsi.c exports
     * UsbScsiBridge.generateWrite10CBW(), but its current byte-order
     * conversion must be corrected before UsbVentoy should rely on it.
     *
     * libscsi.so is still used for parseReadCapacity(), matching the
     * repository's existing JNI class name.
     * ---------------------------------------------------------------------
     */

    private static byte[] createCbw(
        int tag,
        int dataTransferLength,
        int flags,
        int lun,
        byte[] cdb,
        int cdbLength
    ) {

        if (
            cdb == null ||
            cdbLength < 1 ||
            cdbLength > 16 ||
            cdb.length < cdbLength
        ) {
            throw new IllegalArgumentException(
                "Invalid CDB."
            );
        }

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
            dataTransferLength
        );

        cbw[12] =
            (byte)(flags & 0xFF);

        cbw[13] =
            (byte)(lun & 0x0F);

        cbw[14] =
            (byte)(cdbLength & 0x1F);

        System.arraycopy(
            cdb,
            0,
            cbw,
            15,
            cdbLength
        );

        return cbw;
    }

    private static byte[] createReadCapacity10Cbw(
        int tag
    ) {

        byte[] cdb =
            new byte[10];

        cdb[0] =
            (byte)SCSI_READ_CAPACITY_10;

        return createCbw(
            tag,
            8,
            BOT_DIRECTION_IN,
            0,
            cdb,
            10
        );
    }

    private static byte[] createWrite10Cbw(
        int tag,
        long lba,
        int sectorCount,
        int logicalBlockSize
    ) {

        if (
            lba < 0 ||
            lba > MAX_WRITE10_LBA
        ) {
            throw new IllegalArgumentException(
                "WRITE(10) LBA is out of range."
            );
        }

        if (
            sectorCount <= 0 ||
            sectorCount > MAX_WRITE10_BLOCKS
        ) {
            throw new IllegalArgumentException(
                "WRITE(10) sectorCount is out of range."
            );
        }

        long transferBytesLong =
            (long)sectorCount *
            logicalBlockSize;

        if (
            transferBytesLong >
            Integer.MAX_VALUE
        ) {
            throw new IllegalArgumentException(
                "WRITE(10) transfer is too large."
            );
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
            sectorCount
        );

        return createCbw(
            tag,
            (int)transferBytesLong,
            BOT_DIRECTION_OUT,
            0,
            cdb,
            10
        );
    }

    private static byte[] createSynchronizeCache10Cbw(
        int tag
    ) {

        byte[] cdb =
            new byte[10];

        cdb[0] =
            (byte)SCSI_SYNCHRONIZE_CACHE_10;

        return createCbw(
            tag,
            0,
            BOT_DIRECTION_OUT,
            0,
            cdb,
            10
        );
    }

    private static byte[] createTestUnitReadyCbw(
        int tag
    ) {

        byte[] cdb =
            new byte[6];

        cdb[0] =
            (byte)SCSI_TEST_UNIT_READY;

        return createCbw(
            tag,
            0,
            BOT_DIRECTION_OUT,
            0,
            cdb,
            6
        );
    }

    /*
     * ---------------------------------------------------------------------
     * BULK TRANSFER HELPERS
     * ---------------------------------------------------------------------
     */

    private int bulkOutAll(
        byte[] buffer,
        int offset,
        int length
    ) {

        if (
            connection == null ||
            bulkOutEndpoint == null ||
            buffer == null
        ) {
            return -1;
        }

        int transferred = 0;

        while (transferred < length) {

            int result =
                connection.bulkTransfer(
                    bulkOutEndpoint,
                    buffer,
                    offset + transferred,
                    length - transferred,
                    USB_TIMEOUT_MS
                );

            if (result <= 0) {

                Log.e(
                    TAG,
                    "BULK OUT failed: result=" + result +
                    ", requested=" + (length - transferred) +
                    ", totalLength=" + length +
                    ", transferred=" + transferred +
                    ", endpoint=0x" +
                    Integer.toHexString(
                        bulkOutEndpoint.getAddress()
                    )
                );

                return -1;
            }

            transferred +=
                result;
        }

        return transferred;
    }

    private int bulkInAll(
        byte[] buffer,
        int offset,
        int length
    ) {

        if (
            connection == null ||
            bulkInEndpoint == null ||
            buffer == null
        ) {
            return -1;
        }

        int transferred = 0;

        while (transferred < length) {

            int result =
                connection.bulkTransfer(
                    bulkInEndpoint,
                    buffer,
                    offset + transferred,
                    length - transferred,
                    USB_TIMEOUT_MS
                );

            if (result <= 0) {

                Log.e(
                    TAG,
                    "BULK IN failed: result=" + result +
                    ", requested=" + (length - transferred) +
                    ", totalLength=" + length +
                    ", transferred=" + transferred +
                    ", endpoint=0x" +
                    Integer.toHexString(
                        bulkInEndpoint.getAddress()
                    )
                );

                return -1;
            }

            transferred +=
                result;
        }

        return transferred;
    }

    /*
     * ---------------------------------------------------------------------
     * CSW VALIDATION
     * ---------------------------------------------------------------------
     */

    private boolean readAndValidateCsw(
        int expectedTag
    ) {

        byte[] csw =
            new byte[CSW_LENGTH];

        if (
            bulkInAll(
                csw,
                0,
                csw.length
            ) != csw.length
        ) {

            fail(
                ERROR_SCSI,
                "Could not read BOT CSW. expectedTag=" +
                expectedTag
            );

            Log.e(
                TAG,
                lastError
            );

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

        long residue =
            ((long)readLe32(
                csw,
                8
            )) & 0xFFFFFFFFL;

        int status =
            csw[12] & 0xFF;

        if (
            signature != CSW_SIGNATURE
        ) {

            fail(
                ERROR_SCSI,
                "Invalid BOT CSW signature. " +
                "expectedTag=" + expectedTag +
                ", receivedTag=" + tag +
                ", signature=0x" +
                Integer.toHexString(signature) +
                ", residue=" + residue +
                ", status=" + status
            );

            Log.e(
                TAG,
                lastError
            );

            return false;
        }

        if (
            tag != expectedTag
        ) {

            fail(
                ERROR_SCSI,
                "BOT CSW tag mismatch. " +
                "expectedTag=" + expectedTag +
                ", receivedTag=" + tag +
                ", residue=" + residue +
                ", status=" + status
            );

            Log.e(
                TAG,
                lastError
            );

            return false;
        }

        if (status != 0) {

            fail(
                ERROR_SCSI,
                "SCSI command failed. " +
                "expectedTag=" + expectedTag +
                ", receivedTag=" + tag +
                ", CSW status=" + status +
                ", residue=" + residue
            );

            Log.e(
                TAG,
                lastError
            );

            return false;
        }

        return true;
    }

    /*
     * ---------------------------------------------------------------------
     * READ CAPACITY(10)
     * ---------------------------------------------------------------------
     */

    public synchronized Capacity readCapacity() {

        if (!isOpen()) {

            fail(
                ERROR_OPEN_DEVICE,
                "USB Mass Storage device is not open."
            );

            return null;
        }

        int tag =
            allocateTag();

        byte[] cbw =
            createReadCapacity10Cbw(
                tag
            );

        if (
            bulkOutAll(
                cbw,
                0,
                cbw.length
            ) != cbw.length
        ) {

            fail(
                ERROR_SCSI,
                "READ CAPACITY(10) CBW could not be sent."
            );

            return null;
        }

        byte[] capacityData =
            new byte[8];

        if (
            bulkInAll(
                capacityData,
                0,
                capacityData.length
            ) != capacityData.length
        ) {

            fail(
                ERROR_SCSI,
                "READ CAPACITY(10) data could not be read."
            );

            return null;
        }

        if (
            !readAndValidateCsw(
                tag
            )
        ) {
            return null;
        }

        long lastLba =
            readBe32Unsigned(
                capacityData,
                0
            );

        long logicalBlockLength =
            readBe32Unsigned(
                capacityData,
                4
            );

        /*
         * READ CAPACITY(10) returns FFFFFFFFh when READ CAPACITY(16)
         * is required.
         */
        if (
            lastLba == 0xFFFFFFFFL
        ) {

            fail(
                ERROR_UNSUPPORTED_CAPACITY,
                "Device requires READ CAPACITY(16)."
            );

            return null;
        }

        if (
            logicalBlockLength < 512 ||
            logicalBlockLength > 4096 ||
            logicalBlockLength >
                Integer.MAX_VALUE
        ) {

            fail(
                ERROR_UNSUPPORTED_BLOCK_SIZE,
                "Unsupported logical block size: " +
                logicalBlockLength
            );

            return null;
        }

        int blockShift =
            log2Exact(
                (int)logicalBlockLength
            );

        /*
         * exFAT BytesPerSectorShift supports 9..12.
         */
        if (
            blockShift < 9 ||
            blockShift > 12
        ) {

            fail(
                ERROR_UNSUPPORTED_BLOCK_SIZE,
                "Logical block size is not valid for this exFAT formatter."
            );

            return null;
        }

        long sectorsFromNative = -1;

        /*
         * Use the repository's current libscsi.so parser when available.
         *
         * It returns LastLBA + 1.
         */
        if (SCSI_LIBRARY_LOADED) {

            try {

                sectorsFromNative =
                    UsbScsiBridge.parseReadCapacity(
                        capacityData
                    );

            } catch (Throwable ignored) {

                sectorsFromNative = -1;
            }
        }

        long sectors =
            lastLba + 1L;

        /*
         * Native result is only accepted when it agrees with the response
         * we parsed in Java.
         */
        if (
            sectorsFromNative > 0 &&
            sectorsFromNative != sectors
        ) {

            fail(
                ERROR_SCSI,
                "libscsi READ CAPACITY result does not match the device response."
            );

            return null;
        }

        this.blockCount =
            sectors;

        this.blockSize =
            (int)logicalBlockLength;

        lastError = "";

        return new Capacity(
            blockCount,
            blockSize
        );
    }

    /*
     * ---------------------------------------------------------------------
     * TEST UNIT READY
     * ---------------------------------------------------------------------
     */

    public synchronized boolean testUnitReady() {

        if (!isOpen()) {

            fail(
                ERROR_OPEN_DEVICE,
                "USB Mass Storage device is not open."
            );

            return false;
        }

        int tag =
            allocateTag();

        byte[] cbw =
            createTestUnitReadyCbw(
                tag
            );

        if (
            bulkOutAll(
                cbw,
                0,
                cbw.length
            ) != cbw.length
        ) {

            fail(
                ERROR_SCSI,
                "TEST UNIT READY CBW could not be sent."
            );

            return false;
        }

        return readAndValidateCsw(
            tag
        );
    }

    /*
     * ---------------------------------------------------------------------
     * WRITE(10)
     * ---------------------------------------------------------------------
     */

    /**
     * Writes whole logical blocks to the USB Mass Storage device.
     *
     * WARNING:
     * This is destructive raw media I/O.
     */
    public synchronized boolean writeBlocks(
        long startLba,
        byte[] data,
        int sectorCount
    ) {

        if (!isOpen()) {

            fail(
                ERROR_OPEN_DEVICE,
                "USB Mass Storage device is not open."
            );

            return false;
        }

        if (
            data == null ||
            sectorCount <= 0
        ) {

            fail(
                ERROR_ARGUMENT,
                "Invalid WRITE(10) data."
            );

            return false;
        }

        if (blockSize <= 0) {

            fail(
                ERROR_SCSI,
                "Logical block size is unknown."
            );

            return false;
        }

        long requiredLength =
            (long)sectorCount *
            blockSize;

        if (
            requiredLength !=
            data.length
        ) {

            fail(
                ERROR_ARGUMENT,
                "Data length does not match sectorCount * blockSize."
            );

            return false;
        }

        if (
            startLba < 0 ||
            startLba > MAX_WRITE10_LBA
        ) {

            fail(
                ERROR_UNSUPPORTED_CAPACITY,
                "WRITE(10) start LBA is outside the 32-bit range."
            );

            return false;
        }

        if (
            sectorCount >
            MAX_WRITE10_BLOCKS
        ) {

            fail(
                ERROR_ARGUMENT,
                "WRITE(10) transfer length exceeds 65535 blocks."
            );

            return false;
        }

        long lastLba =
            startLba +
            sectorCount -
            1L;

        if (
            lastLba < startLba ||
            lastLba > MAX_WRITE10_LBA
        ) {

            fail(
                ERROR_UNSUPPORTED_CAPACITY,
                "WRITE(10) crosses the 32-bit LBA limit."
            );

            return false;
        }

        if (
            blockCount > 0 &&
            lastLba >= blockCount
        ) {

            fail(
                ERROR_ARGUMENT,
                "WRITE(10) crosses the end of the USB device."
            );

            return false;
        }

        int tag =
            allocateTag();

        byte[] cbw;

        try {

            cbw =
                createWrite10Cbw(
                    tag,
                    startLba,
                    sectorCount,
                    blockSize
                );

        } catch (
            IllegalArgumentException e
        ) {

            fail(
                ERROR_ARGUMENT,
                e.getMessage()
            );

            return false;
        }

        if (
            bulkOutAll(
                cbw,
                0,
                cbw.length
            ) != cbw.length
        ) {

            fail(
                ERROR_SCSI,
                "WRITE(10) CBW could not be sent. " +
                "lba=" + startLba +
                ", sectors=" + sectorCount +
                ", bytes=" + data.length +
                ", tag=" + tag
            );

            Log.e(
                TAG,
                lastError
            );

            return false;
        }

        if (
            bulkOutAll(
                data,
                0,
                data.length
            ) != data.length
        ) {

            fail(
                ERROR_IO,
                "WRITE(10) data stage failed. " +
                "lba=" + startLba +
                ", sectors=" + sectorCount +
                ", bytes=" + data.length +
                ", tag=" + tag
            );

            Log.e(
                TAG,
                lastError
            );

            return false;
        }

        if (
            !readAndValidateCsw(
                tag
            )
        ) {

            Log.e(
                TAG,
                "WRITE(10) CSW validation failed. " +
                "lba=" + startLba +
                ", sectors=" + sectorCount +
                ", bytes=" + data.length +
                ", tag=" + tag +
                ", error=" + lastError
            );

            return false;
        }

        lastError = "";
        return true;
    }

    /**
     * Writes an arbitrary block-aligned byte array and automatically splits
     * it into small WRITE(10) commands.
     */
    public synchronized boolean writeData(
        long startLba,
        byte[] data
    ) {

        if (
            data == null ||
            data.length == 0
        ) {

            fail(
                ERROR_ARGUMENT,
                "data is empty."
            );

            return false;
        }

        if (blockSize <= 0) {

            fail(
                ERROR_SCSI,
                "Logical block size is unknown."
            );

            return false;
        }

        if (
            data.length % blockSize != 0
        ) {

            fail(
                ERROR_ARGUMENT,
                "Data must be aligned to the logical block size."
            );

            return false;
        }

        int totalBlocks =
            data.length /
            blockSize;

        int completedBlocks = 0;

        while (
            completedBlocks <
            totalBlocks
        ) {

            int blocks =
                Math.min(
                    DEFAULT_TRANSFER_BLOCKS,
                    totalBlocks -
                    completedBlocks
                );

            int byteOffset =
                completedBlocks *
                blockSize;

            int byteLength =
                blocks *
                blockSize;

            byte[] chunk =
                Arrays.copyOfRange(
                    data,
                    byteOffset,
                    byteOffset +
                    byteLength
                );

            if (
                !writeBlocks(
                    startLba +
                    completedBlocks,
                    chunk,
                    blocks
                )
            ) {
                return false;
            }

            completedBlocks +=
                blocks;
        }

        return true;
    }

    /*
     * ---------------------------------------------------------------------
     * ASSET -> RAW USB WRITER
     * ---------------------------------------------------------------------
     */

    /**
     * Writes one APK asset directly to the USB device beginning at startLba.
     *
     * The final partial block, if any, is zero-padded.
     *
     * Examples from the current project:
     *
     *   writeAssetToDisk("boot.img", ...)
     *   writeAssetToDisk("core.img", ...)
     *   writeAssetToDisk("ventoy.disk.img", ...)
     *
     * UsbVentoy deliberately does NOT guess where these images belong on
     * the final Ventoy disk layout.  The caller must supply the correct LBA.
     */
    public synchronized boolean writeAssetToDisk(
        String assetName,
        long startLba
    ) {

        if (!isOpen()) {

            fail(
                ERROR_OPEN_DEVICE,
                "USB Mass Storage device is not open."
            );

            return false;
        }

        if (
            assetName == null ||
            assetName.length() == 0
        ) {

            fail(
                ERROR_ARGUMENT,
                "assetName is empty."
            );

            return false;
        }

        if (blockSize <= 0) {

            fail(
                ERROR_SCSI,
                "Logical block size is unknown."
            );

            return false;
        }

        InputStream input = null;

        try {

            input =
                context.getAssets().open(
                    assetName
                );

            int chunkBlocks =
                DEFAULT_TRANSFER_BLOCKS;

            byte[] buffer =
                new byte[
                    chunkBlocks *
                    blockSize
                ];

            long currentLba =
                startLba;

            for (;;) {

                int bytesRead =
                    readUpTo(
                        input,
                        buffer,
                        buffer.length
                    );

                if (bytesRead < 0) {
                    break;
                }

                if (bytesRead == 0) {
                    break;
                }

                int sectors =
                    (
                        bytesRead +
                        blockSize -
                        1
                    ) / blockSize;

                int transferBytes =
                    sectors *
                    blockSize;

                if (
                    bytesRead <
                    transferBytes
                ) {

                    Arrays.fill(
                        buffer,
                        bytesRead,
                        transferBytes,
                        (byte)0
                    );
                }

                byte[] chunk =
                    Arrays.copyOf(
                        buffer,
                        transferBytes
                    );

                if (
                    !writeBlocks(
                        currentLba,
                        chunk,
                        sectors
                    )
                ) {
                    return false;
                }

                currentLba +=
                    sectors;

                if (
                    bytesRead <
                    buffer.length
                ) {
                    break;
                }
            }

            lastError = "";
            return true;

        } catch (IOException e) {

            fail(
                ERROR_IO,
                "Asset read failed: " +
                e.getMessage()
            );

            return false;

        } finally {

            if (input != null) {

                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static int readUpTo(
        InputStream input,
        byte[] buffer,
        int maximum
    ) throws IOException {

        int total = 0;

        while (total < maximum) {

            int read =
                input.read(
                    buffer,
                    total,
                    maximum - total
                );

            if (read < 0) {

                return
                    total == 0
                    ? -1
                    : total;
            }

            if (read == 0) {
                break;
            }

            total +=
                read;
        }

        return total;
    }

    /*
     * ---------------------------------------------------------------------
     * SYNCHRONIZE CACHE(10)
     * ---------------------------------------------------------------------
     */

    public synchronized boolean synchronizeCache() {

        if (!isOpen()) {

            fail(
                ERROR_OPEN_DEVICE,
                "USB Mass Storage device is not open."
            );

            return false;
        }

        int tag =
            allocateTag();

        byte[] cbw =
            createSynchronizeCache10Cbw(
                tag
            );

        if (
            bulkOutAll(
                cbw,
                0,
                cbw.length
            ) != cbw.length
        ) {

            fail(
                ERROR_SCSI,
                "SYNCHRONIZE CACHE(10) CBW could not be sent."
            );

            return false;
        }

        boolean result =
            readAndValidateCsw(
                tag
            );

        if (result) {
            lastError = "";
        }

        return result;
    }

    /*
     * ---------------------------------------------------------------------
     * libexfat.so JNI ENTRY POINT
     * ---------------------------------------------------------------------
     */

    /**
     * Native JNI wrapper to be implemented inside libexfat.so.
     *
     * Expected native flow:
     *
     *   Java nativeFormatExfat()
     *       -> configure exfat_format_options
     *       -> exfat_format()
     *       -> exfat write_sectors callback
     *       -> Java writeSectorsFromNative()
     *
     * partitionOffset and volumeLength are expressed in logical sectors.
     */
    private native int nativeFormatExfat(
        long partitionOffset,
        long volumeLength,
        int volumeSerial,
        int bytesPerSectorShift,
        int sectorsPerClusterShift
    );

    /**
     * Formats a region of the currently opened USB device as exFAT.
     *
     * This method is synchronous and performs destructive raw writes.
     * Call it from a worker thread, not the Android UI thread.
     */
    public synchronized int formatExfat(
        long partitionOffset,
        long volumeLength,
        int volumeSerial
    ) {

        if (!isOpen()) {

            return fail(
                ERROR_OPEN_DEVICE,
                "USB Mass Storage device is not open."
            );
        }

        if (!EXFAT_LIBRARY_LOADED) {

            return fail(
                ERROR_EXFAT_LIBRARY_UNAVAILABLE,
                "libexfat.so is not loaded."
            );
        }

        if (
            partitionOffset < 0 ||
            volumeLength <= 0
        ) {

            return fail(
                ERROR_ARGUMENT,
                "Invalid exFAT volume region."
            );
        }

        if (
            partitionOffset >
            Long.MAX_VALUE -
            volumeLength
        ) {

            return fail(
                ERROR_ARGUMENT,
                "exFAT volume region overflows."
            );
        }

        long endSector =
            partitionOffset +
            volumeLength;

        if (
            blockCount > 0 &&
            endSector > blockCount
        ) {

            return fail(
                ERROR_ARGUMENT,
                "exFAT volume crosses the end of the USB device."
            );
        }

        int bytesPerSectorShift =
            log2Exact(
                blockSize
            );

        if (
            bytesPerSectorShift < 9 ||
            bytesPerSectorShift > 12
        ) {

            return fail(
                ERROR_UNSUPPORTED_BLOCK_SIZE,
                "Unsupported exFAT logical sector size."
            );
        }

        try {

            Log.i(
                TAG,
                "EXFAT begin: partitionOffset=" +
                partitionOffset +
                ", volumeLength=" +
                volumeLength +
                ", blockSize=" +
                blockSize +
                ", blockCount=" +
                blockCount
            );

            int result =
                nativeFormatExfat(
                    partitionOffset,
                    volumeLength,
                    volumeSerial,
                    bytesPerSectorShift,
                    EXFAT_AUTO_CLUSTER_SHIFT
                );

            if (result == RESULT_OK) {

                /*
                 * The native formatter has already completed every exFAT
                 * metadata WRITE(10). Its flush callback may attempt
                 * SYNCHRONIZE CACHE(10), but some USB Mass Storage devices
                 * reject that command even though all writes succeeded.
                 *
                 * Do not issue a second redundant SYNCHRONIZE CACHE(10)
                 * here and do not turn an unsupported cache command into
                 * a formatting failure.
                 */
                lastError = "";

                Log.i(
                    TAG,
                    "EXFAT format completed successfully."
                );

                return RESULT_OK;
            }

            String callbackError =
                lastError;

            String message =
                "libexfat formatter returned " +
                result +
                ".";

            if (
                callbackError != null &&
                callbackError.length() > 0
            ) {

                message +=
                    " Last Java USB error: " +
                    callbackError;
            }

            Log.e(
                TAG,
                "EXFAT failed: nativeResult=" +
                result +
                ", partitionOffset=" +
                partitionOffset +
                ", volumeLength=" +
                volumeLength +
                ", lastUsbError=" +
                callbackError
            );

            return fail(
                result,
                message
            );

        } catch (UnsatisfiedLinkError e) {

            return fail(
                ERROR_NATIVE_EXFAT,
                "libexfat JNI bridge is missing: " +
                e.getMessage()
            );

        } catch (Throwable e) {

            return fail(
                ERROR_NATIVE_EXFAT,
                "libexfat formatter failed: " +
                e.getMessage()
            );
        }
    }

    /**
     * Convenience wrapper for formatting the complete media as one exFAT
     * volume (super-floppy layout).
     *
     * Do NOT use this when a partition table must remain on the device.
     */
    public synchronized int formatWholeDeviceExfat(
        int volumeSerial
    ) {

        if (blockCount <= 0) {

            return fail(
                ERROR_SCSI,
                "Device capacity is unknown."
            );
        }

        return formatExfat(
            0,
            blockCount,
            volumeSerial
        );
    }

    /*
     * ---------------------------------------------------------------------
     * CALLBACKS FOR libexfat.so
     * ---------------------------------------------------------------------
     */

    /**
     * Called by libexfat.so from its exfat write_sectors callback.
     *
     * IMPORTANT:
     * lba is already MEDIA-RELATIVE / PHYSICAL for the currently opened USB
     * device because libexfat.c adds partition_offset before invoking its
     * writer callback.
     *
     * Native side return contract:
     *
     *   0  success
     *  -1  failure
     */
    @SuppressWarnings("unused")
    private synchronized int writeSectorsFromNative(
        long lba,
        byte[] data,
        int sectorCount
    ) {

        if (
            data == null ||
            sectorCount <= 0
        ) {

            Log.e(
                TAG,
                "EXFAT callback received invalid write request. " +
                "physicalLba=" + lba +
                ", sectors=" + sectorCount +
                ", bytes=" +
                (data == null ? -1 : data.length)
            );

            return -1;
        }

        if (
            !writeBlocks(
                lba,
                data,
                sectorCount
            )
        ) {

            Log.e(
                TAG,
                "EXFAT callback WRITE(10) failed. " +
                "physicalLba=" + lba +
                ", sectors=" + sectorCount +
                ", bytes=" + data.length +
                ", error=" + lastError
            );

            return -1;
        }

        return 0;
    }

    /**
     * Called by libexfat.so from its optional flush callback.
     */
    @SuppressWarnings("unused")
    private synchronized int flushFromNative() {

        boolean result =
            synchronizeCache();

        if (!result) {

            Log.e(
                TAG,
                "EXFAT flush failed: " +
                lastError
            );
        }

        return
            result
            ? 0
            : -1;
    }

    /*
     * ---------------------------------------------------------------------
     * OPTIONAL BOT RESET RECOVERY
     * ---------------------------------------------------------------------
     */

    /**
     * Performs the USB Mass Storage Bulk-Only Reset class request.
     *
     * This should be used for error recovery, not before every command.
     */
    public synchronized boolean bulkOnlyReset() {

        if (
            connection == null ||
            massStorageInterface == null
        ) {

            fail(
                ERROR_OPEN_DEVICE,
                "USB Mass Storage device is not open."
            );

            return false;
        }

        /*
         * bmRequestType:
         *
         * Host-to-device | Class | Interface
         * 0x00           | 0x20  | 0x01 = 0x21
         *
         * bRequest = FFh (Bulk-Only Mass Storage Reset)
         */
        int result =
            connection.controlTransfer(
                0x21,
                0xFF,
                0,
                massStorageInterface.getId(),
                null,
                0,
                USB_TIMEOUT_MS
            );

        if (result < 0) {

            fail(
                ERROR_SCSI,
                "Bulk-Only Mass Storage Reset failed."
            );

            return false;
        }

        clearEndpointHalt(
            bulkInEndpoint
        );

        clearEndpointHalt(
            bulkOutEndpoint
        );

        lastError = "";
        return true;
    }

    private void clearEndpointHalt(
        UsbEndpoint endpoint
    ) {

        if (
            connection == null ||
            endpoint == null
        ) {
            return;
        }

        /*
         * Standard CLEAR_FEATURE(ENDPOINT_HALT):
         *
         * bmRequestType = 0x02
         * bRequest      = 0x01
         * wValue        = 0
         * wIndex        = endpoint address
         */
        connection.controlTransfer(
            0x02,
            0x01,
            0,
            endpoint.getAddress(),
            null,
            0,
            USB_TIMEOUT_MS
        );
    }

    /*
     * ---------------------------------------------------------------------
     * ERROR TEXT
     * ---------------------------------------------------------------------
     */

    public static String errorToString(
        int code
    ) {

        switch (code) {

            case RESULT_OK:
                return "success";

            case ERROR_ARGUMENT:
                return "invalid argument";

            case ERROR_USB_MANAGER:
                return "UsbManager unavailable";

            case ERROR_DEVICE_NOT_FOUND:
                return "USB Mass Storage device not found";

            case ERROR_PERMISSION_REQUIRED:
                return "USB permission required";

            case ERROR_INTERFACE_NOT_FOUND:
                return "SCSI/BOT interface not found";

            case ERROR_ENDPOINT_NOT_FOUND:
                return "Bulk IN/OUT endpoint not found";

            case ERROR_OPEN_DEVICE:
                return "USB device open failed";

            case ERROR_CLAIM_INTERFACE:
                return "USB interface claim failed";

            case ERROR_SCSI:
                return "SCSI/BOT command failed";

            case ERROR_UNSUPPORTED_BLOCK_SIZE:
                return "unsupported logical block size";

            case ERROR_UNSUPPORTED_CAPACITY:
                return "device capacity requires SCSI(16) support";

            case ERROR_IO:
                return "USB media I/O failed";

            case ERROR_NATIVE_EXFAT:
                return "libexfat JNI bridge failed";

            case ERROR_EXFAT_LIBRARY_UNAVAILABLE:
                return "libexfat.so unavailable";

            default:

                /*
                 * -1..-5 may also be the original libexfat formatter
                 * return values.  getLastError() contains the immediate
                 * operation-specific text.
                 */
                return "error code " + code;
        }
    }
}

/*
 * =========================================================================
 * JNI CLASS NAME REQUIRED BY THE REPOSITORY'S CURRENT libscsi.c
 * =========================================================================
 *
 * Current native symbols are named:
 *
 *   Java_com_werismoln_multibooter_UsbScsiBridge_generateWrite10CBW
 *   Java_com_werismoln_multibooter_UsbScsiBridge_parseReadCapacity
 *
 * Therefore this package-private top-level class intentionally lives in the
 * same UsbVentoy.java compilation unit.
 *
 * generateWrite10CBW() is declared for ABI compatibility with libscsi.so,
 * but UsbVentoy currently creates WRITE(10) CBWs in Java because the current
 * libscsi.c implementation needs its CDB byte-order generation corrected.
 */
final class UsbScsiBridge {

    private UsbScsiBridge() {
    }

    static native byte[] generateWrite10CBW(
        int tag,
        int lba,
        short sectorCount
    );

    static native long parseReadCapacity(
        byte[] capacityData
    );
}
