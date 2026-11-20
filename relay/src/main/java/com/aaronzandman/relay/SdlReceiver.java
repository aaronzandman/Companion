package com.aaronzandman.relay;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.smartdevicelink.transport.SdlBroadcastReceiver;
import com.smartdevicelink.transport.SdlRouterService;
import com.smartdevicelink.transport.TransportConstants;
import com.smartdevicelink.util.AndroidTools;
import com.smartdevicelink.util.DebugTool;

/** Wakes the SDL service when the head unit connects (Bluetooth or USB). Ported from the SDL sample. */
public class SdlReceiver extends SdlBroadcastReceiver {
    private static final String TAG = "SdlBroadcastReceiver";
    private static final String ACTION_USB_PERMISSION = "com.aaronzandman.relay.USB_PERMISSION";
    private PendingIntent pendingIntentToStartService;
    private Intent startSdlServiceIntent;

    @Override
    public void onSdlEnabled(Context context, Intent intent) {
        DebugTool.logInfo(TAG, "SDL Enabled");
        intent.setClass(context, SdlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent pendingIntent = (PendingIntent) intent.getParcelableExtra(TransportConstants.PENDING_INTENT_EXTRA);
            if (pendingIntent != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    if (!AndroidTools.hasForegroundServiceTypePermission(context)) {
                        requestUsbAccessory(context);
                        startSdlServiceIntent = intent;
                        this.pendingIntentToStartService = pendingIntent;
                        return;
                    }
                }
                try {
                    pendingIntent.send(context, 0, intent);
                } catch (PendingIntent.CanceledException e) {
                    e.printStackTrace();
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }
    }

    @Override
    public Class<? extends SdlRouterService> defineLocalSdlRouterClass() {
        return com.aaronzandman.relay.SdlRouterService.class;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent); // Required if overriding this method
    }

    @Override
    public String getSdlServiceName() {
        return SdlService.class.getSimpleName();
    }

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action) && context != null && startSdlServiceIntent != null && pendingIntentToStartService != null) {
                if (AndroidTools.hasForegroundServiceTypePermission(context)) {
                    try {
                        pendingIntentToStartService.send(context, 0, startSdlServiceIntent);
                        context.unregisterReceiver(this);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    };

    private void requestUsbAccessory(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbAccessory[] accessoryList = manager.getAccessoryList();
        if (accessoryList == null || accessoryList.length == 0) {
            startSdlServiceIntent = null;
            pendingIntentToStartService = null;
            return;
        }
        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        AndroidTools.registerReceiver(context, usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED);
        for (final UsbAccessory usbAccessory : accessoryList) {
            manager.requestPermission(usbAccessory, mPermissionIntent);
        }
    }
}
