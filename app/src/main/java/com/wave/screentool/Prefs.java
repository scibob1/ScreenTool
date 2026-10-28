package com.wave.screentool;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** 所有设置项的读写与默认值。 */
final class Prefs {
    static final HashMap<String, Object> DEF = new HashMap<>();

    static {
        DEF.put("lang", "auto");          // auto / zh / en
        DEF.put("home_time", true);       // 主页显示系统时间
        DEF.put("brightness", 0.3f);      // 亮度滑杆位置 0..1
        DEF.put("bright_mode", "auto");   // auto=有权限用系统亮度 / window=仅窗口亮度 / system=仅系统亮度
        DEF.put("use_ball", true);        // 启用悬浮球
        DEF.put("use_curtain", true);     // 启用悬浮幕布
        DEF.put("keep_method", "both");   // both / window / wakelock
        DEF.put("start_mode", "ball");    // 悬浮启动形态 ball / curtain
        DEF.put("op_sec", 600);           // 操作窗口期（秒），0=防误触
        DEF.put("reset_on_ball", true);   // 幕布切回悬浮球时重置秒表
        DEF.put("ball_dim", false);       // 悬浮球/无窗口状态也使用低亮度
        DEF.put("ball_sw", true);         // 悬浮球显示秒表
        DEF.put("ball_clock", 1);         // 0全秒 1时:分:秒(动态) 2自适应
        DEF.put("adapt_min", 5);          // 自适应：前几分钟显示秒
        DEF.put("ball_grow", true);       // true=球随文字变大 false=球不变文字变小
        DEF.put("ball_dp", 36);           // 悬浮球基础大小
        DEF.put("ball_shape", 0);         // 0圆形 1长方形
        DEF.put("ball_side", 1);          // 0靠左 1靠右
        DEF.put("ball_y", 0.35f);         // 悬浮球纵向位置(屏幕比例)
        DEF.put("ball_edge", true);       // 悬浮球 5 秒无操作自动贴边半隐藏
        DEF.put("cur_sw", false);         // 幕布显示秒表
        DEF.put("cur_time", true);        // 幕布显示系统时间
        DEF.put("cur_l", 0f);
        DEF.put("cur_t", 0f);
        DEF.put("cur_r", 1f);
        DEF.put("cur_b", 1f);
        DEF.put("burn", true);            // 防烧屏微移
        DEF.put("auto_off", 0);           // 定时自动关闭（分钟），0=不关闭
        DEF.put("charge_only", false);    // 仅充电时保持常亮
        DEF.put("low_batt", 15);          // 低电量自动停止（%），0=关闭
        DEF.put("vol_keys", true);        // 幕布状态下音量键调亮度
        DEF.put("auto_start", "float");   // 打开应用时自动启动：none / float / notif
        DEF.put("boot_start", false);     // 开机自启
        DEF.put("enhance", false);        // 增强模式（无障碍悬浮窗）
        DEF.put("eye_on", false);         // 护眼强制休息
        DEF.put("eye_iv", 20);            // 间隔（分钟）
        DEF.put("eye_len", 60);           // 休息时长（秒）
        DEF.put("eye_warn", 5);           // 提前预告（秒，用短 Toast 提示），0=不预告
        DEF.put("eye_style", "");         // 护眼休息专用风格 id，空=跟随当前风格
        DEF.put("style_active", "black");
        DEF.put("style_order", "");
        DEF.put("styles", "[]");
        DEF.put("active_colors", "");     // 当前风格的颜色缓存（运行时只读这个）
        DEF.put("active_imgs", "");       // 当前风格各槽位的图片集 id 缓存
        DEF.put("imgsets", "[]");         // 图片集（只存链接）
        DEF.put("note_actions", true);    // 通知栏显示操作按钮
        DEF.put("nowin_method", "auto");  // 无窗口模式的常亮方式：auto / wakelock / keeper
        DEF.put("cover_bars", true);      // 幕布尽量盖住状态栏和导航栏
        DEF.put("vib_off", false);        // 熄屏后间歇震动提醒
        DEF.put("vib_iv", 5);             // 震动间隔（秒），最小 2 秒
        DEF.put("asked_notif", false);
        // ---- 隐私保护（1.5.0 起整体重做，默认全部关闭）----
        DEF.put("pv_on", false);          // 总开关
        DEF.put("pv_mode", 1);            // 验证方式：1=自定义密码 2=系统锁屏凭据 3=两者都要
        DEF.put("pv_trig_timer", 0);      // 触发条件：启动/解锁后 N 分钟自动上锁，0=关
        DEF.put("pv_trig_expand", false); // 触发条件：每次展开幕布时上锁
        DEF.put("pv_trig_screenoff", false); // 触发条件：熄屏后上锁
        DEF.put("pv_trig_leave", false);  // 触发条件：离开本应用后上锁
        DEF.put("pv_ui_notap", false);    // 界面限制：点击幕布不能关闭
        DEF.put("pv_ui_back", true);      // 界面限制：返回键需要验证（上锁时）
        DEF.put("pv_ui_secure", false);   // 界面限制：防截图/录屏
        DEF.put("pv_ui_enhanced", false); // 界面限制：增强覆盖（无障碍窗口，盖住状态栏/导航栏）
        DEF.put("pv_sc_settings", true);  // 授权范围：一次验证同时授权打开设置（关=每次进入都要再验证）
        DEF.put("pv_sc_backup", true);    // 授权范围：一次验证同时授权备份/恢复
        DEF.put("pv_sc_policy", false);   // 授权范围：一次验证同时授权修改保护策略（关=每次进入都要再验证）
        DEF.put("pv_relock_min", 0);      // 验证有效期（分钟），0=只在本次打开应用期间有效
        DEF.put("pv_fail_limit", 5);      // 连续失败多少次后执行失败响应
        DEF.put("pv_fail_cool", false);   // 失败响应：逐步加长冷却
        DEF.put("pv_fail_wipe_files", false); // 失败响应：清除授权文件/文件夹
        DEF.put("pv_fail_wipe_data", false);  // 失败响应：清除软件数据
        DEF.put("pv_emerg_on", true);     // 紧急释放遮罩：总开关（默认开启）
        DEF.put("pv_emerg_sec", 10);      // 紧急释放遮罩：长按幕布秒数
        DEF.put("pv_locked", false);      // 当前是否处于上锁状态（重启后仍保持）
        DEF.put("pv_wipe_pending", 0);    // 上次清理未完成的标记（位 1=文件 2=数据）
        DEF.put("pv_auth_uri", "");       // 授权的外部文件/文件夹（SAF URI，换行分隔多个）
        DEF.put("pv_ver", 0);             // 隐私配置版本（用于一次性迁移）
        DEF.put("svc_alive", false);      // 悬浮服务是否应当在运行（被系统杀掉后用来自动恢复）
        DEF.put("exit_remove_task", false); // 双击退出主页时是否“移除任务卡片”（默认否：稳定优先）
        DEF.put("notif_enabled", true);  // 是否显示通知（关闭后服仍为前台服务，但不显示/更新通知）
        DEF.put("last_screen", 0);        // 0主页 1设置（用于从最近任务返回时回到原处）
        DEF.put("bright_mod", false);     // 是否已改动系统亮度（用于异常后恢复）
        DEF.put("orig_mode", 1);
        DEF.put("orig_bright", 128);
    }

    private static SharedPreferences cached;

    static SharedPreferences sp(Context c) {
        SharedPreferences p = cached;
        if (p == null) {
            p = c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE);
            cached = p;
        }
        return p;
    }

    static boolean getBool(Context c, String k) {
        Object d = DEF.get(k);
        return sp(c).getBoolean(k, d instanceof Boolean && (Boolean) d);
    }

    static int getInt(Context c, String k) {
        Object d = DEF.get(k);
        return sp(c).getInt(k, d instanceof Integer ? (Integer) d : 0);
    }

    static float getFloat(Context c, String k) {
        Object d = DEF.get(k);
        return sp(c).getFloat(k, d instanceof Float ? (Float) d : 0f);
    }

    static String getStr(Context c, String k) {
        Object d = DEF.get(k);
        return sp(c).getString(k, d == null ? "" : d.toString());
    }

    static void putBool(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
    /** 立即同步写入：安全相关的状态（上锁/解锁）不能只留在内存里等异步落盘。 */
    static void putBoolNow(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).commit(); }
    static void putInt(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); }
    static void putFloat(Context c, String k, float v) { sp(c).edit().putFloat(k, v).apply(); }
    static void putStr(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }

    /** 读取外部授权文件/文件夹列表（换行分隔的 SAF URI）。 */
    static List<String> authUris(Context c) {
        String s = getStr(c, "pv_auth_uri");
        ArrayList<String> out = new ArrayList<>();
        if (s != null) {
            for (String u : s.split("\n")) {
                String t = u.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    /** 覆盖写回外部授权文件/文件夹列表。 */
    static void putAuthUris(Context c, List<String> uris) {
        StringBuilder sb = new StringBuilder();
        for (String u : uris) {
            if (u == null || u.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(u.trim());
        }
        putStr(c, "pv_auth_uri", sb.toString());
    }

    /** 隐私/运行状态相关的键：不进备份、不被备份恢复覆盖、不受“恢复默认设置”影响。 */
    static boolean isProtectedKey(String k) {
        return k.startsWith("priv_") || k.startsWith("pv_") || k.startsWith("svc_") || k.startsWith("_priv");
    }

    /** 滑杆位置 -> 窗口亮度。平方曲线让低亮度区域更细腻，最低 0.4%。 */
    static float windowBrightness(float p) {
        return Math.max(0.004f, p * p);
    }
}