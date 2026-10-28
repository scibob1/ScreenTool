package com.wave.screentool;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 风格的读取、保存、排序。内置 3 种，自定义风格以 JSON 存在设置里。 */
final class StyleStore {
    private StyleStore() {}

    static StyleModel[] builtins() {
        return new StyleModel[]{
                new StyleModel("black", "Pure black", true,
                        0xFF000000, 0xFF1C1C1C, 0xFF000000, 0xFF7A7A7A, 0xFFB0B0B0, 0xFFFFFFFF, 0xFF000000, 0xFFB0B0B0),
                new StyleModel("white", "Pure white", true,
                        0xFFFFFFFF, 0xFFEFEFEF, 0xFFFFFFFF, 0xFF444444, 0xFF111111, 0xFF000000, 0xFFFFFFFF, 0xFF111111),
                new StyleModel("yellow", "Eye-care yellow", true,
                        0xFFF4E9B8, 0xFFE8D48A, 0xFFF4E9B8, 0xFF6B5A2A, 0xFF4A3B10, 0xFF3B2F0B, 0xFFF4E9B8, 0xFF4A3B10),
        };
    }

    private static List<StyleModel> loadCustom(Context c) {
        ArrayList<StyleModel> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.getStr(c, "styles"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONArray cs = o.getJSONArray("c");
                int[] col = new int[StyleModel.N];
                for (int k = 0; k < StyleModel.N && k < cs.length(); k++) col[k] = cs.getInt(k);
                if (cs.length() <= StyleModel.EYEBG) {   // 旧版本保存的风格：护眼槽位用幕布/秒表颜色补上
                    col[StyleModel.EYEBG] = Sys.opaque(col[StyleModel.CURTAIN]);
                    col[StyleModel.EYETEXT] = col[StyleModel.WATCH];
                }
                StyleModel m = new StyleModel(o.getString("id"), o.optString("name", "Custom"), false, col);
                JSONArray im = o.optJSONArray("img");
                if (im != null) for (int k = 0; k < StyleModel.N && k < im.length(); k++) m.img[k] = im.optString(k, "");
                out.add(m);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveCustom(Context c, List<StyleModel> list) {
        try {
            JSONArray a = new JSONArray();
            for (StyleModel s : list) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                JSONArray cs = new JSONArray();
                for (int k = 0; k < StyleModel.N; k++) cs.put(s.c[k]);
                o.put("c", cs);
                JSONArray im = new JSONArray();
                for (int k = 0; k < StyleModel.N; k++) im.put(s.img[k]);
                o.put("img", im);
                a.put(o);
            }
            Prefs.putStr(c, "styles", a.toString());
        } catch (Exception ignored) {
        }
    }

    /** 按用户排序返回全部风格。 */
    static List<StyleModel> all(Context c) {
        LinkedHashMap<String, StyleModel> map = new LinkedHashMap<>();
        for (StyleModel b : builtins()) map.put(b.id, b);
        for (StyleModel s : loadCustom(c)) map.put(s.id, s);
        ArrayList<StyleModel> out = new ArrayList<>();
        String order = Prefs.getStr(c, "style_order");
        if (order.length() > 0) {
            for (String id : order.split(",")) {
                StyleModel s = map.remove(id);
                if (s != null) out.add(s);
            }
        }
        out.addAll(map.values());
        return out;
    }

    static StyleModel get(Context c, String id) {
        for (StyleModel s : all(c)) if (s.id.equals(id)) return s;
        return null;
    }

    /** 当前风格。内置风格直接返回；自定义风格读 6 个颜色的缓存，不解析整份风格列表。 */
    static StyleModel active(Context c) {
        String id = Prefs.getStr(c, "style_active");
        for (StyleModel b : builtins()) if (b.id.equals(id)) return b;
        String ac = Prefs.getStr(c, "active_colors");
        String ai = Prefs.getStr(c, "active_imgs");
        if (ac.length() > 0) {
            String[] p = ac.split(",");
            String[] q = ai.split(",", -1);
            if (p.length == StyleModel.N && q.length == StyleModel.N) {
                try {
                    int[] col = new int[StyleModel.N];
                    for (int i = 0; i < col.length; i++) col[i] = (int) Long.parseLong(p[i]);
                    StyleModel m = new StyleModel(id, "", false, col);
                    System.arraycopy(q, 0, m.img, 0, StyleModel.N);
                    return m;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        StyleModel s = get(c, id);
        if (s == null) s = builtins()[0];
        cacheActive(c, s);
        return s;
    }

    private static void cacheActive(Context c, StyleModel s) {
        if (s.builtin) {
            Prefs.putStr(c, "active_colors", "");
            Prefs.putStr(c, "active_imgs", "");
            return;
        }
        StringBuilder sb = new StringBuilder(), si = new StringBuilder();
        for (int i = 0; i < StyleModel.N; i++) {
            if (i > 0) {
                sb.append(',');
                si.append(',');
            }
            sb.append(s.c[i]);
            si.append(s.img[i]);
        }
        Prefs.putStr(c, "active_colors", sb.toString());
        Prefs.putStr(c, "active_imgs", si.toString());
    }

    /** 恢复备份后，重新生成当前风格的缓存。 */
    static void syncActive(Context c) {
        Prefs.putStr(c, "active_colors", "");
        Prefs.putStr(c, "active_imgs", "");
        active(c);
    }

    static void setActive(Context c, String id) {
        Prefs.putStr(c, "style_active", id);
        StyleModel s = get(c, id);
        if (s != null) cacheActive(c, s);
    }

    /** 新增或更新一个自定义风格。 */
    static void upsert(Context c, StyleModel s) {
        List<StyleModel> list = loadCustom(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(s.id)) {
                list.set(i, s);
                found = true;
            }
        }
        if (!found) {
            list.add(s);
            String order = Prefs.getStr(c, "style_order");
            if (order.length() > 0) Prefs.putStr(c, "style_order", order + "," + s.id);
        }
        saveCustom(c, list);
        if (s.id.equals(Prefs.getStr(c, "style_active"))) cacheActive(c, s);
    }

    static void delete(Context c, String id) {
        List<StyleModel> list = loadCustom(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        saveCustom(c, list);
        if (id.equals(Prefs.getStr(c, "style_active"))) {
            Prefs.putStr(c, "style_active", "black");
            Prefs.putStr(c, "active_colors", "");
            Prefs.putStr(c, "active_imgs", "");
        }
        String order = Prefs.getStr(c, "style_order");
        StringBuilder sb = new StringBuilder();
        for (String o : order.split(",")) {
            if (o.length() == 0 || o.equals(id)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(o);
        }
        Prefs.putStr(c, "style_order", sb.toString());
    }

    static void reorder(Context c, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        Prefs.putStr(c, "style_order", sb.toString());
    }

    static StyleModel newCopy(Context c, StyleModel base) {
        int n = loadCustom(c).size() + 1;
        return base.copy("c" + System.currentTimeMillis(), S.t("Custom") + " " + n);
    }

    /** 取当前风格用于修改：如果是内置风格，先复制成自定义风格并启用。 */
    static StyleModel editableActive(Context c) {
        StyleModel s = active(c);
        if (!s.builtin) {
            StyleModel full = get(c, s.id);   // 缓存里没有名称，取完整对象
            return full != null ? full : s;
        }
        StyleModel ns = newCopy(c, s);
        upsert(c, ns);
        setActive(c, ns.id);
        return ns;
    }
}
