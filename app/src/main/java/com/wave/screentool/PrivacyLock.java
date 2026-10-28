package com.wave.screentool;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Locale;

/**
 * 凭据与失败冷却。密码哈希、恢复码、失败计数都存在独立的 priv_cred 文件里：
 * 不进备份文件、不受“恢复默认设置”影响。只存哈希，从不存明文。
 *
 * 冷却时间用“开机以来时间 + 墙上时钟 + 开机次数”三份信息一起记录：
 * 同一次开机内按开机以来时间算（改系统时间无效）；重启之后按墙上时钟补算（重启不能清零冷却）。
 */
final class PrivacyLock {
    private PrivacyLock() {}

    private static SharedPreferences cached;

    static SharedPreferences cred(Context c) {
        SharedPreferences p = cached;
        if (p == null) {
            p = c.getApplicationContext().getSharedPreferences("priv_cred", Context.MODE_PRIVATE);
            cached = p;
        }
        return p;
    }

    // ---------------- 密码 ----------------

    static boolean hasPassword(Context c) {
        return cred(c).getString("priv_hash", "").length() > 0;
    }

    static void setPassword(Context c, String password) {
        byte[] salt = Crypto.randomBytes(16);
        String h = Crypto.hash(password, salt);
        if (h == null) return;
        cred(c).edit().putString("priv_salt", Crypto.b64(salt)).putString("priv_hash", h).commit();
        clearBackoff(c);
    }

    static boolean verifyPassword(Context c, String password) {
        SharedPreferences cr = cred(c);
        return Crypto.verify(password, cr.getString("priv_salt", ""), cr.getString("priv_hash", ""));
    }

    static void clearPassword(Context c) {
        cred(c).edit().putString("priv_hash", "").putString("priv_salt", "").commit();
        clearBackoff(c);
    }

    // ---------------- 恢复码 ----------------

    static String generateRecoveryCode(Context c) {
        String code = Crypto.recoveryCode();
        byte[] salt = Crypto.randomBytes(16);
        cred(c).edit().putString("priv_rec_salt", Crypto.b64(salt))
                .putString("priv_rec_hash", Crypto.hash(code, salt)).commit();
        return code;
    }

    static boolean hasRecoveryCode(Context c) {
        return cred(c).getString("priv_rec_hash", "").length() > 0;
    }

    static boolean verifyRecoveryCode(Context c, String code) {
        if (code == null) return false;
        SharedPreferences cr = cred(c);
        return Crypto.verify(code.trim().toUpperCase(Locale.US),
                cr.getString("priv_rec_salt", ""), cr.getString("priv_rec_hash", ""));
    }

    /** 恢复码用掉即作废，同时清掉密码；调用方负责关闭保护总开关（见 Protect.resetAfterRecovery）。 */
    static void consumeRecoveryCode(Context c) {
        cred(c).edit().putString("priv_hash", "").putString("priv_salt", "")
                .putString("priv_rec_hash", "").putString("priv_rec_salt", "").commit();
        clearBackoff(c);
    }

    // ---------------- 失败计数与冷却 ----------------

    private static final long[] COOLDOWN_MS = {0, 0, 0, 0, 30_000, 60_000, 120_000, 300_000, 900_000};

    private static int bootCount(Context c) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** 记录一次失败，并按内置阶梯设置冷却。返回当前连续失败次数。 */
    static int onFail(Context c) {
        SharedPreferences cr = cred(c);
        int n = cr.getInt("priv_fail_n", 0) + 1;
        long cd = COOLDOWN_MS[Math.min(n, COOLDOWN_MS.length - 1)];
        cr.edit().putInt("priv_fail_n", n).commit();
        if (cd > 0) lockFor(c, cd);
        return n;
    }

    /** 把锁定截止时间延长到“现在起 ms 毫秒”（只会延长，不会缩短）。 */
    static void lockFor(Context c, long ms) {
        long left = cooldownSecondsLeft(c) * 1000L;
        if (ms <= left) return;
        cred(c).edit()
                .putLong("priv_lock_el", SystemClock.elapsedRealtime() + ms)
                .putLong("priv_lock_wall", System.currentTimeMillis() + ms)
                .putInt("priv_lock_boot", bootCount(c)).commit();
    }

    static void clearBackoff(Context c) {
        cred(c).edit().putInt("priv_fail_n", 0).putLong("priv_lock_el", 0).putLong("priv_lock_wall", 0).commit();
    }

    static int failCount(Context c) {
        return cred(c).getInt("priv_fail_n", 0);
    }

    static long cooldownSecondsLeft(Context c) {
        SharedPreferences cr = cred(c);
        long el = cr.getLong("priv_lock_el", 0), wall = cr.getLong("priv_lock_wall", 0);
        if (el == 0 && wall == 0) return 0;
        long left;
        if (cr.getInt("priv_lock_boot", 0) == bootCount(c) && el > 0) {
            left = el - SystemClock.elapsedRealtime();
        } else {
            left = wall - System.currentTimeMillis();
        }
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    // ---------------- 旧版本迁移 ----------------

    /** 1.4.x 的凭据曾放在 cfg 文件里，一次性搬到 priv_cred，并把旧的 elapsedRealtime 冷却丢弃（重启后本来就无效）。 */
    static void migrateLegacy(Context c) {
        SharedPreferences cfg = Prefs.sp(c);
        SharedPreferences cr = cred(c);
        if (cr.getBoolean("_mig2", false)) return;
        SharedPreferences.Editor ed = cr.edit();
        if (cfg.contains("priv_hash") && !cr.contains("priv_hash")) {
            for (String k : new String[]{"priv_hash", "priv_salt", "priv_rec_hash", "priv_rec_salt"}) {
                String v = cfg.getString(k, null);
                if (v != null) ed.putString(k, v);
            }
        }
        // 旧文件里的失败计数键同名且类型相同，若已迁移则保持原值
        ed.putBoolean("_mig2", true).commit();
        cfg.edit().remove("priv_hash").remove("priv_salt").remove("priv_rec_hash")
                .remove("priv_rec_salt").remove("priv_fail_n").remove("priv_lock_until").commit();
    }
}
