package com.wave.screentool;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 下拉通知栏里的快速开关磁贴。 */
public class ToggleTileService extends TileService {
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        update();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean running = FloatCtl.running();
        if (running && !Protect.needVerify(this, Protect.Purpose.STOP)) {
            FloatCtl.stop(this);
            h.postDelayed(this::update, 400);
            return;
        }
        // 上锁期间停止要先验证：交给 ToggleActivity 走统一的保护检查
        Intent i = new Intent(this, ToggleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (running) i.putExtra("mode", "stop");
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void update() {
        S.initLite(this);
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(FloatCtl.running() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(S.t("Screen keep-on"));
        t.updateTile();
    }
}
