package com.wave.screentool;

import java.util.ArrayList;

/** 图片集：只保存图片的链接（content:// 或应用内副本 file://），以及幻灯片放映设置。 */
final class ImgSet {
    String id;
    String name;
    final ArrayList<String> uris = new ArrayList<>();
    int interval = 10;       // 切换间隔（秒）
    boolean random = false;  // 随机顺序
    int fit = 0;             // 0 铺满裁剪  1 完整显示
    boolean fade = true;     // 渐变切换
}
