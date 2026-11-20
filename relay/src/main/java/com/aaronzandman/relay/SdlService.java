package com.aaronzandman.relay;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import com.smartdevicelink.managers.SdlManager;
import com.smartdevicelink.managers.SdlManagerListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.managers.lifecycle.LifecycleConfigurationUpdate;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.SendLocation;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.Language;
import com.smartdevicelink.proxy.rpc.enums.PredefinedWindows;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.util.SystemInfo;

import java.util.Collections;
import java.util.Vector;

/**
 * Connects to the vehicle over SmartDeviceLink (AppLink) and sets a destination in the built-in SYNC
 * navigation with the SendLocation RPC. Only works on a SYNC head unit that allows this app
 * (developer mode, or OEM approval) and that supports SendLocation. Experimental.
 */
public class SdlService extends Service {

    private static final String APP_ID = "nextstoprelay";
    private static final String APP_NAME = "Next Stop";
    private static final int FOREGROUND_SERVICE_ID = 221;

    private SdlManager sdlManager = null;
    private boolean hmiReady = false;
    private boolean sessionStarted = false;
    private HMILevel level = HMILevel.HMI_NONE;
    private int lastTriedRank = -1;   // highest HMI level we've already tried sending at (-1 = none yet)

    private static int rank(HMILevel l) {
        if (l == HMILevel.HMI_FULL) return 3;
        if (l == HMILevel.HMI_LIMITED) return 2;
        if (l == HMILevel.HMI_BACKGROUND) return 1;
        return 0; // NONE
    }

    // The destination waiting to be sent (set from the activity via an intent).
    private static Double pLat = null, pLng = null;
    private static String pName = null, pAddr = null;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) enterForeground();
    }

    @SuppressLint("NewApi")
    private void enterForeground() {
        try {
            NotificationChannel channel = new NotificationChannel(APP_ID, "SYNC connection", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
                Notification.Builder b = new Notification.Builder(this, channel.getId())
                        .setContentTitle("Next Stop — connecting to SYNC")
                        .setSmallIcon(R.drawable.ic_sdl);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
                startForeground(FOREGROUND_SERVICE_ID, b.build());
            }
        } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("lat")) {
            pLat = intent.getDoubleExtra("lat", 0);
            pLng = intent.getDoubleExtra("lng", 0);
            pName = intent.getStringExtra("name");
            pAddr = intent.getStringExtra("addr");
            lastTriedRank = -1;   // a fresh destination — allow a send at the current level again
        }
        if (sdlManager == null) startProxy(); else trySend();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) stopForeground(true);
        if (sdlManager != null) { sdlManager.dispose(); sdlManager = null; }
        super.onDestroy();
    }

    private void saveStatus(String s) {
        try { getSharedPreferences("nextstop", MODE_PRIVATE).edit().putString("sdlStatus", s).apply(); } catch (Exception ignored) {}
    }

    private void startProxy() {
        if (sdlManager != null) return;
        MultiplexTransportConfig transport = new MultiplexTransportConfig(this, APP_ID, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF);

        // DEFAULT type: the vehicle refuses SendLocation on policy regardless of type, and NAVIGATION
        // forced an intrusive "Locked for your safety" screen every connect, so stay on DEFAULT.
        Vector<AppHMIType> appType = new Vector<>();
        appType.add(AppHMIType.DEFAULT);

        SdlManagerListener listener = new SdlManagerListener() {
            @Override public void onStart() {
                sessionStarted = true;
                saveStatus("Connected to SYNC; waiting for the screen.");
                sdlManager.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, new OnRPCNotificationListener() {
                    @Override public void onNotified(RPCNotification notification) {
                        OnHMIStatus status = (OnHMIStatus) notification;
                        if (status.getWindowID() != null && status.getWindowID() != PredefinedWindows.DEFAULT_WINDOW.getValue()) return;
                        if (status.getHmiLevel() != null) level = status.getHmiLevel();
                        hmiReady = true;
                        trySend();   // retries at a higher HMI level (e.g. when the app is opened on SYNC)
                    }
                });
            }
            @Override public void onDestroy() { SdlService.this.stopSelf(); }
            @Override public void onError(String info, Exception e) { saveStatus("SYNC error: " + info); }
            @Override public LifecycleConfigurationUpdate managerShouldUpdateLifecycle(Language language, Language hmiLanguage) { return null; }
            @Override public boolean onSystemInfoReceived(SystemInfo systemInfo) { return true; }
        };

        SdlArtwork icon = new SdlArtwork("nextstop_icon.png", FileType.GRAPHIC_PNG, R.mipmap.ic_launcher, true);
        SdlManager.Builder builder = new SdlManager.Builder(this, APP_ID, APP_NAME, listener);
        builder.setAppTypes(appType);
        builder.setTransportType(transport);
        builder.setAppIcon(icon);
        sdlManager = builder.build();
        sdlManager.start();
        saveStatus("Starting SYNC connection… (waiting for the van)");
        // If no head unit links within 25 s, say so plainly instead of sitting on "Starting…".
        main.postDelayed(() -> {
            if (!sessionStarted) saveStatus("No SYNC connection. Make sure: the van is running, the phone is plugged into the SYNC USB port (or Bluetooth-paired to SYNC), and AppLink is enabled on SYNC. If it still won't connect, SYNC is likely blocking this unapproved app.");
        }, 25000);
    }

    private void trySend() {
        if (sdlManager == null || !hmiReady || pLat == null) return;
        if (rank(level) <= lastTriedRank) return;   // already tried at this level; wait for a higher one
        lastTriedRank = rank(level);
        final String name = pName;
        SendLocation sl = new SendLocation();
        sl.setLatitudeDegrees(pLat);
        sl.setLongitudeDegrees(pLng);
        if (name != null) sl.setLocationName(name);
        if (pAddr != null) sl.setAddressLines(Collections.singletonList(pAddr));
        sl.setOnRPCResponseListener(new OnRPCResponseListener() {
            @Override public void onResponse(int correlationId, final RPCResponse response) {
                final boolean ok = response != null && Boolean.TRUE.equals(response.getSuccess());
                String rc = (response != null && response.getResultCode() != null) ? response.getResultCode().toString() : "no response";
                String info = (response != null) ? response.getInfo() : null;
                if (ok) {
                    pLat = null; pLng = null; pName = null; pAddr = null;   // delivered — consume it
                    saveStatus("Sent to SYNC: " + name);
                } else {
                    // keep the pending destination so a higher HMI level (app opened on SYNC) can retry
                    String hint = (level == HMILevel.HMI_NONE) ? " · try opening Next Stop on the SYNC Apps screen, then tap Send again" : "";
                    saveStatus("SYNC refused SendLocation — " + rc + (info != null ? " (" + info + ")" : "") + " · at HMI " + level + hint);
                }
                main.post(() -> Toast.makeText(SdlService.this, ok ? "Sent to the Ford SYNC navigation." : ("SYNC refused it (" + rc + ")"), Toast.LENGTH_LONG).show());
            }
        });
        saveStatus("Sending destination to SYNC… (HMI " + level + ")");
        sdlManager.sendRPC(sl);
    }
}
