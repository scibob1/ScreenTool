package com.wave.screentool;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启（需要在设置里打开“开机自动启动”）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (!Prefs.getBool(c, "boot_start")) return;
        String a = Prefs.getStr(c, "auto_start");
        String mode = "notif".equals(a) ? "notif" : null;
        if (FloatCtl.needsOverlay(c, mode) && !Sys.canOverlay(c)) mode = "notif";
        try {
            FloatCtl.startPlain(c, mode);
        } catch (Exception ignored) {
        }
    }
}
