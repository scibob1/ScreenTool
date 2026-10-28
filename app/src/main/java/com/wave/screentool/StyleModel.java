package com.wave.screentool;

/** 一套风格：6 个颜色槽位（都带透明度，ARGB）。 */
final class StyleModel {
    static final int HOME = 0;     // 主页背景（同时作为设置页背景）
    static final int BALL = 1;     // 悬浮球颜色
    static final int CURTAIN = 2;  // 悬浮幕布颜色
    static final int TIME = 3;     // 系统时间文字
    static final int WATCH = 4;    // 计时秒表文字
    static final int TEXT = 5;     // 设置页文字
    static final int EYEBG = 6;    // 护眼休息背景
    static final int EYETEXT = 7;  // 护眼休息文字
    static final int N = 8;

    String id;
    String name;
    boolean builtin;
    final int[] c = new int[N];
    /** 各背景槽位绑定的图片集 id（空字符串＝不用图片）。只有 HOME / CURTAIN / EYEBG 会用到。 */
    final String[] img = {"", "", "", "", "", "", "", ""};

    StyleModel(String id, String name, boolean builtin, int... colors) {
        this.id = id;
        this.name = name;
        this.builtin = builtin;
        System.arraycopy(colors, 0, c, 0, Math.min(N, colors.length));
    }

    String displayName() {
        return builtin ? S.t(name) : name;
    }

    StyleModel copy(String newId, String newName) {
        StyleModel m = new StyleModel(newId, newName, false, c);
        System.arraycopy(img, 0, m.img, 0, N);
        return m;
    }
}
