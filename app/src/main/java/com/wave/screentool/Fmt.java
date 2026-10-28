package com.wave.screentool;

import java.util.TimeZone;

/** 时间文字格式化（尽量不产生垃圾对象，省资源）。 */
final class Fmt {
    private Fmt() {}

    static String two(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }

    /** 秒表文字。style: 0全秒数字 1动态时:分:秒 2自适应(前adaptMin分钟显示秒，之后只显示分钟数)。 */
    static String watch(long s, int style, int adaptMin) {
        if (s < 0) s = 0;
        if (style == 0) return Long.toString(s);
        if (style == 2) {
            if (s < adaptMin * 60L) return Long.toString(s);
            return (s / 60) + "m";
        }
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        if (h > 0) return h + ":" + two(m) + ":" + two(sec);
        if (m > 0) return m + ":" + two(sec);
        return Long.toString(sec);
    }

    /** 系统时间 HH:mm:ss */
    static String clock(long nowMs) {
        long off = TimeZone.getDefault().getOffset(nowMs);
        long d = ((nowMs + off) / 1000) % 86400;
        if (d < 0) d += 86400;
        return two(d / 3600) + ":" + two((d / 60) % 60) + ":" + two(d % 60);
    }
}
