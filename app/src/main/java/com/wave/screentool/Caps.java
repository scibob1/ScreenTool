package com.wave.screentool;

import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;

/**
 * 能力检测：隐私保护用到的每一项能力现在到底能不能用，以及“没有通知 / 没有通知权限”时哪些功能照常、哪些受影响。
 * 设置页的“保护自检”和各个开关的启用条件都读这里，保证界面显示与真实行为一致。
 */
final class Caps {
    private Caps() {}

    static final int OK = 0, WARN = 1, FAIL = 2;

    static final class Item {
        final String title, detail;
        final int level;

        Item(int level, String title, String detail) {
            this.level = level;
            this.title = title;
            this.detail = detail;
        }
    }

    // ---------- 基础能力 ----------

    static boolean overlay(Context c) {
        return Sys.canOverlay(c);
    }

    static boolean a11y() {
        return KeepAccessibilityService.inst != null;
    }

    /** 能不能显示遮罩窗口（普通悬浮窗权限，或增强模式的无障碍窗口二选一）。 */
    static boolean canShowCover(Context c) {
        return overlay(c) || a11y();
    }

    static boolean notifPermission(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean notifEnabledBySystem(Context c) {
        if (Build.VERSION.SDK_INT < 24) return true;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    static boolean channelBlocked(Context c) {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = nm == null ? null : nm.getNotificationChannel("keepon");
            return ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用户此刻能不能在通知栏里看到并点到本应用的通知按钮。 */
    static boolean notifVisible(Context c) {
        return Prefs.getBool(c, "notif_enabled") && notifPermission(c) && notifEnabledBySystem(c) && !channelBlocked(c);
    }

    @SuppressWarnings("deprecation")
    static boolean sysCred(Context c) {
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) return false;
            return Build.VERSION.SDK_INT >= 23 ? km.isDeviceSecure() : km.isKeyguardSecure();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean canClearData() {
        return Build.VERSION.SDK_INT >= 19;
    }

    static boolean ignoringBattery(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** 实测：能不能真的往窗口管理器里加一个 1 像素窗口（权限开着但被系统限制的情况也能测出来）。 */
    static boolean probeOverlay(Context c) {
        try {
            boolean a = KeepAccessibilityService.inst != null;
            Context host = a ? KeepAccessibilityService.inst : c.getApplicationContext();
            WindowManager wm = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
            View v = new View(host);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                    a ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : Sys.overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            wm.addView(v, lp);
            wm.removeView(v);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 授权文件/文件夹里读写权限仍然有效的条数。 */
    static int authValid(Context c) {
        int n = 0;
        for (String s : Prefs.authUris(c)) {
            try {
                if (AuthCleaner.canAccess(c, android.net.Uri.parse(s))) n++;
            } catch (Exception ignored) {
            }
        }
        return n;
    }

    // ---------- 自检报告 ----------

    static ArrayList<Item> report(Context c) {
        ArrayList<Item> r = new ArrayList<>();
        boolean ov = overlay(c), ax = a11y();
        int mode = Protect.mode(c);
        boolean needSys = (mode & 2) != 0, needApp = (mode & 1) != 0;

        r.add(new Item(canShowCover(c) ? OK : FAIL, S.t("Cover window"),
                canShowCover(c)
                        ? (ov ? S.t("Available through the \"Display over other apps\" permission.")
                        : S.t("Available through the enhanced (accessibility) overlay."))
                        : S.t("Not available. Grant \"Display over other apps\" or enable the enhanced mode, otherwise the privacy cover and the verification screen above it cannot be shown.")));

        boolean wantEnh = Prefs.getBool(c, "pv_ui_enhanced");
        r.add(new Item(ax ? OK : (wantEnh ? FAIL : WARN), S.t("Enhanced overlay"),
                ax ? S.t("Accessibility service connected: the cover can sit above the status bar and navigation bar.")
                        : (wantEnh ? S.t("Selected in the privacy settings but the accessibility service is not connected. The app will say so and use the normal overlay instead; it will not pretend the enhanced cover is active.")
                        : S.t("Not connected (optional)."))));

        if (needApp) {
            boolean has = PrivacyLock.hasPassword(c);
            r.add(new Item(has ? OK : FAIL, S.t("Privacy password"),
                    has ? S.t("Set.") : S.t("No password is set, so password verification is unavailable.")));
        }
        if (needSys) {
            boolean sc = sysCred(c);
            r.add(new Item(sc ? OK : FAIL, S.t("System verification"),
                    sc ? S.t("The device has a screen lock, so system verification can be used.")
                            : S.t("This device has no screen lock (PIN, pattern or password). System verification is unavailable and the app will not silently fall back to something weaker: the cover stays locked.")));
            boolean ok = ov;
            r.add(new Item(ok ? OK : WARN, S.t("System verification from the cover"),
                    ok ? S.t("The system screen can be opened from the background because the overlay permission is granted. The cover is removed while it is shown, so it cannot block the system input screen.")
                            : S.t("Without the \"Display over other apps\" permission the system may refuse to open the verification screen from the background. Open the app first, then verify.")));
        }

        boolean np = notifPermission(c), ne = notifEnabledBySystem(c), cb = channelBlocked(c), nv = notifVisible(c);
        r.add(new Item(nv ? OK : WARN, S.t("Notification"),
                nv ? S.t("Visible. Notification buttons (Stop, Expand, Skip rest, Lock) work.")
                        : (!np ? S.t("The notification permission is not granted. The service keeps running and the privacy cover, verification screen, auto-lock triggers and the emergency release do not depend on it. Only the notification buttons are unavailable.")
                        : (!ne || cb ? S.t("Notifications are blocked in the system settings. The service keeps running and the privacy cover, verification screen, auto-lock triggers and the emergency release do not depend on it. Only the notification buttons are unavailable.")
                        : S.t("Turned off in this app. The service keeps running; privacy functions are not affected.")))));

        r.add(new Item(FloatService.running ? OK : WARN, S.t("Keep-awake service"),
                FloatService.running
                        ? (FloatService.fgsMode == 2 ? S.t("Running with a minimal silent notification.") : S.t("Running."))
                        : S.t("Not running. Lock triggers (timer, screen off, leaving the app) and the cover only work while the service is running.")));

        r.add(new Item(ignoringBattery(c) ? OK : WARN, S.t("Battery optimization"),
                ignoringBattery(c) ? S.t("Ignored (recommended).")
                        : S.t("Not ignored. The system may stop the service in the background, which also removes the cover.")));

        int total = Prefs.authUris(c).size();
        if (total > 0 || Prefs.getBool(c, "pv_fail_wipe_files")) {
            int ok = authValid(c);
            r.add(new Item(total > 0 && ok == total ? OK : FAIL, S.t("Authorized files and folders"),
                    total == 0 ? S.t("None added.") : S.f("%d of %d items have working read/write access.", ok, total)));
        }
        r.add(new Item(canClearData() ? OK : FAIL, S.t("Clear app data"),
                canClearData() ? S.t("Supported by this Android version.") : S.t("Not supported by this Android version.")));
        return r;
    }
}
