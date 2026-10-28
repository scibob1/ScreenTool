package com.wave.screentool;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * 亮度控制。有些系统（如华为/鸿蒙）不理会悬浮窗自带的亮度属性，
 * 所以在拿到“修改系统设置”权限后，同时写入系统亮度；用完恢复原来的亮度和自动亮度开关。
 */
final class BrightnessCtl {
    private BrightnessCtl() {}

    static boolean canWrite(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(c);
    }

    static boolean useSystem(Context c) {
        return !"window".equals(Prefs.getStr(c, "bright_mode")) && canWrite(c);
    }

    /** 滑杆位置 -> 0..255 */
    static int toSystem(float p) {
        return Math.max(1, Math.min(255, Math.round(255f * Prefs.windowBrightness(p))));
    }

    private static int lastWritten = -1;   // 上一次写入系统的亮度值，相同就不再重复写

    static void apply(Context c, float p) {
        if (!useSystem(c)) return;
        int v = toSystem(p);
        if (v == lastWritten && Prefs.getBool(c, "bright_mod")) return;
        try {
            ContentResolver cr = c.getContentResolver();
            if (!Prefs.getBool(c, "bright_mod")) {
                Prefs.putInt(c, "orig_mode", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1));
                Prefs.putInt(c, "orig_bright", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128));
                Prefs.sp(c).edit().putBoolean("bright_mod", true).commit();
            }
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v);
            lastWritten = v;
        } catch (Exception ignored) {
        }
    }

    static void restore(Context c) {
        if (!Prefs.getBool(c, "bright_mod")) return;
        lastWritten = -1;
        try {
            ContentResolver cr = c.getContentResolver();
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Prefs.getInt(c, "orig_bright"));
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Prefs.getInt(c, "orig_mode"));
        } catch (Exception ignored) {
        }
        Prefs.sp(c).edit().putBoolean("bright_mod", false).commit();
    }

    /** 上次异常退出（被杀）没来得及恢复时，下次打开应用补恢复。 */
    static void restoreIfStale(Context c) {
        if (!FloatCtl.running()) restore(c);
    }
}
