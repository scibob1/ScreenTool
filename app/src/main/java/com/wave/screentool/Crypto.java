package com.wave.screentool;

import android.os.Build;
import android.util.Base64;

import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 口令哈希（PBKDF2 加盐）。纯本地计算，不联网。
 *
 * 存储格式：
 *  - 新格式  "2$迭代次数$Base64"  → PBKDF2WithHmacSHA256（Android 8.0+）
 *            "1$迭代次数$Base64"  → PBKDF2WithHmacSHA1（Android 8.0 以下）
 *  - 旧格式  只有 Base64，没有 "$"      → 1.3.x/1.4.x 版本的 PBKDF2WithHmacSHA1 / 150000 次，仍可验证
 *
 * 注意：以前这里用 java.util.Base64（需要 Android 8.0），在 Android 5–7 上会直接崩溃，现已改用 android.util.Base64。
 */
final class Crypto {
    private Crypto() {}

    static final int ITER = 150_000;
    private static final int KEY_BITS = 256;
    private static final String RECOVERY_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // 去掉易混的 I O 0 1

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    static String hash(String pw, byte[] salt) {
        boolean v2 = Build.VERSION.SDK_INT >= 26;
        String raw = derive(pw, salt, ITER, v2);
        return raw == null ? null : (v2 ? "2$" : "1$") + ITER + "$" + raw;
    }

    private static String derive(String pw, byte[] salt, int iter, boolean sha256) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, iter, KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance(sha256 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1");
            return b64(f.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            return null;
        }
    }

    static boolean verify(String pw, String saltB64, String stored) {
        if (pw == null || saltB64 == null || stored == null || saltB64.isEmpty() || stored.isEmpty()) return false;
        try {
            byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
            String calc;
            String want;
            if (stored.indexOf('$') > 0) {
                String[] p = stored.split("\\$");
                if (p.length != 3) return false;
                boolean sha256 = "2".equals(p[0]);
                if (sha256 && Build.VERSION.SDK_INT < 26) return false;   // 备份/迁移到旧系统后无法验证
                calc = derive(pw, salt, Integer.parseInt(p[1]), sha256);
                want = p[2];
            } else {
                calc = derive(pw, salt, ITER, false);
                want = stored;
            }
            return calc != null && same(calc, want);
        } catch (Exception e) {
            return false;
        }
    }

    /** 恒定时间比较，避免通过耗时推测内容。 */
    static boolean same(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    /** 恢复码：XXXXX-XXXXX。 */
    static String recoveryCode() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) sb.append('-');
            sb.append(RECOVERY_CHARS.charAt(r.nextInt(RECOVERY_CHARS.length())));
        }
        return sb.toString();
    }
}
