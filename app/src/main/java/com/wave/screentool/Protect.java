package com.wave.screentool;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * 统一保护控制器：所有会降低保护程度的入口（点幕布、返回键、通知按钮、磁贴、快捷方式、
 * 打开设置、备份、改保护策略、恢复默认、清除数据……）都必须先问这里。
 *
 * 状态只有两个：
 *  - 上锁（locked）：幕布全屏盖住，解除必须验证；重启后仍保持（存在 pv_locked，写入用 commit 立即落盘）。
 *  - 验证会话（session）：验证通过后一段时间内不用再验证；任何上锁动作都会立刻让会话作废。
 *
 * 几条原则：
 *  1. 验证方式不可用时不会悄悄降级：保持锁定并明确告知（见 methodProblem）。
 *  2. 低电量/定时关闭常亮、护眼休息结束/跳过，都不会改变上锁状态，更不会算作“验证通过”。
 *  3. 紧急释放只撤掉遮罩，不授予会话：设置、备份、策略仍然需要验证。
 */
final class Protect {
    private Protect() {}

    enum Purpose {
        UNLOCK,     // 解除遮罩（点幕布、返回键、切换形态）
        STOP,       // 停止常亮服务（通知/磁贴/快捷方式/设置页的关闭）
        SETTINGS,   // 打开设置
        BACKUP,     // 备份与恢复
        POLICY,     // 修改保护策略、清除全部数据
        RESET,      // 恢复默认设置
        TEST        // 自检：只试验验证流程，不授权任何东西
    }

    interface Listener {
        void onLockChanged(boolean locked);
    }

    static volatile Listener listener;

    private static volatile boolean locked;
    private static volatile boolean loaded;
    private static volatile long sessionUntil;     // elapsedRealtime，0=无
    private static volatile boolean uiSession;     // 有效期为 0 时：本次打开应用期间有效
    private static volatile int sessionMask;       // 当前会话授权的范围：1 设置 2 备份 4 保护策略
    private static volatile int epoch;
    private static volatile int uiCount;           // 处于前台的本应用页面数
    private static volatile long externalUntil;    // 在这之前离开应用去系统页面，不算“离开应用”

    // ---------------- 配置读取 ----------------

    static boolean armed(Context c) {
        return Prefs.getBool(c, "pv_on");
    }

    static int mode(Context c) {
        int m = Prefs.getInt(c, "pv_mode");
        return m < 1 || m > 3 ? 1 : m;
    }

    static boolean needApp(Context c) {
        return (mode(c) & 1) != 0;
    }

    static boolean needSys(Context c) {
        return (mode(c) & 2) != 0;
    }

    /** 当前验证方式有什么问题；null 表示可用。有问题时一律保持锁定，不降级。 */
    static String methodProblem(Context c) {
        if (needApp(c) && !PrivacyLock.hasPassword(c)) {
            return S.t("No privacy password is set, so verification is unavailable.");
        }
        if (needSys(c) && !Caps.sysCred(c)) {
            return S.t("System verification is unavailable because this device has no screen lock. The app does not fall back to a weaker method.");
        }
        return null;
    }

    // ---------------- 是否需要验证 ----------------

    /**
     * 这个操作现在要不要先验证（passEpoch：调用方这个页面本身已经验证过的“代”，没有就传 -1）。
     * 注意：保护开着但验证方式不可用时，也返回 true（调用方会进入验证页并显示原因，而不是放行）。
     *
     * 授权范围的含义：保护开启后，设置、备份、修改保护策略一律要验证；
     * 一次验证“同时授权”哪些范围，由 pv_sc_settings / pv_sc_backup / pv_sc_policy 决定——
     *  - 开：这次验证在“验证有效期”内一并放行该范围，不再追问；
     *  - 关：该范围不被别的验证顺带放行，每次进入都要重新验证（只有刚为它验证过的那个页面本身可以继续使用）。
     * 解除遮罩/停止服务不属于会话授权：上锁期间每次都要验证。
     */
    static boolean needVerify(Context c, Purpose p, int passEpoch) {
        if (!armed(c)) return false;
        if (p == Purpose.TEST) return true;
        // 解除遮罩/停止服务：上锁期间每次都要验证，不看会话（会话可能是为了别的目的刚授予的）
        if (p == Purpose.UNLOCK || p == Purpose.STOP) return isLocked(c);
        if (passEpoch >= 0 && passEpoch == epoch) return false;
        return !(sessionValid() && (sessionMask & bit(p)) != 0);
    }

    static boolean needVerify(Context c, Purpose p) {
        return needVerify(c, p, -1);
    }

    private static int bit(Purpose p) {
        switch (p) {
            case SETTINGS:
            case RESET:
                return 1;
            case BACKUP:
                return 2;
            case POLICY:
                return 4;
            default:
                return 0;
        }
    }

    /** 页面“代”：每次会话作废（上锁、离开应用、验证有效期结束）都会 +1，旧页面的“已验证”标记随之失效。 */
    static int epoch() {
        return epoch;
    }

    // ---------------- 验证会话 ----------------

    /** 验证通过后：按“授权范围”开关授予会话，有效期见 pv_relock_min。 */
    static void grantSession(Context c) {
        int mask = (Prefs.getBool(c, "pv_sc_settings") ? 1 : 0) | (Prefs.getBool(c, "pv_sc_backup") ? 2 : 0)
                | (Prefs.getBool(c, "pv_sc_policy") ? 4 : 0);
        sessionMask = mask;
        int min = Prefs.getInt(c, "pv_relock_min");
        if (min > 0) {
            sessionUntil = SystemClock.elapsedRealtime() + min * 60_000L;
            uiSession = false;
        } else {
            sessionUntil = 0;
            uiSession = true;
        }
    }

    static boolean sessionValid() {
        if (sessionUntil > 0 && SystemClock.elapsedRealtime() < sessionUntil) return true;
        return uiSession && uiCount > 0;
    }

    static void revokeSession() {
        sessionUntil = 0;
        uiSession = false;
        sessionMask = 0;
        epoch++;
    }

    /** 本应用页面都离开后：“本次打开应用期间有效”的会话到此结束；已验证的页面也要重新验证。 */
    static void endUiSession() {
        uiSession = false;
        epoch++;
    }

    // ---------------- 上锁状态 ----------------

    private static void load(Context c) {
        if (!loaded) {
            locked = Prefs.getBool(c, "pv_locked");
            loaded = true;
        }
    }

    static boolean isLocked(Context c) {
        load(c);
        return locked && armed(c);
    }

    static void setLocked(Context c, boolean v) {
        load(c);
        boolean changed = locked != v;
        locked = v;
        Prefs.putBoolNow(c, "pv_locked", v);
        if (v) revokeSession();
        Listener l = listener;
        if (changed && l != null) l.onLockChanged(v);
    }

    /**
     * 立即上锁。服务没在运行时没有遮罩可盖：只让验证会话作废（设置页仍需验证），不改上锁状态——
     * 除非 startIfNeeded=true（手动上锁），此时会先拉起服务并显示遮罩。返回是否真的上了锁。
     */
    static boolean lockNow(Context c, boolean startIfNeeded) {
        if (!armed(c)) return false;
        revokeSession();
        if (FloatCtl.running()) {
            setLocked(c, true);
            return true;
        }
        if (!startIfNeeded) return false;
        if (!Caps.canShowCover(c)) return false;
        setLocked(c, true);
        try {
            FloatCtl.startPlain(c, "curtain");
        } catch (Exception e) {
            setLocked(c, false);
            return false;
        }
        return true;
    }

    /** 验证通过后解除上锁并授予会话。 */
    static void completeUnlock(Context c) {
        setLocked(c, false);
        grantSession(c);
    }

    /** 紧急释放：撤掉遮罩，但不授予会话——受保护的数据和设置仍然需要验证。 */
    static void emergencyRelease(Context c) {
        revokeSession();
        setLocked(c, false);
        Prefs.putStr(c, "pv_last_emerg", String.valueOf(System.currentTimeMillis()));
    }

    /** 恢复码验证通过后：作废恢复码和密码，保护总开关一并关闭，让用户重新设置。 */
    static void resetAfterRecovery(Context c) {
        PrivacyLock.consumeRecoveryCode(c);
        Prefs.sp(c).edit().putBoolean("pv_on", false).commit();
        setLocked(c, false);
        revokeSession();
    }

    // ---------------- 本应用页面前后台（App.java 调用） ----------------

    static void uiStarted() {
        uiCount++;
    }

    static void uiStopped() {
        if (uiCount > 0) uiCount--;
    }

    static int uiCount() {
        return uiCount;
    }

    /** 即将打开系统页面（文件选择器、权限页等）：90 秒内离开本应用不算“离开应用”。 */
    static void expectExternal() {
        externalUntil = SystemClock.elapsedRealtime() + 90_000L;
    }

    static boolean externalExpected() {
        return SystemClock.elapsedRealtime() < externalUntil;
    }

    static void clearExternal() {
        externalUntil = 0;
    }

    // ---------------- 设置项约束 ----------------

    /**
     * 想把某个隐私设置改成 newVal 时，检查组合是否允许。返回 null 表示允许，否则返回（已翻译的）原因。
     * 不是所有开关都能任意叠加：不允许时界面不改动设置并说明原因。
     */
    static String check(Context c, String key, Object newVal) {
        boolean on = newVal instanceof Boolean && (Boolean) newVal;
        int iv = newVal instanceof Integer ? (Integer) newVal : 0;
        switch (key) {
            case "pv_on": {
                if (!on) return null;
                String p = methodProblem(c);
                if (p != null) return p;
                return null;
            }
            case "pv_mode": {
                if ((iv & 1) != 0 && !PrivacyLock.hasPassword(c)) return S.t("Set a password first.");
                if ((iv & 2) != 0 && !Caps.sysCred(c)) {
                    return S.t("System verification needs a device screen lock (PIN, pattern or password). Set one in the system settings first.");
                }
                return null;
            }
            case "pv_trig_expand":
                if (on && !Prefs.getBool(c, "use_curtain")) {
                    return S.t("The curtain is turned off, so there is nothing to lock when it expands. Turn the curtain on first (Settings > Floating window).");
                }
                if (on && !Caps.canShowCover(c)) {
                    return S.t("This trigger locks the curtain, which needs the \"Display over other apps\" permission or the enhanced overlay.");
                }
                return null;
            case "pv_trig_screenoff":
            case "pv_trig_leave":
                if (on && !Caps.canShowCover(c)) {
                    return S.t("This trigger locks the curtain, which needs the \"Display over other apps\" permission or the enhanced overlay.");
                }
                return null;
            case "pv_trig_timer":
                if (iv > 0 && !Caps.canShowCover(c)) {
                    return S.t("This trigger locks the curtain, which needs the \"Display over other apps\" permission or the enhanced overlay.");
                }
                return null;
            case "pv_ui_notap":
                return null;   // 打开时界面会同时把“返回需验证”置为开
            case "pv_ui_back":
                if (!on && Prefs.getBool(c, "pv_ui_notap")) {
                    return S.t("Taps are disabled, so the Back key must stay the way to unlock. Allow taps first.");
                }
                return null;
            case "pv_ui_enhanced":
                if (on && !Caps.a11y()) {
                    return S.t("The enhanced cover needs the accessibility service to be enabled (Settings > Stability guide).");
                }
                return null;
            case "pv_fail_limit":
                if (iv < 1) return S.t("The limit must be at least 1.");
                if (iv < 3 && (FailPolicy.wipeFiles(c) || FailPolicy.wipeData(c))) {
                    return S.t("Data clean-up needs a limit of at least 3 so a slip of the finger cannot trigger it.");
                }
                return null;
            case "pv_fail_wipe_files":
                if (!on) return null;
                if (Prefs.authUris(c).isEmpty()) {
                    return S.t("Add at least one authorized file or folder first. Without authorization there is nothing this clean-up can delete.");
                }
                if (Caps.authValid(c) < Prefs.authUris(c).size()) {
                    return S.t("Some authorized items have lost their read/write access. Fix or remove them first, otherwise the clean-up could not be confirmed.");
                }
                if (FailPolicy.limit(c) < 3) return S.t("Data clean-up needs a limit of at least 3 so a slip of the finger cannot trigger it.");
                return null;
            case "pv_fail_wipe_data":
                if (!on) return null;
                if (!Caps.canClearData()) return S.t("This Android version cannot clear app data from inside the app.");
                if (FailPolicy.limit(c) < 3) return S.t("Data clean-up needs a limit of at least 3 so a slip of the finger cannot trigger it.");
                return null;
            case "pv_emerg_on":
                // 关闭紧急释放前，必须保证“验证”这条正路现在是通的，否则上锁后可能出不来
                if (!on && armed(c) && methodProblem(c) != null) {
                    return S.t("Verification is not working right now, so the emergency release has to stay on. Fix the verification method first.");
                }
                return null;
            case "pv_emerg_sec":
                if (iv < 5 || iv > 60) return S.t("Choose between 5 and 60 seconds.");
                return null;
            default:
                return null;
        }
    }

    /** 移除密码前的检查：还有哪个已启用的功能离不开它。 */
    static String checkRemovePassword(Context c) {
        if (armed(c) && needApp(c)) {
            return S.t("Protection is on and uses the password. Turn protection off, or switch to system verification only, before removing the password.");
        }
        return null;
    }

    /** 移除最后一个授权项前的检查。 */
    static String checkRemoveAuth(Context c) {
        if (Prefs.authUris(c).size() <= 1 && Prefs.getBool(c, "pv_fail_wipe_files")) {
            return S.t("Clearing authorized files is switched on. Turn it off before removing the last item.");
        }
        return null;
    }

    // ---------------- 旧版本迁移 ----------------

    /**
     * 一次性迁移：保留旧版的密码/恢复码/授权列表/阈值，但保护总开关一律关闭——
     * 升级不会把现有设置自动变成密码锁。
     */
    static void migrate(Context c) {
        SharedPreferences sp = Prefs.sp(c);
        if (sp.getInt("pv_ver", 0) >= 1) return;
        SharedPreferences.Editor ed = sp.edit();
        if (sp.contains("priv_auth_uri")) ed.putString("pv_auth_uri", sp.getString("priv_auth_uri", ""));
        if (sp.contains("priv_relock_min")) ed.putInt("pv_relock_min", sp.getInt("priv_relock_min", 0));
        int fa0 = sp.getInt("priv_fail_action", 0);
        int lim = Math.max(1, sp.getInt("priv_fail_limit", 5));
        ed.putInt("pv_fail_limit", fa0 >= 2 ? Math.max(3, lim) : lim);   // 清理类动作的阈值至少 3 次
        boolean sys = sp.getBoolean("priv_sys_cred", false), app = sp.getBoolean("priv_app_pwd", true);
        ed.putInt("pv_mode", sys ? (app ? 3 : 2) : 1);
        if (sp.contains("priv_screenshot")) ed.putBoolean("pv_ui_secure", sp.getBoolean("priv_screenshot", false));
        int fa = sp.getInt("priv_fail_action", 0);
        ed.putBoolean("pv_fail_cool", fa == 1);
        ed.putBoolean("pv_fail_wipe_data", fa == 2 || fa == 4);
        ed.putBoolean("pv_fail_wipe_files", fa == 3 || fa == 4);
        ed.putBoolean("pv_on", false).putBoolean("pv_locked", false).putInt("pv_ver", 1);
        for (String k : new String[]{"priv_enabled", "priv_master", "priv_protect_settings", "priv_sys_cred", "priv_app_pwd",
                "priv_relock_min", "priv_screenshot", "priv_fail_action", "priv_fail_limit", "priv_auth_uri",
                "cur_priv_exit", "_priv_migrated"}) {
            ed.remove(k);
        }
        ed.commit();
    }
}
