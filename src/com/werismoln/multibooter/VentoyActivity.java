/******************************************************************************
 * VentoyActivity.java
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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VentoyActivity extends Activity {

    private static final String STATE_PAGE =
        "ventoy_page";

    private static final String STATE_DEVICE_ID =
        "ventoy_device_id";

    private static final int PAGE_USB_SELECT = 1;
    private static final int PAGE_INSTALL = 2;

    private static final int MASS_STORAGE_SUBCLASS_SCSI =
        0x06;

    private static final int MASS_STORAGE_PROTOCOL_BOT =
        0x50;

    private int currentPage =
        PAGE_USB_SELECT;

    private int selectedDeviceId =
        -1;

    private int capacityReadGeneration =
        0;

    private UsbManager usbManager;
    private UsbVentoy usbVentoy;

    private UsbDevice selectedDevice;
    private DeviceCapacity selectedCapacity;

    private boolean receiverRegistered =
        false;

    private boolean capacityReadRunning =
        false;

    private boolean installationRunning =
        false;

    private TextView deviceNameView;
    private TextView deviceDetailsView;
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

                this.totalBytes =
                    blockCount *
                    (long) blockSize;

            } else {

                this.totalBytes =
                    -1;
            }
        }
    }

    private final BroadcastReceiver usbReceiver =
        new BroadcastReceiver() {

            @Override
            public void onReceive(
                Context context,
                Intent intent
            ) {

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
                            VentoyActivity.this,
                            "USB permission was denied.",
                            Toast.LENGTH_SHORT
                        ).show();
                    }

                } else if (
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
                        device != null &&
                        device.getDeviceId() ==
                            selectedDeviceId
                    ) {

                        if (
                            installationRunning
                        ) {

                            selectedDevice =
                                null;

                            selectedCapacity =
                                null;

                            selectedDeviceId =
                                -1;

                            Toast.makeText(
                                VentoyActivity.this,
                                "USB device disconnected during installation.",
                                Toast.LENGTH_LONG
                            ).show();

                        } else {

                            clearSelectedDevice();

                            if (
                                currentPage ==
                                PAGE_INSTALL
                            ) {

                                currentPage =
                                    PAGE_USB_SELECT;

                                showUsbSelectPage();
                            }

                            Toast.makeText(
                                VentoyActivity.this,
                                "Selected USB device was disconnected.",
                                Toast.LENGTH_SHORT
                            ).show();
                        }
                    }

                } else if (
                    UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(
                        action
                    )
                ) {

                    if (
                        currentPage ==
                        PAGE_USB_SELECT
                    ) {

                        updateUsbSelectionUi();
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

        usbVentoy =
            new UsbVentoy(this);

        if (
            savedInstanceState != null
        ) {

            currentPage =
                savedInstanceState.getInt(
                    STATE_PAGE,
                    PAGE_USB_SELECT
                );

            selectedDeviceId =
                savedInstanceState.getInt(
                    STATE_DEVICE_ID,
                    -1
                );
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
            !capacityReadRunning
        ) {

            restoreSelectedDevice();
        }

        updateUsbSelectionUi();
    }

    @Override
    protected void onDestroy() {

        capacityReadGeneration++;

        if (
            usbVentoy != null
        ) {

            usbVentoy.close();
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
            PAGE_INSTALL
        ) {

            if (
                selectedDevice == null ||
                selectedCapacity == null
            ) {

                currentPage =
                    PAGE_USB_SELECT;

                showUsbSelectPage();
                return;
            }

            showInstallPage();

        } else {

            showUsbSelectPage();
        }
    }

    private void showUsbSelectPage() {

        currentPage =
            PAGE_USB_SELECT;

        setContentView(
            R.layout.ventoy_usb_select
        );

        TextView backTop =
            (TextView)
            findViewById(
                R.id.ventoy_back_top
            );

        Button selectUsb =
            (Button)
            findViewById(
                R.id.ventoy_select_usb
            );

        Button cancel =
            (Button)
            findViewById(
                R.id.ventoy_cancel
            );

        continueButton =
            (Button)
            findViewById(
                R.id.ventoy_continue
            );

        deviceNameView =
            (TextView)
            findViewById(
                R.id.ventoy_device_name
            );

        deviceDetailsView =
            (TextView)
            findViewById(
                R.id.ventoy_device_details
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

                    if (
                        selectedDevice == null ||
                        selectedCapacity == null
                    ) {

                        Toast.makeText(
                            VentoyActivity.this,
                            "Select a USB device first.",
                            Toast.LENGTH_SHORT
                        ).show();

                        return;
                    }

                    currentPage =
                        PAGE_INSTALL;

                    showInstallPage();
                }
            }
        );

        updateUsbSelectionUi();
    }

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

        AlertDialog.Builder builder =
            new AlertDialog.Builder(
                this
            );

        builder.setTitle(
            "Select USB Device"
        );

        builder.setItems(
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
        );

        builder.setNegativeButton(
            "CANCEL",
            null
        );

        builder.show();
    }

    private List<UsbDevice>
    findMassStorageDevices() {

        List<UsbDevice> result =
            new ArrayList<UsbDevice>();

        if (
            usbManager == null
        ) {
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

                    String nameA =
                        getFriendlyDeviceName(
                            a
                        );

                    String nameB =
                        getFriendlyDeviceName(
                            b
                        );

                    return
                        nameA.compareToIgnoreCase(
                            nameB
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

        if (
            device == null
        ) {
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
            device == null
        ) {
            return;
        }

        capacityReadGeneration++;

        if (
            usbVentoy != null
        ) {
            usbVentoy.close();
        }

        selectedDevice =
            device;

        selectedDeviceId =
            device.getDeviceId();

        selectedCapacity =
            null;

        capacityReadRunning =
            false;

        updateUsbSelectionUi();

        if (
            usbVentoy.hasPermission(
                device
            )
        ) {

            beginCapacityRead(
                device
            );

            return;
        }

        int result =
            usbVentoy.requestPermission(
                device
            );

        if (
            result !=
                UsbVentoy.RESULT_OK &&
            result !=
                UsbVentoy.ERROR_PERMISSION_REQUIRED
        ) {

            String error =
                usbVentoy.getLastError();

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
            capacityReadRunning
        ) {
            return;
        }

        final int deviceId =
            device.getDeviceId();

        final int generation =
            ++capacityReadGeneration;

        capacityReadRunning =
            true;

        selectedCapacity =
            null;

        updateUsbSelectionUi();

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    int result =
                        usbVentoy.open(
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
                                usbVentoy.getBlockCount(),
                                usbVentoy.getBlockSize()
                            );

                    } else {

                        error =
                            usbVentoy.getLastError();
                    }

                    usbVentoy.close();

                    final DeviceCapacity
                        finalCapacity =
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
                                        capacityReadGeneration ||
                                    selectedDeviceId !=
                                        deviceId
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
                                        VentoyActivity.this,
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

                                updateUsbSelectionUi();
                            }
                        }
                    );
                }
            },
            "VentoyUsbCapacity"
        ).start();
    }

    private void restoreSelectedDevice() {

        if (
            usbManager == null ||
            selectedDeviceId == -1
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

            updateUsbSelectionUi();
            return;
        }

        if (
            usbVentoy.hasPermission(
                restored
            )
        ) {

            beginCapacityRead(
                restored
            );

        } else {

            updateUsbSelectionUi();
        }
    }

    private void clearSelectedDevice() {

        capacityReadGeneration++;

        capacityReadRunning =
            false;

        if (
            usbVentoy != null
        ) {

            usbVentoy.close();
        }

        selectedDevice =
            null;

        selectedCapacity =
            null;

        selectedDeviceId =
            -1;

        updateUsbSelectionUi();
    }

    private void updateUsbSelectionUi() {

        if (
            deviceNameView == null ||
            deviceDetailsView == null ||
            continueButton == null
        ) {

            return;
        }

        if (
            selectedDevice == null
        ) {

            deviceNameView.setText(
                "No USB device selected"
            );

            deviceDetailsView.setText(
                "Connect a USB flash drive and select it."
            );

            continueButton.setEnabled(
                false
            );

            continueButton.setAlpha(
                0.45f
            );

            return;
        }

        deviceNameView.setText(
            getFriendlyDeviceName(
                selectedDevice
            )
        );

        if (
            selectedCapacity == null
        ) {

            if (
                capacityReadRunning
            ) {

                deviceDetailsView.setText(
                    buildBasicDeviceDetails(
                        selectedDevice
                    ) +
                    "\nReading device capacity..."
                );

            } else if (
                usbVentoy.hasPermission(
                    selectedDevice
                )
            ) {

                deviceDetailsView.setText(
                    buildBasicDeviceDetails(
                        selectedDevice
                    ) +
                    "\nPreparing USB device..."
                );

            } else {

                deviceDetailsView.setText(
                    buildBasicDeviceDetails(
                        selectedDevice
                    ) +
                    "\nWaiting for USB permission..."
                );
            }

            continueButton.setEnabled(
                false
            );

            continueButton.setAlpha(
                0.45f
            );

            return;
        }

        deviceDetailsView.setText(
            buildSelectedDeviceDetails()
        );

        continueButton.setEnabled(
            true
        );

        continueButton.setAlpha(
            1.0f
        );
    }

    private void showInstallPage() {

        currentPage =
            PAGE_INSTALL;

        setContentView(
            R.layout.ventoy_install
        );

        deviceNameView =
            null;

        deviceDetailsView =
            null;

        continueButton =
            null;

        TextView backTop =
            (TextView)
            findViewById(
                R.id.ventoy_install_back_top
            );

        TextView installDevice =
            (TextView)
            findViewById(
                R.id.ventoy_install_device
            );

        TextView installDetails =
            (TextView)
            findViewById(
                R.id.ventoy_install_device_details
            );

        Button back =
            (Button)
            findViewById(
                R.id.ventoy_install_back
            );

        Button install =
            (Button)
            findViewById(
                R.id.ventoy_install_button
            );

        if (
            selectedDevice == null ||
            selectedCapacity == null
        ) {

            currentPage =
                PAGE_USB_SELECT;

            showUsbSelectPage();
            return;
        }

        installDevice.setText(
            getFriendlyDeviceName(
                selectedDevice
            )
        );

        installDetails.setText(
            buildSelectedDeviceDetails()
        );

        addTouchFeedback(
            back
        );

        addTouchFeedback(
            install
        );

        View.OnClickListener backListener =
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    currentPage =
                        PAGE_USB_SELECT;

                    showUsbSelectPage();
                }
            };

        backTop.setOnClickListener(
            backListener
        );

        back.setOnClickListener(
            backListener
        );

        final ProgressBar progressBar =
            (ProgressBar)
            findViewById(
                R.id.ventoy_progress
            );

        final TextView progressText =
            (TextView)
            findViewById(
                R.id.ventoy_progress_text
            );

        install.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    if (
                        installationRunning
                    ) {

                        return;
                    }

                    if (
                        selectedDevice == null ||
                        selectedCapacity == null
                    ) {

                        Toast.makeText(
                            VentoyActivity.this,
                            "USB device is no longer available.",
                            Toast.LENGTH_LONG
                        ).show();

                        currentPage =
                            PAGE_USB_SELECT;

                        showUsbSelectPage();
                        return;
                    }

                    showInstallConfirmation(
                        backTop,
                        back,
                        install,
                        progressBar,
                        progressText
                    );
                }
            }
        );
    }

    private void showInstallConfirmation(
        final TextView backTop,
        final Button back,
        final Button install,
        final ProgressBar progressBar,
        final TextView progressText
    ) {

        if (
            selectedDevice == null ||
            selectedCapacity == null
        ) {
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
            "\n\nAll data on this USB drive will be permanently erased.";

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

                        showFinalInstallConfirmation(
                            backTop,
                            back,
                            install,
                            progressBar,
                            progressText
                        );
                    }
                }
            )
            .show();
    }

    private void showFinalInstallConfirmation(
        final TextView backTop,
        final Button back,
        final Button install,
        final ProgressBar progressBar,
        final TextView progressText
    ) {

        new AlertDialog.Builder(
            this
        )
            .setTitle(
                "Final confirmation"
            )
            .setMessage(
                "Ventoy will rewrite the partition table, format the main partition as exFAT, and write the Ventoy boot data. This cannot be undone."
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "ERASE AND INSTALL",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        DialogInterface dialog,
                        int which
                    ) {

                        startVentoyInstallation(
                            backTop,
                            back,
                            install,
                            progressBar,
                            progressText
                        );
                    }
                }
            )
            .show();
    }

    private void startVentoyInstallation(
        final TextView backTop,
        final Button back,
        final Button install,
        final ProgressBar progressBar,
        final TextView progressText
    ) {

        if (
            selectedDevice == null ||
            installationRunning
        ) {
            return;
        }

        final UsbDevice installDevice =
            selectedDevice;

        installationRunning =
            true;

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

        install.setEnabled(
            false
        );

        install.setAlpha(
            0.45f
        );

        progressBar.setProgress(
            0
        );

        progressText.setText(
            "Preparing Ventoy installation..."
        );

        setRequestedOrientation(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        );

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        final VentoyInstaller installer =
            new VentoyInstaller(
                VentoyActivity.this
            );

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    int result =
                        installer.installMbr(
                            installDevice,
                            new VentoyInstaller.ProgressListener() {

                                @Override
                                public void onProgress(
                                    final int percent,
                                    final String message
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
                                                    percent
                                                );

                                                progressText.setText(
                                                    message
                                                );
                                            }
                                        }
                                    );
                                }
                            }
                        );

                    final int finalResult =
                        result;

                    final String finalError =
                        installer.getLastError();

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                finishVentoyInstallationUi(
                                    finalResult,
                                    finalError,
                                    backTop,
                                    back,
                                    install,
                                    progressBar,
                                    progressText
                                );
                            }
                        }
                    );
                }
            },
            "VentoyInstaller"
        ).start();
    }

    private void finishVentoyInstallationUi(
        int result,
        String error,
        TextView backTop,
        Button back,
        Button install,
        ProgressBar progressBar,
        TextView progressText
    ) {

        installationRunning =
            false;

        getWindow().clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setRequestedOrientation(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
            result ==
            VentoyInstaller.RESULT_OK
        ) {

            progressBar.setProgress(
                100
            );

            progressText.setText(
                "Ventoy installed successfully."
            );

            install.setEnabled(
                false
            );

            install.setAlpha(
                0.45f
            );

            new AlertDialog.Builder(
                this
            )
                .setTitle(
                    "Ventoy installed"
                )
                .setMessage(
                    "Installation completed successfully. Disconnect and reconnect the USB drive before copying ISO files to it."
                )
                .setPositiveButton(
                    "OK",
                    null
                )
                .show();

        } else {

            install.setEnabled(
                true
            );

            install.setAlpha(
                1.0f
            );

            progressText.setText(
                "Installation failed."
            );

            String message =
                error == null ||
                error.length() == 0
                ? "Ventoy installation failed."
                : error;

            new AlertDialog.Builder(
                this
            )
                .setTitle(
                    "Installation failed"
                )
                .setMessage(
                    message
                )
                .setPositiveButton(
                    "OK",
                    null
                )
                .show();
        }
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

        if (
            device == null
        ) {

            return
                "USB Mass Storage Device";
        }

        String manufacturer =
            "";

        String product =
            "";

        try {

            manufacturer =
                safeText(
                    device.getManufacturerName()
                );

        } catch (
            Throwable ignored
        ) {
        }

        try {

            product =
                safeText(
                    device.getProductName()
                );

        } catch (
            Throwable ignored
        ) {
        }

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

            return manufacturer;
        }

        return
            "USB Mass Storage Device";
    }

    private String buildBasicDeviceDetails(
        UsbDevice device
    ) {

        if (
            device == null
        ) {

            return "";
        }

        return String.format(
            Locale.US,
            "VID: %04X  PID: %04X",
            device.getVendorId(),
            device.getProductId()
        );
    }

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
            "  •  Sector: " +
            selectedCapacity.blockSize +
            " bytes";
    }

    private static String formatBytes(
        long bytes
    ) {

        if (
            bytes < 0
        ) {

            return
                "Unknown";
        }

        double value =
            (double) bytes;

        if (
            bytes >=
            1024L *
            1024L *
            1024L *
            1024L
        ) {

            return String.format(
                Locale.US,
                "%.2f TiB",
                value /
                (
                    1024.0 *
                    1024.0 *
                    1024.0 *
                    1024.0
                )
            );
        }

        if (
            bytes >=
            1024L *
            1024L *
            1024L
        ) {

            return String.format(
                Locale.US,
                "%.2f GiB",
                value /
                (
                    1024.0 *
                    1024.0 *
                    1024.0
                )
            );
        }

        if (
            bytes >=
            1024L *
            1024L
        ) {

            return String.format(
                Locale.US,
                "%.2f MiB",
                value /
                (
                    1024.0 *
                    1024.0
                )
            );
        }

        if (
            bytes >=
            1024L
        ) {

            return String.format(
                Locale.US,
                "%.2f KiB",
                value /
                1024.0
            );
        }

        return
            bytes +
            " bytes";
    }

    private static String safeText(
        String value
    ) {

        return
            value == null
            ? ""
            : value.trim();
    }

    @Override
    public void onBackPressed() {

        if (
            installationRunning
        ) {

            Toast.makeText(
                this,
                "Ventoy installation is in progress.",
                Toast.LENGTH_SHORT
            ).show();

            return;
        }

        if (
            currentPage ==
            PAGE_INSTALL
        ) {

            currentPage =
                PAGE_USB_SELECT;

            showUsbSelectPage();

        } else {

            super.onBackPressed();
        }
    }

    private void addTouchFeedback(
        final View view
    ) {

        view.setOnTouchListener(
            new View.OnTouchListener() {

                @Override
                public boolean onTouch(
                    View v,
                    MotionEvent event
                ) {

                    if (
                        !v.isEnabled()
                    ) {

                        return false;
                    }

                    switch (
                        event.getAction()
                    ) {

                        case MotionEvent.ACTION_DOWN:

                            v.animate().cancel();
                            v.setAlpha(
                                0.4f
                            );

                            break;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:

                            v.animate()
                                .alpha(1.0f)
                                .setDuration(300)
                                .start();

                            break;
                    }

                    return false;
                }
            }
        );
    }
}
