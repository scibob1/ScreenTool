package com.wave.screentool;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 图片集的读写。只有用到图片背景时才会解析，没有图片时不占资源。 */
final class ImgStore {
    private ImgStore() {}

    static List<ImgSet> all(Context c) {
        ArrayList<ImgSet> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.getStr(c, "imgsets"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                ImgSet s = new ImgSet();
                s.id = o.getString("id");
                s.name = o.optString("name", "Images");
                s.interval = o.optInt("iv", 10);
                s.random = o.optBoolean("rnd", false);
                s.fit = o.optInt("fit", 0);
                s.fade = o.optBoolean("fade", true);
                JSONArray u = o.optJSONArray("uris");
                if (u != null) for (int k = 0; k < u.length(); k++) s.uris.add(u.getString(k));
                out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static ImgSet get(Context c, String id) {
        if (id == null || id.length() == 0) return null;
        for (ImgSet s : all(c)) if (s.id.equals(id)) return s;
        return null;
    }

    private static void saveAll(Context c, List<ImgSet> list) {
        try {
            JSONArray a = new JSONArray();
            for (ImgSet s : list) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("iv", s.interval);
                o.put("rnd", s.random);
                o.put("fit", s.fit);
                o.put("fade", s.fade);
                JSONArray u = new JSONArray();
                for (String x : s.uris) u.put(x);
                o.put("uris", u);
                a.put(o);
            }
            Prefs.putStr(c, "imgsets", a.toString());
        } catch (Exception ignored) {
        }
    }

    static void upsert(Context c, ImgSet s) {
        List<ImgSet> list = all(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(s.id)) {
                list.set(i, s);
                found = true;
            }
        }
        if (!found) list.add(s);
        saveAll(c, list);
    }

    static void delete(Context c, String id) {
        List<ImgSet> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        saveAll(c, list);
    }

    static ImgSet create(Context c, String name) {
        ImgSet s = new ImgSet();
        s.id = "s" + System.currentTimeMillis();
        s.name = name;
        upsert(c, s);
        return s;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "imgs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static String displayName(Context c, String uriStr) {
        try {
            Uri u = Uri.parse(uriStr);
            if ("content".equals(u.getScheme())) {
                Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME},
                        null, null, null);
                if (cur != null) {
                    try {
                        if (cur.moveToFirst()) {
                            String n = cur.getString(0);
                            if (n != null && n.length() > 0) return n;
                        }
                    } finally {
                        cur.close();
                    }
                }
            }
            String p = u.getLastPathSegment();
            if (p != null) return p;
        } catch (Exception ignored) {
        }
        return "image";
    }

    /** 文件名只保留安全字符，防止路径穿越。 */
    static String safeName(String n) {
        if (n == null) n = "image";
        n = n.replace('\\', '/');
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n.length() && sb.length() < 80; k++) {
            char ch = n.charAt(k);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-' || ch == ' '
                    || ch == '(' || ch == ')') sb.append(ch);
            else sb.append('_');
        }
        String r = sb.toString().trim();
        return r.length() == 0 || r.equals(".") || r.equals("..") ? "image" : r;
    }

    /** 无法获得持久授权时，把图片复制一份到应用内。返回 file:// 链接，失败返回 null。 */
    static String copyToLocal(Context c, Uri u) {
        try {
            String name = safeName(displayName(c, u.toString()));
            File f = new File(dir(c), name);
            int n = 1;
            while (f.exists()) {
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : "";
                f = new File(dir(c), base + "_" + (n++) + ext);
            }
            InputStream is = c.getContentResolver().openInputStream(u);
            if (is == null) return null;
            OutputStream os = new FileOutputStream(f);
            try {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            } finally {
                is.close();
                os.close();
            }
            return Uri.fromFile(f).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
