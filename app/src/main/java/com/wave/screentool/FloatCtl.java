package com.wave.screentool;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/** 启动/停止/刷新悬浮服务的入口。 */
final class FloatCtl {
    private FloatCtl() {}

    static boolean running() {
        return FloatService.running;
    }

    /** 这次启动是否需要“显示在其他应用上层”权限（通知模式、增强模式不需要）。 */
    static boolean needsOverlay(Context c, String mode) {
        if ("notif".equals(mode)) return false;
        if (!Prefs.getBool(c, "use_ball") && !Prefs.getBool(c, "use_curtain") && mode == null) return false;
        if (Prefs.getBool(c, "enhance") && KeepAccessibilityService.inst != null) return false;
        return true;
    }

    /** 从界面启动。mode: null=按设置, ball / curtain / notif。interactive=false 时缺权限不弹设置页。 */
    static void start(Activity a, String mode, boolean interactive) {
        if (needsOverlay(a, mode) && !Sys.canOverlay(a)) {
            if (interactive) {
                Toast.makeText(a, S.t("Please allow \"Display over other apps\" first"), Toast.LENGTH_LONG).show();
                openOverlaySettings(a);
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.getBool(a, "asked_notif")
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Prefs.putBool(a, "asked_notif", true);
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        startPlain(a, mode);
    }

    static void start(Activity a) {
        start(a, null, true);
    }

    static void startPlain(Context c, String mode) {
        Intent i = new Intent(c, FloatService.class);
        if (mode != null) i.putExtra("mode", mode);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /**
     * 停止服务（已通过保护控制器的检查之后才调用，或在不需要验证时）。
     * 服务自己收到 ACT_STOP 时还会再检查一次：上锁期间没验证过的停止请求会被拒绝并转为验证。
     */
    static void stop(Context c) {
        if (running()) c.startService(new Intent(c, FloatService.class).setAction(FloatService.ACT_STOP));
    }

    /** 验证页打开/结束时通知服务：撤掉遮罩避免盖住系统输入界面，结束后恢复并（需要时）继续原操作。 */
    static void verifyBegin(Context c) {
        if (!running()) return;
        try {
            c.startService(new Intent(c, FloatService.class).setAction(FloatService.ACT_VERIFY_BEGIN));
        } catch (Exception ignored) {
        }
    }

    static void verifyEnd(Context c, boolean ok, Protect.Purpose p, boolean forService) {
        if (!running()) return;
        try {
            c.startService(new Intent(c, FloatService.class).setAction(FloatService.ACT_VERIFY_END)
                    .putExtra("ok", ok).putExtra("purpose", p.name()).putExtra("for_service", forService));
        } catch (Exception ignored) {
        }
    }

    /** 手动上锁（设置页按钮、通知按钮、快捷方式）。返回是否成功；失败原因由调用方提示。 */
    static boolean lock(Context c) {
        return Protect.lockNow(c, true);
    }

    /** 已在运行时切换形态：ball / curtain / notif。 */
    static void setMode(Context c, String mode) {
        if (running()) {
            c.startService(new Intent(c, FloatService.class)
                    .setAction(FloatService.ACT_SETMODE).putExtra("mode", mode));
        }
    }

    /** 设置改变后通知服务重新加载（仅在运行时）。 */
    static void refresh(Context c) {
        if (running()) c.startService(new Intent(c, FloatService.class).setAction(FloatService.ACT_REFRESH));
    }

    static void openOverlaySettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + c.getPackageName()));
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
