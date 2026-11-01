/******************************************************************************
 * ModeService.java
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Foreground service that keeps the process alive and shows one ongoing
 * notification per running mode:
 *
 *   ISO writer, Ventoy install, USB Gadget, FunctionFS and TFTP server.
 *
 * The modes keep their own logic (and their own persisted state). This class
 * only
 *   - keeps the app process alive while a mode runs,
 *   - shows title / text / real progress,
 *   - offers a "Stop" action that calls the stop hook the mode registered,
 *   - opens the mode's screen when the notification is tapped.
 *
 * The notification permission is optional: without it the service still
 * runs, Android just does not show the notification.
 */
public final class ModeService extends Service {

    static final int MODE_ISO = 1;
    static final int MODE_VENTOY = 2;
    static final int MODE_GADGET = 3;
    static final int MODE_FUNCTIONFS = 4;
    static final int MODE_TFTP = 5;

    /** Called on a background thread when the user taps "Stop". */
    interface StopHook {

        void stop();
    }

    /** Snapshot of one running mode. */
    static final class State {

        final int mode;
        String text = "";
        int percent = -1;           // -1 = no progress bar
        boolean stopping;
        StopHook hook;

        State(int mode) {
            this.mode = mode;
        }

        State copy() {

            State c = new State(mode);

            c.text = text;
            c.percent = percent;
            c.stopping = stopping;
            c.hook = hook;

            return c;
        }
    }

    /** Result of a finished mode, kept so a re-opened screen can show it. */
    static final class Result {

        final boolean success;
        final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private static final String TAG = "ModeService";

    private static final String CHANNEL_ID = "multibooter_modes";

    private static final String ACTION_SYNC =
        "com.werismoln.multibooter.action.SYNC";

    private static final String ACTION_STOP =
        "com.werismoln.multibooter.action.STOP";

    private static final String EXTRA_MODE = "mode";

    private static final int NOTIFICATION_BASE = 1000;
    private static final int RESULT_NOTIFICATION_BASE = 2000;

    /*
     * ServiceInfo.FOREGROUND_SERVICE_TYPE_* values. They are written as
     * literals because the SPECIAL_USE constant only exists from API 34.
     */
    private static final int TYPE_CONNECTED_DEVICE = 0x10;
    private static final int TYPE_SPECIAL_USE = 0x40000000;

    private static final Map<Integer, State> STATES =
        new LinkedHashMap<Integer, State>();

    private static final Map<Integer, Result> RESULTS =
        new HashMap<Integer, Result>();

    private static volatile ModeService instance;

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private final Set<Integer> posted =
        new HashSet<Integer>();

    private int foregroundMode = -1;

    // ---------------------------------------------------------------------
    // Public API (any thread)
    // ---------------------------------------------------------------------

    /**
     * Registers a running mode and starts the service. Call it from a
     * visible activity right after the user starts the mode, because Android
     * only allows a foreground service to be started from the foreground.
     */
    static void begin(
        Context context,
        int mode,
        String text,
        StopHook hook
    ) {

        Context app = context.getApplicationContext();

        synchronized (STATES) {

            State state = STATES.get(mode);

            if (state == null) {

                state = new State(mode);
                STATES.put(mode, state);
            }

            state.text = text == null ? "" : text;
            state.percent = -1;
            state.stopping = false;
            state.hook = hook;

            RESULTS.remove(mode);
        }

                warnIfNotificationsOff(app);

        ModeService running = instance;

        if (running != null) {

            running.requestRender();
            return;
        }

        Intent intent = new Intent(app, ModeService.class);

        intent.setAction(ACTION_SYNC);

        try {

            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }

        } catch (RuntimeException e) {

            // For example when Android refuses a background start. The mode
            // itself keeps working; only the notification is missing.
            Log.w(TAG, "Could not start the foreground service.", e);
        }
    }

            private static final long PROGRESS_MIN_INTERVAL_MS = 500L;

    private static long sLastProgressRender = 0L;

    private static boolean sWarnedNotificationsOff = false;

    /**
     * Notifications are optional. If they are off the mode still runs, so
     * tell the user once what they will not see and why.
     */
    private static void warnIfNotificationsOff(final Context app) {

        if (sWarnedNotificationsOff) {
            return;
        }

        NotificationManager manager =
            (NotificationManager)
            app.getSystemService(Context.NOTIFICATION_SERVICE);

        if (manager == null || manager.areNotificationsEnabled()) {
            return;
        }

        sWarnedNotificationsOff = true;

        new Handler(Looper.getMainLooper()).post(
            new Runnable() {

                @Override
                public void run() {

                    Toast.makeText(
                        app,
                        "Notifications are off. The mode keeps running in " +
                        "the background, but progress and the Stop button " +
                        "will not be shown.",
                        Toast.LENGTH_LONG
                    ).show();
                }
            }
        );
    }

    /** Updates the text and (when percent >= 0) the progress bar. */
    static void progress(
        Context context,
        int mode,
        int percent,
        String text
    ) {

                long now = SystemClock.elapsedRealtime();

        synchronized (STATES) {

            State state = STATES.get(mode);

            if (state == null) {
                return;
            }

            if (text != null) {
                state.text = text;
            }

            int newPercent =
                percent < 0
                ? -1
                : Math.min(100, percent);

            state.percent = newPercent;

            /*
             * The writers report progress for every 64 KiB chunk. Android
             * drops or throttles notifications that are updated too often,
             * so refresh at most about twice a second (the state itself is
             * always up to date for the in-app status page).
             */
            if (
                now - sLastProgressRender < PROGRESS_MIN_INTERVAL_MS &&
                newPercent < 100
            ) {
                return;
            }

            sLastProgressRender = now;
        }

        ModeService running = instance;

        if (running != null) {
            running.requestRender();
        }
    }

    /**
     * Marks a mode as finished. For the two write modes a final
     * (non-ongoing) result notification is shown as well.
     */
    static void end(
        Context context,
        int mode,
        boolean success,
        String message
    ) {

        Context app = context.getApplicationContext();

        boolean wasRunning;

        synchronized (STATES) {

            wasRunning = STATES.remove(mode) != null;

            RESULTS.put(
                mode,
                new Result(
                    success,
                    message == null ? "" : message
                )
            );
        }

        ModeService running = instance;

        if (running != null) {

            running.requestRender();

            if (
                wasRunning &&
                (mode == MODE_ISO || mode == MODE_VENTOY)
            ) {

                running.postResult(mode, success, message);
            }
        }
    }

    static boolean isActive(int mode) {

        synchronized (STATES) {

            return STATES.containsKey(mode);
        }
    }

    static State snapshot(int mode) {

        synchronized (STATES) {

            State state = STATES.get(mode);

            return state == null ? null : state.copy();
        }
    }

    static Result lastResult(int mode) {

        synchronized (STATES) {

            return RESULTS.get(mode);
        }
    }

    /** Runs the stop hook on a background thread (same as the button). */
    static void requestStop(int mode) {

        final StopHook hook;

        synchronized (STATES) {

            State state = STATES.get(mode);

            if (state == null || state.stopping) {
                return;
            }

            state.stopping = true;
            state.text = "Stopping...";
            state.percent = -1;

            hook = state.hook;
        }

        ModeService running = instance;

        if (running != null) {
            running.requestRender();
        }

        if (hook == null) {
            return;
        }

        Thread thread = new Thread(
            new Runnable() {

                @Override
                public void run() {

                    try {

                        hook.stop();

                    } catch (RuntimeException e) {

                        Log.w(TAG, "Stop hook failed.", e);
                    }
                }
            },
            "ModeStop"
        );

        thread.setDaemon(true);
        thread.start();
    }

    // ---------------------------------------------------------------------
    // Service
    // ---------------------------------------------------------------------

    @Override
    public void onCreate() {

        super.onCreate();

        instance = this;

        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (
            intent != null &&
            ACTION_STOP.equals(intent.getAction())
        ) {

            requestStop(intent.getIntExtra(EXTRA_MODE, -1));
        }

        render();

        // If the process is killed there is nothing to restore: every mode
        // keeps its own persisted state and registers again when reopened.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {

        instance = null;

        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {

        // The user swiped the app away. Running modes must keep going; the
        // service (and with it the process) simply stays.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {

        return null;
    }

    private void requestRender() {

        handler.post(
            new Runnable() {

                @Override
                public void run() {

                    render();
                }
            }
        );
    }

    private void render() {

        List<State> active = new ArrayList<State>();

        synchronized (STATES) {

            for (State state : STATES.values()) {
                active.add(state.copy());
            }
        }

        NotificationManager manager =
            (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);

        if (active.isEmpty()) {

            // A service started with startForegroundService() must call
            // startForeground() once, even if there is nothing left to show.
            if (foregroundMode == -1) {

                try {

                    enterForeground(
                        NOTIFICATION_BASE,
                        buildIdleNotification(),
                        foregroundType(0)
                    );

                } catch (RuntimeException ignored) {
                }
            }

            stopForegroundCompat();

            if (manager != null) {

                for (int mode : posted) {
                    manager.cancel(NOTIFICATION_BASE + mode);
                }

                manager.cancel(NOTIFICATION_BASE);
            }

            posted.clear();
            foregroundMode = -1;

            stopSelf();
            return;
        }

        State primary = active.get(0);

        int type = 0;

        for (State state : active) {
            type |= typeOf(state.mode);
        }

        Set<Integer> now = new HashSet<Integer>();

        for (State state : active) {

            now.add(state.mode);

            Notification notification = buildNotification(state);

            if (state.mode == primary.mode) {

                try {

                    enterForeground(
                        NOTIFICATION_BASE + state.mode,
                        notification,
                        foregroundType(type)
                    );

                } catch (RuntimeException e) {

                    // Missing prerequisite or a refused start: keep the
                    // mode running, show the notification the normal way.
                    Log.w(TAG, "startForeground() failed.", e);

                    if (manager != null) {

                        manager.notify(
                            NOTIFICATION_BASE + state.mode,
                            notification
                        );
                    }
                }

            } else if (manager != null) {

                manager.notify(
                    NOTIFICATION_BASE + state.mode,
                    notification
                );
            }
        }

        if (manager != null) {

            for (int mode : posted) {

                if (!now.contains(mode)) {
                    manager.cancel(NOTIFICATION_BASE + mode);
                }
            }
        }

        posted.clear();
        posted.addAll(now);
        foregroundMode = primary.mode;
    }

    private void stopForegroundCompat() {

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private static int typeOf(int mode) {

        switch (mode) {

            case MODE_ISO:
            case MODE_VENTOY:
                return TYPE_CONNECTED_DEVICE;

            default:
                return TYPE_SPECIAL_USE;
        }
    }

    private static int foregroundType(int wanted) {

        if (Build.VERSION.SDK_INT >= 34) {

            return wanted == 0 ? TYPE_SPECIAL_USE : wanted;
        }

        if (Build.VERSION.SDK_INT >= 29) {

            // SPECIAL_USE does not exist before API 34.
            return TYPE_CONNECTED_DEVICE;
        }

        return 0;
    }

    private void enterForeground(
        int id,
        Notification notification,
        int type
    ) {

        if (Build.VERSION.SDK_INT >= 29) {

            super.startForeground(id, notification, type);

        } else {

            super.startForeground(id, notification);
        }
    }

    // ---------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------

    private void createChannel() {

        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationManager manager =
            (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);

        if (manager == null) {
            return;
        }

        NotificationChannel channel =
            new NotificationChannel(
                CHANNEL_ID,
                "Running modes",
                NotificationManager.IMPORTANCE_LOW
            );

        channel.setDescription(
            "Shows the mode that is running in the background."
        );

        channel.setShowBadge(false);

        manager.createNotificationChannel(channel);
    }

    private Notification.Builder newBuilder() {

        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID);
        }

        return new Notification.Builder(this);
    }

    private Notification buildIdleNotification() {

        return newBuilder()
            .setSmallIcon(R.drawable.ic_stat_mode)
            .setContentTitle("MultiBooter")
            .setOngoing(true)
            .build();
    }

    private Notification buildNotification(State state) {

        Notification.Builder builder = newBuilder();

        builder
            .setSmallIcon(R.drawable.ic_stat_mode)
            .setContentTitle(titleOf(state.mode))
            .setContentText(state.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openIntent(state.mode));

        if (Build.VERSION.SDK_INT >= 31) {

            builder.setForegroundServiceBehavior(
                Notification.FOREGROUND_SERVICE_IMMEDIATE
            );
        }

        if (state.percent >= 0) {

            builder.setProgress(100, state.percent, false);

            builder.setSubText(state.percent + "%");
        }

        if (!state.stopping) {

            Intent stop = new Intent(this, ModeService.class);

            stop.setAction(ACTION_STOP);
            stop.putExtra(EXTRA_MODE, state.mode);

            PendingIntent stopIntent =
                PendingIntent.getService(
                    this,
                    NOTIFICATION_BASE + state.mode,
                    stop,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                    PendingIntent.FLAG_IMMUTABLE
                );

            builder.addAction(
                new Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        R.drawable.ic_stat_mode
                    ),
                    "Stop",
                    stopIntent
                ).build()
            );
        }

        return builder.build();
    }

    private void postResult(int mode, boolean success, String message) {

        NotificationManager manager =
            (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);

        if (manager == null) {
            return;
        }

        Notification notification =
            newBuilder()
                .setSmallIcon(R.drawable.ic_stat_mode)
                .setContentTitle(
                    titleOf(mode) +
                    (success ? " - done" : " - failed")
                )
                .setContentText(message == null ? "" : message)
                .setStyle(
                    new Notification.BigTextStyle()
                        .bigText(message == null ? "" : message)
                )
                .setAutoCancel(true)
                .setContentIntent(openIntent(mode))
                .build();

        manager.notify(RESULT_NOTIFICATION_BASE + mode, notification);
    }

    private PendingIntent openIntent(int mode) {

        Intent intent = new Intent(this, activityOf(mode));

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK |
            Intent.FLAG_ACTIVITY_SINGLE_TOP |
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        return PendingIntent.getActivity(
            this,
            NOTIFICATION_BASE + mode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT |
            PendingIntent.FLAG_IMMUTABLE
        );
    }

    static Class<?> activityOf(int mode) {

        switch (mode) {

            case MODE_ISO:
                return IsoWriterActivity.class;

            case MODE_VENTOY:
                return VentoyActivity.class;

            case MODE_GADGET:
                return GadgetActivity.class;

            case MODE_FUNCTIONFS:
                return FunctionFsActivity.class;

            default:
                return TftpActivity.class;
        }
    }

    static String titleOf(int mode) {

        switch (mode) {

            case MODE_ISO:
                return "Writing ISO to USB";

            case MODE_VENTOY:
                return "Installing Ventoy";

            case MODE_GADGET:
                return "USB Gadget is active";

            case MODE_FUNCTIONFS:
                return "FunctionFS is active";

            default:
                return "TFTP server is running";
        }
    }
}
