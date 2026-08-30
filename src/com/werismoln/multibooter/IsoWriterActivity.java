package com.werismoln.multibooter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class IsoWriterActivity extends Activity {

    private static final int REQUEST_OPEN_ISO = 4201;

    private static final int PAGE_SELECT = 1;
    private static final int PAGE_WRITE = 2;

    private static final String STATE_PAGE =
        "iso_writer_page";

    private static final String STATE_ISO_URI =
        "iso_writer_iso_uri";

    private static final String STATE_DEVICE_ID =
        "iso_writer_device_id";

    private static final int MASS_STORAGE_SUBCLASS_SCSI =
        0x06;

    private static final int MASS_STORAGE_PROTOCOL_BOT =
        0x50;

    /*
     * Keep WRITE(10) commands moderate.
     *
     * 128 * 512 bytes = 64 KiB on normal USB flash drives.
     * UsbVentoy itself further splits Android bulkTransfer() calls.
     */
    private static final int TRANSFER_BLOCKS =
        128;

    /*
     * A bootable ISO written byte-for-byte to ordinary USB media is expected
     * to target 512-byte logical sectors. Most USB flash drives expose 512.
     *
     * Supporting 4Kn raw media safely requires a separate compatibility path
     * because partition-table LBAs inside hybrid ISOs are normally authored
     * for 512-byte logical sectors.
     */
    private static final int REQUIRED_LOGICAL_BLOCK_SIZE =
        512;

    private int currentPage =
        PAGE_SELECT;

    private Uri isoUri;

    private String isoDisplayName =
        "";

    private long isoSizeBytes =
        -1;

    private int selectedDeviceId =
        -1;

    private UsbDevice selectedDevice;

    private DeviceCapacity selectedCapacity;

    private UsbManager usbManager;

    /*
     * Used only for device permission / capacity probing while the UI is idle.
     * The actual destructive write gets its own UsbVentoy instance.
     */
    private UsbVentoy probeUsb;

    private boolean receiverRegistered =
        false;

    private boolean capacityReadRunning =
        false;

    private boolean writeRunning =
        false;

    private int capacityGeneration =
        0;

    private TextView isoNameView;
    private TextView isoDetailsView;
    private TextView usbNameView;
    private TextView usbDetailsView;
    private Button continueButton;

    private static final class DeviceCapacity {

        final long blockCount;
        final int blockSize;
        final long totalBytes;

        DeviceCapacity(
            long blockCount,
            int blockSize
        ) {

            this.blockCount =
                blockCount;

            this.blockSize =
                blockSize;

            if (
                blockCount > 0 &&
                blockSize > 0 &&
                blockCount <=
                    Long.MAX_VALUE / blockSize
            ) {

                totalBytes =
                    blockCount *
                    (long) blockSize;

            } else {

                totalBytes =
                    -1;
            }
        }
    }

    private static final class IsoInfo {

        final String name;
        final long size;

        IsoInfo(
            String name,
            long size
        ) {

            this.name =
                name == null
                ? ""
                : name;

            this.size =
                size;
        }
    }

    private static final class WriteResult {

        final boolean success;
        final String message;

        WriteResult(
            boolean success,
            String message
        ) {

            this.success =
                success;

            this.message =
                message == null
                ? ""
                : message;
        }
    }

    private final BroadcastReceiver usbReceiver =
        new BroadcastReceiver() {

            @Override
            public void onReceive(
                Context context,
                Intent intent
            ) {

                if (intent == null) {
                    return;
                }

                String action =
                    intent.getAction();

                if (
                    UsbVentoy.ACTION_USB_PERMISSION.equals(
                        action
                    )
                ) {

                    UsbDevice device =
                        (UsbDevice)
                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE
                        );

                    boolean granted =
                        intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false
                        );

                    if (device == null) {
                        return;
                    }

                    if (
                        selectedDeviceId != -1 &&
                        device.getDeviceId() !=
                            selectedDeviceId
                    ) {

                        return;
                    }

                    if (granted) {

                        selectedDevice =
                            device;

                        selectedDeviceId =
                            device.getDeviceId();

                        beginCapacityRead(
                            device
                        );

                    } else {

                        clearSelectedDevice();

                        Toast.makeText(
                            IsoWriterActivity.this,
                            "USB permission was denied.",
                            Toast.LENGTH_SHORT
                        ).show();
                    }

                    return;
                }

                if (
                    UsbManager.ACTION_USB_DEVICE_DETACHED.equals(
                        action
                    )
                ) {

                    UsbDevice device =
                        (UsbDevice)
                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE
                        );

                    if (
                        device == null ||
                        device.getDeviceId() !=
                            selectedDeviceId
                    ) {

                        return;
                    }

                    capacityGeneration++;

                    selectedDevice =
                        null;

                    selectedCapacity =
                        null;

                    selectedDeviceId =
                        -1;

                    if (probeUsb != null) {
                        probeUsb.close();
                    }

                    if (writeRunning) {

                        Toast.makeText(
                            IsoWriterActivity.this,
                            "USB device disconnected during ISO writing.",
                            Toast.LENGTH_LONG
                        ).show();

                    } else {

                        currentPage =
                            PAGE_SELECT;

                        showSelectPage();

                        Toast.makeText(
                            IsoWriterActivity.this,
                            "Selected USB device was disconnected.",
                            Toast.LENGTH_SHORT
                        ).show();
                    }

                    return;
                }

                if (
                    UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(
                        action
                    )
                ) {

                    if (
                        currentPage ==
                        PAGE_SELECT
                    ) {

                        updateSelectionUi();
                    }
                }
            }
        };

    @Override
    protected void onCreate(
        Bundle savedInstanceState
    ) {

        super.onCreate(
            savedInstanceState
        );

        usbManager =
            (UsbManager)
            getSystemService(
                Context.USB_SERVICE
            );

        probeUsb =
            new UsbVentoy(
                this
            );

        if (
            savedInstanceState !=
            null
        ) {

            currentPage =
                savedInstanceState.getInt(
                    STATE_PAGE,
                    PAGE_SELECT
                );

            selectedDeviceId =
                savedInstanceState.getInt(
                    STATE_DEVICE_ID,
                    -1
                );

            String uriString =
                savedInstanceState.getString(
                    STATE_ISO_URI
                );

            if (
                uriString != null &&
                uriString.length() > 0
            ) {

                try {

                    isoUri =
                        Uri.parse(
                            uriString
                        );

                    restoreIsoInfo();

                } catch (
                    Throwable ignored
                ) {

                    clearIso();
                }
            }
        }

        registerUsbReceiver();
        restoreSelectedDevice();
        showCurrentPage();
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (
            selectedDeviceId != -1 &&
            selectedCapacity == null &&
            !capacityReadRunning &&
            !writeRunning
        ) {

            restoreSelectedDevice();
        }

        if (
            currentPage ==
            PAGE_SELECT
        ) {

            updateSelectionUi();
        }
    }

    @Override
    protected void onDestroy() {

        capacityGeneration++;

        if (probeUsb != null) {
            probeUsb.close();
        }

        unregisterUsbReceiver();

        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(
        Bundle outState
    ) {

        outState.putInt(
            STATE_PAGE,
            currentPage
        );

        outState.putInt(
            STATE_DEVICE_ID,
            selectedDeviceId
        );

        if (isoUri != null) {

            outState.putString(
                STATE_ISO_URI,
                isoUri.toString()
            );
        }

        super.onSaveInstanceState(
            outState
        );
    }

    private void registerUsbReceiver() {

        if (receiverRegistered) {
            return;
        }

        IntentFilter filter =
            new IntentFilter();

        filter.addAction(
            UsbVentoy.ACTION_USB_PERMISSION
        );

        filter.addAction(
            UsbManager.ACTION_USB_DEVICE_ATTACHED
        );

        filter.addAction(
            UsbManager.ACTION_USB_DEVICE_DETACHED
        );

        if (
            Build.VERSION.SDK_INT >= 33
        ) {

            registerReceiver(
                usbReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            );

        } else {

            registerReceiver(
                usbReceiver,
                filter
            );
        }

        receiverRegistered =
            true;
    }

    private void unregisterUsbReceiver() {

        if (!receiverRegistered) {
            return;
        }

        try {

            unregisterReceiver(
                usbReceiver
            );

        } catch (
            IllegalArgumentException ignored
        ) {
        }

        receiverRegistered =
            false;
    }

    private void showCurrentPage() {

        if (
            currentPage ==
            PAGE_WRITE
        ) {

            if (
                !isReadyToWrite()
            ) {

                currentPage =
                    PAGE_SELECT;

                showSelectPage();
                return;
            }

            showWritePage();

        } else {

            showSelectPage();
        }
    }

    /*
     * ---------------------------------------------------------------------
     * PAGE 1: ISO + USB SELECTION
     * ---------------------------------------------------------------------
     */

    private void showSelectPage() {

        currentPage =
            PAGE_SELECT;

        setContentView(
            R.layout.iso_writer_select
        );

        TextView backTop =
            (TextView)
            findViewById(
                R.id.iso_writer_back_top
            );

        Button selectIso =
            (Button)
            findViewById(
                R.id.iso_writer_select_iso
            );

        Button selectUsb =
            (Button)
            findViewById(
                R.id.iso_writer_select_usb
            );

        Button cancel =
            (Button)
            findViewById(
                R.id.iso_writer_cancel
            );

        continueButton =
            (Button)
            findViewById(
                R.id.iso_writer_continue
            );

        isoNameView =
            (TextView)
            findViewById(
                R.id.iso_writer_iso_name
            );

        isoDetailsView =
            (TextView)
            findViewById(
                R.id.iso_writer_iso_details
            );

        usbNameView =
            (TextView)
            findViewById(
                R.id.iso_writer_usb_name
            );

        usbDetailsView =
            (TextView)
            findViewById(
                R.id.iso_writer_usb_details
            );

        addTouchFeedback(
            selectIso
        );

        addTouchFeedback(
            selectUsb
        );

        addTouchFeedback(
            cancel
        );

        addTouchFeedback(
            continueButton
        );

        View.OnClickListener finishListener =
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    finish();
                }
            };

        backTop.setOnClickListener(
            finishListener
        );

        cancel.setOnClickListener(
            finishListener
        );

        selectIso.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    openIsoPicker();
                }
            }
        );

        selectUsb.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    showUsbDeviceChooser();
                }
            }
        );

        continueButton.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    if (!isReadyToWrite()) {

                        Toast.makeText(
                            IsoWriterActivity.this,
                            "Select a valid ISO and USB flash drive first.",
                            Toast.LENGTH_SHORT
                        ).show();

                        return;
                    }

                    currentPage =
                        PAGE_WRITE;

                    showWritePage();
                }
            }
        );

        updateSelectionUi();
    }

    private void openIsoPicker() {

        Intent intent =
            new Intent(
                Intent.ACTION_OPEN_DOCUMENT
            );

        intent.addCategory(
            Intent.CATEGORY_OPENABLE
        );

        /*
         * Some Android document providers do not publish a specific ISO MIME
         * type. Using a wildcard MIME type keeps legitimate .iso files visible.
         */
        intent.setType(
            "*/*"
        );

        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );

        try {

            startActivityForResult(
                intent,
                REQUEST_OPEN_ISO
            );

        } catch (Throwable e) {

            Toast.makeText(
                this,
                "Could not open the Android file picker.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        );

        if (
            requestCode !=
            REQUEST_OPEN_ISO
        ) {

            return;
        }

        if (
            resultCode !=
                RESULT_OK ||
            data == null ||
            data.getData() == null
        ) {

            return;
        }

        Uri uri =
            data.getData();

        int takeFlags =
            data.getFlags() &
            (
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );

        try {

            getContentResolver()
                .takePersistableUriPermission(
                    uri,
                    takeFlags &
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                );

        } catch (
            Throwable ignored
        ) {

            /*
             * Some providers grant only temporary access.
             * The current Activity session can still use it.
             */
        }

        IsoInfo info =
            queryIsoInfo(
                uri
            );

        if (
            info == null ||
            info.size <= 0
        ) {

            Toast.makeText(
                this,
                "Could not determine the ISO file size.",
                Toast.LENGTH_LONG
            ).show();

            return;
        }

        String lowerName =
            info.name.toLowerCase(
                Locale.US
            );

        if (
            !lowerName.endsWith(
                ".iso"
            )
        ) {

            new AlertDialog.Builder(
                this
            )
                .setTitle(
                    "Not an .iso file"
                )
                .setMessage(
                    "The selected file does not have an .iso extension. Select an ISO disk image."
                )
                .setPositiveButton(
                    "OK",
                    null
                )
                .show();

            return;
        }

        isoUri =
            uri;

        isoDisplayName =
            info.name;

        isoSizeBytes =
            info.size;

        updateSelectionUi();
    }

    private void restoreIsoInfo() {

        if (isoUri == null) {
            clearIso();
            return;
        }

        IsoInfo info =
            queryIsoInfo(
                isoUri
            );

        if (
            info == null ||
            info.size <= 0
        ) {

            clearIso();
            return;
        }

        isoDisplayName =
            info.name;

        isoSizeBytes =
            info.size;
    }

    private void clearIso() {

        isoUri =
            null;

        isoDisplayName =
            "";

        isoSizeBytes =
            -1;

        updateSelectionUi();
    }

    private IsoInfo queryIsoInfo(
        Uri uri
    ) {

        if (uri == null) {
            return null;
        }

        String name =
            "";

        long size =
            -1;

        Cursor cursor =
            null;

        try {

            cursor =
                getContentResolver()
                    .query(
                        uri,
                        new String[] {
                            OpenableColumns.DISPLAY_NAME,
                            OpenableColumns.SIZE
                        },
                        null,
                        null,
                        null
                    );

            if (
                cursor != null &&
                cursor.moveToFirst()
            ) {

                int nameIndex =
                    cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                    );

                int sizeIndex =
                    cursor.getColumnIndex(
                        OpenableColumns.SIZE
                    );

                if (
                    nameIndex >= 0 &&
                    !cursor.isNull(
                        nameIndex
                    )
                ) {

                    name =
                        cursor.getString(
                            nameIndex
                        );
                }

                if (
                    sizeIndex >= 0 &&
                    !cursor.isNull(
                        sizeIndex
                    )
                ) {

                    size =
                        cursor.getLong(
                            sizeIndex
                        );
                }
            }

        } catch (
            Throwable ignored
        ) {

        } finally {

            if (cursor != null) {

                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }

        if (
            name == null ||
            name.length() == 0
        ) {

            String segment =
                uri.getLastPathSegment();

            name =
                segment == null
                ? "Selected ISO"
                : segment;
        }

        if (size <= 0) {

            ParcelFileDescriptor pfd =
                null;

            try {

                pfd =
                    getContentResolver()
                        .openFileDescriptor(
                            uri,
                            "r"
                        );

                if (pfd != null) {

                    long stat =
                        pfd.getStatSize();

                    if (stat > 0) {
                        size = stat;
                    }
                }

            } catch (
                Throwable ignored
            ) {

            } finally {

                if (pfd != null) {

                    try {
                        pfd.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        return new IsoInfo(
            name,
            size
        );
    }

    /*
     * ---------------------------------------------------------------------
     * USB DEVICE SELECTION / PERMISSION / CAPACITY
     * ---------------------------------------------------------------------
     */

    private void showUsbDeviceChooser() {

        final List<UsbDevice> devices =
            findMassStorageDevices();

        if (devices.isEmpty()) {

            Toast.makeText(
                this,
                "No compatible USB Mass Storage device was found.",
                Toast.LENGTH_LONG
            ).show();

            return;
        }

        String[] labels =
            new String[
                devices.size()
            ];

        for (
            int i = 0;
            i < devices.size();
            i++
        ) {

            labels[i] =
                buildDeviceDialogLabel(
                    devices.get(i)
                );
        }

        new AlertDialog.Builder(
            this
        )
            .setTitle(
                "Select USB Flash Drive"
            )
            .setItems(
                labels,
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        DialogInterface dialog,
                        int which
                    ) {

                        if (
                            which < 0 ||
                            which >=
                                devices.size()
                        ) {

                            return;
                        }

                        prepareSelectedDevice(
                            devices.get(
                                which
                            )
                        );
                    }
                }
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .show();
    }

    private List<UsbDevice>
    findMassStorageDevices() {

        List<UsbDevice> result =
            new ArrayList<UsbDevice>();

        if (usbManager == null) {
            return result;
        }

        Map<String, UsbDevice> map =
            usbManager.getDeviceList();

        for (
            UsbDevice device :
            map.values()
        ) {

            if (
                hasSupportedMassStorageInterface(
                    device
                )
            ) {

                result.add(
                    device
                );
            }
        }

        Collections.sort(
            result,
            new Comparator<UsbDevice>() {

                @Override
                public int compare(
                    UsbDevice a,
                    UsbDevice b
                ) {

                    return
                        getFriendlyDeviceName(a)
                        .compareToIgnoreCase(
                            getFriendlyDeviceName(b)
                        );
                }
            }
        );

        return result;
    }

    private boolean
    hasSupportedMassStorageInterface(
        UsbDevice device
    ) {

        if (device == null) {
            return false;
        }

        for (
            int i = 0;
            i <
                device.getInterfaceCount();
            i++
        ) {

            UsbInterface usbInterface =
                device.getInterface(i);

            if (
                usbInterface.getInterfaceClass() ==
                    UsbConstants.USB_CLASS_MASS_STORAGE &&
                usbInterface.getInterfaceSubclass() ==
                    MASS_STORAGE_SUBCLASS_SCSI &&
                usbInterface.getInterfaceProtocol() ==
                    MASS_STORAGE_PROTOCOL_BOT
            ) {

                return true;
            }
        }

        return false;
    }

    private void prepareSelectedDevice(
        UsbDevice device
    ) {

        if (
            device == null ||
            writeRunning
        ) {

            return;
        }

        capacityGeneration++;

        if (probeUsb != null) {
            probeUsb.close();
        }

        selectedDevice =
            device;

        selectedDeviceId =
            device.getDeviceId();

        selectedCapacity =
            null;

        capacityReadRunning =
            false;

        updateSelectionUi();

        if (
            probeUsb.hasPermission(
                device
            )
        ) {

            beginCapacityRead(
                device
            );

            return;
        }

        int result =
            probeUsb.requestPermission(
                device
            );

        if (
            result !=
                UsbVentoy.RESULT_OK &&
            result !=
                UsbVentoy.ERROR_PERMISSION_REQUIRED
        ) {

            String error =
                probeUsb.getLastError();

            clearSelectedDevice();

            Toast.makeText(
                this,
                error.length() == 0
                    ? "USB permission request failed."
                    : error,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void beginCapacityRead(
        final UsbDevice device
    ) {

        if (
            device == null ||
            capacityReadRunning ||
            writeRunning
        ) {

            return;
        }

        final int deviceId =
            device.getDeviceId();

        final int generation =
            ++capacityGeneration;

        capacityReadRunning =
            true;

        selectedCapacity =
            null;

        updateSelectionUi();

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    int result =
                        probeUsb.open(
                            device
                        );

                    DeviceCapacity capacity =
                        null;

                    String error =
                        "";

                    if (
                        result ==
                        UsbVentoy.RESULT_OK
                    ) {

                        capacity =
                            new DeviceCapacity(
                                probeUsb.getBlockCount(),
                                probeUsb.getBlockSize()
                            );

                    } else {

                        error =
                            probeUsb.getLastError();
                    }

                    probeUsb.close();

                    final DeviceCapacity finalCapacity =
                        capacity;

                    final String finalError =
                        error;

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                if (
                                    isFinishing() ||
                                    generation !=
                                        capacityGeneration ||
                                    selectedDeviceId !=
                                        deviceId ||
                                    writeRunning
                                ) {

                                    return;
                                }

                                capacityReadRunning =
                                    false;

                                if (
                                    finalCapacity ==
                                    null
                                ) {

                                    clearSelectedDevice();

                                    Toast.makeText(
                                        IsoWriterActivity.this,
                                        finalError.length() == 0
                                            ? "Could not read USB device capacity."
                                            : finalError,
                                        Toast.LENGTH_LONG
                                    ).show();

                                    return;
                                }

                                selectedDevice =
                                    device;

                                selectedCapacity =
                                    finalCapacity;

                                updateSelectionUi();
                            }
                        }
                    );
                }
            },
            "IsoWriterUsbCapacity"
        ).start();
    }

    private void restoreSelectedDevice() {

        if (
            usbManager == null ||
            selectedDeviceId ==
                -1 ||
            writeRunning
        ) {

            return;
        }

        UsbDevice restored =
            null;

        for (
            UsbDevice device :
            usbManager
                .getDeviceList()
                .values()
        ) {

            if (
                device.getDeviceId() ==
                selectedDeviceId
            ) {

                restored =
                    device;

                break;
            }
        }

        if (
            restored == null ||
            !hasSupportedMassStorageInterface(
                restored
            )
        ) {

            clearSelectedDevice();
            return;
        }

        selectedDevice =
            restored;

        if (
            selectedCapacity != null
        ) {

            updateSelectionUi();
            return;
        }

        if (
            probeUsb.hasPermission(
                restored
            )
        ) {

            beginCapacityRead(
                restored
            );

        } else {

            updateSelectionUi();
        }
    }

    private void clearSelectedDevice() {

        capacityGeneration++;

        capacityReadRunning =
            false;

        if (probeUsb != null) {
            probeUsb.close();
        }

        selectedDevice =
            null;

        selectedCapacity =
            null;

        selectedDeviceId =
            -1;

        updateSelectionUi();
    }

    private boolean isReadyToWrite() {

        if (
            isoUri == null ||
            isoSizeBytes <= 0 ||
            selectedDevice == null ||
            selectedCapacity == null ||
            selectedCapacity.totalBytes <= 0
        ) {

            return false;
        }

        if (
            selectedCapacity.blockSize !=
            REQUIRED_LOGICAL_BLOCK_SIZE
        ) {

            return false;
        }

        return
            isoSizeBytes <=
            selectedCapacity.totalBytes;
    }

    private void updateSelectionUi() {

        if (
            isoNameView == null ||
            isoDetailsView == null ||
            usbNameView == null ||
            usbDetailsView == null ||
            continueButton == null
        ) {

            return;
        }

        if (
            isoUri == null ||
            isoSizeBytes <= 0
        ) {

            isoNameView.setText(
                "No ISO selected"
            );

            isoDetailsView.setText(
                "Select the ISO disk image that will be written byte-for-byte."
            );

        } else {

            isoNameView.setText(
                isoDisplayName.length() == 0
                    ? "Selected ISO"
                    : isoDisplayName
            );

            isoDetailsView.setText(
                "Size: " +
                formatBytes(
                    isoSizeBytes
                )
            );
        }

        if (
            selectedDevice ==
            null
        ) {

            usbNameView.setText(
                "No USB device selected"
            );

            usbDetailsView.setText(
                "Connect a USB flash drive and select it."
            );

        } else {

            usbNameView.setText(
                getFriendlyDeviceName(
                    selectedDevice
                )
            );

            if (
                selectedCapacity ==
                null
            ) {

                if (
                    capacityReadRunning
                ) {

                    usbDetailsView.setText(
                        buildBasicDeviceDetails(
                            selectedDevice
                        ) +
                        "\nReading capacity..."
                    );

                } else if (
                    probeUsb.hasPermission(
                        selectedDevice
                    )
                ) {

                    usbDetailsView.setText(
                        buildBasicDeviceDetails(
                            selectedDevice
                        ) +
                        "\nPreparing USB device..."
                    );

                } else {

                    usbDetailsView.setText(
                        buildBasicDeviceDetails(
                            selectedDevice
                        ) +
                        "\nWaiting for USB permission..."
                    );
                }

            } else {

                String details =
                    buildSelectedDeviceDetails();

                if (
                    selectedCapacity.blockSize !=
                    REQUIRED_LOGICAL_BLOCK_SIZE
                ) {

                    details +=
                        "\nUnsupported for ISO writing: " +
                        selectedCapacity.blockSize +
                        "-byte logical sectors.";

                } else if (
                    isoSizeBytes > 0 &&
                    isoSizeBytes >
                        selectedCapacity.totalBytes
                ) {

                    details +=
                        "\nISO is larger than this USB device.";
                }

                usbDetailsView.setText(
                    details
                );
            }
        }

        boolean ready =
            isReadyToWrite();

        continueButton.setEnabled(
            ready
        );

        continueButton.setAlpha(
            ready
                ? 1.0f
                : 0.45f
        );
    }

    /*
     * ---------------------------------------------------------------------
     * PAGE 2: DESTRUCTIVE WRITE
     * ---------------------------------------------------------------------
     */

    private void showWritePage() {

        if (!isReadyToWrite()) {

            currentPage =
                PAGE_SELECT;

            showSelectPage();
            return;
        }

        currentPage =
            PAGE_WRITE;

        setContentView(
            R.layout.iso_writer_write
        );

        isoNameView =
            null;

        isoDetailsView =
            null;

        usbNameView =
            null;

        usbDetailsView =
            null;

        continueButton =
            null;

        final TextView backTop =
            (TextView)
            findViewById(
                R.id.iso_writer_write_back_top
            );

        final TextView isoName =
            (TextView)
            findViewById(
                R.id.iso_writer_write_iso_name
            );

        final TextView isoDetails =
            (TextView)
            findViewById(
                R.id.iso_writer_write_iso_details
            );

        final TextView usbName =
            (TextView)
            findViewById(
                R.id.iso_writer_write_usb_name
            );

        final TextView usbDetails =
            (TextView)
            findViewById(
                R.id.iso_writer_write_usb_details
            );

        final ProgressBar progressBar =
            (ProgressBar)
            findViewById(
                R.id.iso_writer_progress
            );

        final TextView progressText =
            (TextView)
            findViewById(
                R.id.iso_writer_progress_text
            );

        final TextView progressDetails =
            (TextView)
            findViewById(
                R.id.iso_writer_progress_details
            );

        final Button back =
            (Button)
            findViewById(
                R.id.iso_writer_write_back
            );

        final Button write =
            (Button)
            findViewById(
                R.id.iso_writer_write_button
            );

        isoName.setText(
            isoDisplayName
        );

        isoDetails.setText(
            "ISO size: " +
            formatBytes(
                isoSizeBytes
            )
        );

        usbName.setText(
            getFriendlyDeviceName(
                selectedDevice
            )
        );

        usbDetails.setText(
            buildSelectedDeviceDetails()
        );

        progressBar.setProgress(
            0
        );

        progressText.setText(
            "Ready"
        );

        progressDetails.setText(
            "No data has been written yet."
        );

        addTouchFeedback(
            back
        );

        addTouchFeedback(
            write
        );

        View.OnClickListener backListener =
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    if (writeRunning) {
                        return;
                    }

                    currentPage =
                        PAGE_SELECT;

                    showSelectPage();
                }
            };

        backTop.setOnClickListener(
            backListener
        );

        back.setOnClickListener(
            backListener
        );

        write.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    if (writeRunning) {
                        return;
                    }

                    showFirstWriteConfirmation(
                        backTop,
                        back,
                        write,
                        progressBar,
                        progressText,
                        progressDetails
                    );
                }
            }
        );
    }

    private void showFirstWriteConfirmation(
        final TextView backTop,
        final Button back,
        final Button write,
        final ProgressBar progressBar,
        final TextView progressText,
        final TextView progressDetails
    ) {

        if (!isReadyToWrite()) {
            return;
        }

        String message =
            getFriendlyDeviceName(
                selectedDevice
            ) +
            "\n" +
            formatBytes(
                selectedCapacity.totalBytes
            ) +
            "\n\nISO: " +
            isoDisplayName +
            "\n" +
            formatBytes(
                isoSizeBytes
            ) +
            "\n\nAll existing partition information and data at the beginning of this USB drive will be overwritten.";

        new AlertDialog.Builder(
            this
        )
            .setTitle(
                "Erase USB drive?"
            )
            .setMessage(
                message
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "CONTINUE",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        DialogInterface dialog,
                        int which
                    ) {

                        showFinalWriteConfirmation(
                            backTop,
                            back,
                            write,
                            progressBar,
                            progressText,
                            progressDetails
                        );
                    }
                }
            )
            .show();
    }

    private void showFinalWriteConfirmation(
        final TextView backTop,
        final Button back,
        final Button write,
        final ProgressBar progressBar,
        final TextView progressText,
        final TextView progressDetails
    ) {

        new AlertDialog.Builder(
            this
        )
            .setTitle(
                "Final confirmation"
            )
            .setMessage(
                "The ISO will now be written directly to the selected USB flash drive starting at LBA 0 using USB Mass Storage BOT and SCSI WRITE(10). This operation cannot be undone."
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "ERASE AND WRITE",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        DialogInterface dialog,
                        int which
                    ) {

                        startIsoWrite(
                            backTop,
                            back,
                            write,
                            progressBar,
                            progressText,
                            progressDetails
                        );
                    }
                }
            )
            .show();
    }

    private void startIsoWrite(
        final TextView backTop,
        final Button back,
        final Button write,
        final ProgressBar progressBar,
        final TextView progressText,
        final TextView progressDetails
    ) {

        if (
            writeRunning ||
            !isReadyToWrite()
        ) {

            return;
        }

        final Uri sourceUri =
            isoUri;

        final String sourceName =
            isoDisplayName;

        final long sourceBytes =
            isoSizeBytes;

        final UsbDevice targetDevice =
            selectedDevice;

        final DeviceCapacity targetCapacity =
            selectedCapacity;

        writeRunning =
            true;

        capacityGeneration++;

        if (probeUsb != null) {
            probeUsb.close();
        }

        backTop.setEnabled(
            false
        );

        backTop.setAlpha(
            0.45f
        );

        back.setEnabled(
            false
        );

        back.setAlpha(
            0.45f
        );

        write.setEnabled(
            false
        );

        write.setAlpha(
            0.45f
        );

        progressBar.setProgress(
            0
        );

        progressText.setText(
            "Opening USB device..."
        );

        progressDetails.setText(
            "0 B / " +
            formatBytes(
                sourceBytes
            )
        );

        setRequestedOrientation(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        );

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final WriteResult result =
                        writeIsoToUsb(
                            sourceUri,
                            sourceName,
                            sourceBytes,
                            targetDevice,
                            targetCapacity,
                            progressBar,
                            progressText,
                            progressDetails
                        );

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                writeRunning =
                                    false;

                                try {

                                    setRequestedOrientation(
                                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    );

                                } catch (
                                    Throwable ignored
                                ) {
                                }

                                getWindow().clearFlags(
                                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                );

                                backTop.setEnabled(
                                    true
                                );

                                backTop.setAlpha(
                                    1.0f
                                );

                                back.setEnabled(
                                    true
                                );

                                back.setAlpha(
                                    1.0f
                                );

                                if (
                                    result.success
                                ) {

                                    progressBar.setProgress(
                                        100
                                    );

                                    progressText.setText(
                                        "ISO writing completed."
                                    );

                                    progressDetails.setText(
                                        formatBytes(
                                            sourceBytes
                                        ) +
                                        " written successfully."
                                    );

                                    write.setEnabled(
                                        false
                                    );

                                    write.setAlpha(
                                        0.45f
                                    );

                                    write.setText(
                                        "COMPLETED"
                                    );

                                    Toast.makeText(
                                        IsoWriterActivity.this,
                                        "ISO was written successfully.",
                                        Toast.LENGTH_LONG
                                    ).show();

                                } else {

                                    progressText.setText(
                                        "ISO writing failed."
                                    );

                                    progressDetails.setText(
                                        result.message.length() == 0
                                            ? "An unknown USB/SCSI error occurred."
                                            : result.message
                                    );

                                    write.setEnabled(
                                        true
                                    );

                                    write.setAlpha(
                                        1.0f
                                    );

                                    write.setText(
                                        "TRY AGAIN"
                                    );

                                    Toast.makeText(
                                        IsoWriterActivity.this,
                                        result.message.length() == 0
                                            ? "ISO writing failed."
                                            : result.message,
                                        Toast.LENGTH_LONG
                                    ).show();
                                }
                            }
                        }
                    );
                }
            },
            "IsoRawWriter"
        ).start();
    }

    private WriteResult writeIsoToUsb(
        Uri sourceUri,
        String sourceName,
        long sourceSize,
        UsbDevice targetDevice,
        DeviceCapacity expectedCapacity,
        final ProgressBar progressBar,
        final TextView progressText,
        final TextView progressDetails
    ) {

        if (
            sourceUri == null ||
            sourceSize <= 0 ||
            targetDevice == null ||
            expectedCapacity == null
        ) {

            return new WriteResult(
                false,
                "Invalid ISO writer state."
            );
        }

        UsbVentoy usb =
            new UsbVentoy(
                this
            );

        InputStream input =
            null;

        try {

            postProgress(
                progressBar,
                progressText,
                progressDetails,
                0,
                "Opening USB device...",
                "0 B / " +
                formatBytes(
                    sourceSize
                )
            );

            if (
                !usb.hasPermission(
                    targetDevice
                )
            ) {

                return new WriteResult(
                    false,
                    "USB permission is no longer granted."
                );
            }

            int openResult =
                usb.open(
                    targetDevice
                );

            if (
                openResult !=
                UsbVentoy.RESULT_OK
            ) {

                return new WriteResult(
                    false,
                    prefixError(
                        "Could not open USB flash drive",
                        usb.getLastError()
                    )
                );
            }

            if (
                usb.getBlockSize() !=
                REQUIRED_LOGICAL_BLOCK_SIZE
            ) {

                return new WriteResult(
                    false,
                    "ISO raw writing currently requires a USB device with 512-byte logical sectors."
                );
            }

            long capacityBytes =
                safeCapacityBytes(
                    usb.getBlockCount(),
                    usb.getBlockSize()
                );

            if (
                capacityBytes <= 0
            ) {

                return new WriteResult(
                    false,
                    "USB capacity is invalid."
                );
            }

            if (
                sourceSize >
                capacityBytes
            ) {

                return new WriteResult(
                    false,
                    "The ISO is larger than the selected USB flash drive."
                );
            }

            if (
                expectedCapacity.totalBytes > 0 &&
                expectedCapacity.totalBytes !=
                    capacityBytes
            ) {

                /*
                 * The device was reopened for the destructive operation.
                 * If its reported capacity changed, stop before touching LBA 0.
                 */
                return new WriteResult(
                    false,
                    "USB capacity changed after device selection. Re-select the USB device."
                );
            }

            ContentResolver resolver =
                getContentResolver();

            input =
                resolver.openInputStream(
                    sourceUri
                );

            if (input == null) {

                return new WriteResult(
                    false,
                    "Could not open the selected ISO file."
                );
            }

            final int blockSize =
                usb.getBlockSize();

            final int bufferSize =
                TRANSFER_BLOCKS *
                blockSize;

            byte[] buffer =
                new byte[
                    bufferSize
                ];

            long currentLba =
                0;

            long sourceBytesRead =
                0;

            long startedAt =
                System.nanoTime();

            postProgress(
                progressBar,
                progressText,
                progressDetails,
                0,
                "Writing ISO to USB...",
                "0 B / " +
                formatBytes(
                    sourceSize
                )
            );

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

                /*
                 * We write complete USB logical blocks.
                 * ISO files are normally sector-aligned, but the final block is
                 * zero-padded instead of silently dropping trailing bytes.
                 */
                int sectors =
                    (
                        bytesRead +
                        blockSize -
                        1
                    ) /
                    blockSize;

                int transferBytes =
                    sectors *
                    blockSize;

                byte[] payload;

                if (
                    transferBytes ==
                    bytesRead
                ) {

                    payload =
                        Arrays.copyOf(
                            buffer,
                            bytesRead
                        );

                } else {

                    payload =
                        new byte[
                            transferBytes
                        ];

                    System.arraycopy(
                        buffer,
                        0,
                        payload,
                        0,
                        bytesRead
                    );
                }

                if (
                    !usb.writeBlocks(
                        currentLba,
                        payload,
                        sectors
                    )
                ) {

                    return new WriteResult(
                        false,
                        prefixError(
                            "SCSI WRITE(10) failed at LBA " +
                            currentLba,
                            usb.getLastError()
                        )
                    );
                }

                currentLba +=
                    sectors;

                sourceBytesRead +=
                    bytesRead;

                if (
                    sourceBytesRead >
                    sourceSize
                ) {

                    return new WriteResult(
                        false,
                        "The ISO source returned more data than its reported size."
                    );
                }

                int percent =
                    (int)
                    Math.min(
                        99L,
                        (
                            sourceBytesRead *
                            100L
                        ) /
                        sourceSize
                    );

                long elapsedNanos =
                    System.nanoTime() -
                    startedAt;

                double seconds =
                    elapsedNanos <= 0
                        ? 0.0
                        : elapsedNanos /
                            1000000000.0;

                double bytesPerSecond =
                    seconds <= 0.0
                        ? 0.0
                        : sourceBytesRead /
                            seconds;

                String detail =
                    formatBytes(
                        sourceBytesRead
                    ) +
                    " / " +
                    formatBytes(
                        sourceSize
                    );

                if (
                    bytesPerSecond >
                    0.0
                ) {

                    detail +=
                        "  •  " +
                        formatRate(
                            bytesPerSecond
                        );
                }

                postProgress(
                    progressBar,
                    progressText,
                    progressDetails,
                    percent,
                    "Writing " +
                    sourceName +
                    "...",
                    detail
                );
            }

            if (
                sourceBytesRead !=
                sourceSize
            ) {

                return new WriteResult(
                    false,
                    "ISO read ended early. Expected " +
                    sourceSize +
                    " bytes but read " +
                    sourceBytesRead +
                    "."
                );
            }

            postProgress(
                progressBar,
                progressText,
                progressDetails,
                99,
                "Synchronizing USB cache...",
                formatBytes(
                    sourceBytesRead
                ) +
                " written"
            );

            if (
                !usb.synchronizeCache()
            ) {

                return new WriteResult(
                    false,
                    prefixError(
                        "ISO data was sent but USB cache synchronization failed",
                        usb.getLastError()
                    )
                );
            }

            return new WriteResult(
                true,
                ""
            );

        } catch (
            SecurityException e
        ) {

            return new WriteResult(
                false,
                "Permission to read the selected ISO was lost."
            );

        } catch (
            IOException e
        ) {

            return new WriteResult(
                false,
                "ISO read failed: " +
                safeMessage(
                    e
                )
            );

        } catch (
            Throwable e
        ) {

            return new WriteResult(
                false,
                "ISO writer failed: " +
                safeMessage(
                    e
                )
            );

        } finally {

            if (input != null) {

                try {
                    input.close();
                } catch (Throwable ignored) {
                }
            }

            usb.close();
        }
    }

    private void postProgress(
        final ProgressBar progressBar,
        final TextView progressText,
        final TextView progressDetails,
        final int percent,
        final String message,
        final String details
    ) {

        runOnUiThread(
            new Runnable() {

                @Override
                public void run() {

                    if (
                        isFinishing()
                    ) {

                        return;
                    }

                    progressBar.setProgress(
                        Math.max(
                            0,
                            Math.min(
                                100,
                                percent
                            )
                        )
                    );

                    progressText.setText(
                        message
                    );

                    progressDetails.setText(
                        details
                    );
                }
            }
        );
    }

    private static int readUpTo(
        InputStream input,
        byte[] buffer,
        int maximum
    ) throws IOException {

        int total =
            0;

        while (
            total <
            maximum
        ) {

            int read =
                input.read(
                    buffer,
                    total,
                    maximum -
                    total
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
     * UI / DISPLAY HELPERS
     * ---------------------------------------------------------------------
     */

    private String buildSelectedDeviceDetails() {

        if (
            selectedDevice == null ||
            selectedCapacity == null
        ) {

            return "";
        }

        return
            buildBasicDeviceDetails(
                selectedDevice
            ) +
            "\nCapacity: " +
            formatBytes(
                selectedCapacity.totalBytes
            ) +
            "\nLogical sector: " +
            selectedCapacity.blockSize +
            " bytes";
    }

    private String buildBasicDeviceDetails(
        UsbDevice device
    ) {

        if (device == null) {
            return "";
        }

        return
            String.format(
                Locale.US,
                "VID %04X  •  PID %04X",
                device.getVendorId(),
                device.getProductId()
            );
    }

    private String buildDeviceDialogLabel(
        UsbDevice device
    ) {

        return
            getFriendlyDeviceName(
                device
            ) +
            "\n" +
            buildBasicDeviceDetails(
                device
            );
    }

    private String getFriendlyDeviceName(
        UsbDevice device
    ) {

        if (device == null) {
            return "USB flash drive";
        }

        String manufacturer =
            null;

        String product =
            null;

        try {
            manufacturer =
                device.getManufacturerName();
        } catch (Throwable ignored) {
        }

        try {
            product =
                device.getProductName();
        } catch (Throwable ignored) {
        }

        manufacturer =
            cleanText(
                manufacturer
            );

        product =
            cleanText(
                product
            );

        if (
            manufacturer.length() > 0 &&
            product.length() > 0
        ) {

            if (
                product.toLowerCase(
                    Locale.US
                ).startsWith(
                    manufacturer.toLowerCase(
                        Locale.US
                    )
                )
            ) {

                return product;
            }

            return
                manufacturer +
                " " +
                product;
        }

        if (
            product.length() > 0
        ) {

            return product;
        }

        if (
            manufacturer.length() > 0
        ) {

            return
                manufacturer +
                " USB";
        }

        return String.format(
            Locale.US,
            "USB %04X:%04X",
            device.getVendorId(),
            device.getProductId()
        );
    }

    private static String cleanText(
        String value
    ) {

        if (value == null) {
            return "";
        }

        return
            value.trim();
    }

    private static long safeCapacityBytes(
        long blocks,
        int blockSize
    ) {

        if (
            blocks <= 0 ||
            blockSize <= 0 ||
            blocks >
                Long.MAX_VALUE /
                blockSize
        ) {

            return -1;
        }

        return
            blocks *
            (long) blockSize;
    }

    private static String prefixError(
        String prefix,
        String detail
    ) {

        if (
            detail == null ||
            detail.length() == 0
        ) {

            return prefix + ".";
        }

        return
            prefix +
            ": " +
            detail;
    }

    private static String safeMessage(
        Throwable throwable
    ) {

        if (throwable == null) {
            return "unknown error";
        }

        String message =
            throwable.getMessage();

        if (
            message == null ||
            message.length() == 0
        ) {

            return
                throwable.getClass()
                    .getSimpleName();
        }

        return message;
    }

    private static String formatBytes(
        long bytes
    ) {

        if (bytes < 0) {
            return "Unknown";
        }

        double value =
            bytes;

        String[] units =
            new String[] {
                "B",
                "KiB",
                "MiB",
                "GiB",
                "TiB"
            };

        int unit =
            0;

        while (
            value >= 1024.0 &&
            unit <
                units.length - 1
        ) {

            value /=
                1024.0;

            unit++;
        }

        if (unit == 0) {

            return
                Long.toString(
                    bytes
                ) +
                " B";
        }

        return String.format(
            Locale.US,
            "%.2f %s",
            value,
            units[unit]
        );
    }

    private static String formatRate(
        double bytesPerSecond
    ) {

        if (
            bytesPerSecond <=
            0.0
        ) {

            return "";
        }

        return
            formatBytes(
                (long)
                bytesPerSecond
            ) +
            "/s";
    }

    private void addTouchFeedback(
        final View view
    ) {

        if (view == null) {
            return;
        }

        view.setOnTouchListener(
            new View.OnTouchListener() {

                @Override
                public boolean onTouch(
                    View v,
                    MotionEvent event
                ) {

                    if (!v.isEnabled()) {
                        return false;
                    }

                    switch (
                        event.getAction()
                    ) {

                        case MotionEvent.ACTION_DOWN:

                            v.animate()
                                .alpha(0.55f)
                                .setDuration(60)
                                .start();

                            break;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:

                            v.animate()
                                .alpha(1.0f)
                                .setDuration(180)
                                .start();

                            break;
                    }

                    return false;
                }
            }
        );
    }

    @Override
    public void onBackPressed() {

        if (writeRunning) {
            return;
        }

        if (
            currentPage ==
            PAGE_WRITE
        ) {

            currentPage =
                PAGE_SELECT;

            showSelectPage();

            return;
        }

        super.onBackPressed();
    }
}
