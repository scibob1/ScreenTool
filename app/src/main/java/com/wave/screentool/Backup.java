package com.wave.screentool;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 设置与风格的备份/恢复（通过系统文件选择器读写，无需存储权限）。
 * - 仅设置：一个 .json（图片集只含链接）
 * - 含图片：一个 .zip（settings.json + images/文件名），恢复时同名图片直接覆盖，读不到的图片自动跳过。
 */
final class Backup {
    private Backup() {}

    static void write(Context c, Uri u, boolean withImages) throws Exception {
        LinkedHashMap<String, String> imgs = new LinkedHashMap<>();   // zip 内文件名 -> 原图链接
        JSONObject o = new JSONObject();
        o.put("app", "screentool");
        o.put("version", 2);
        JSONArray a = new JSONArray();
        for (Map.Entry<String, ?> e : Prefs.sp(c).getAll().entrySet()) {
            if (Prefs.isProtectedKey(e.getKey())) continue;   // 隐私配置、上锁状态、授权列表、运行标记永不进入备份
            Object v = e.getValue();
            JSONObject j = new JSONObject();
            j.put("k", e.getKey());
            if (v instanceof Boolean) {
                j.put("t", "b");
                j.put("v", ((Boolean) v).booleanValue());
            } else if (v instanceof Integer) {
                j.put("t", "i");
                j.put("v", ((Integer) v).intValue());
            } else if (v instanceof Long) {
                j.put("t", "l");
                j.put("v", ((Long) v).longValue());
            } else if (v instanceof Float) {
                j.put("t", "f");
                j.put("v", ((Float) v).doubleValue());
            } else if (v instanceof String) {
                j.put("t", "s");
                String sv = (String) v;
                if (withImages && "imgsets".equals(e.getKey())) sv = packSets(c, sv, imgs);
                j.put("v", sv);
            } else {
                continue;
            }
            a.put(j);
        }
        o.put("data", a);
        byte[] json = o.toString(2).getBytes("UTF-8");
        OutputStream os = c.getContentResolver().openOutputStream(u, "wt");
        if (os == null) throw new Exception("cannot open");
        try {
            if (!withImages) {
                os.write(json);
            } else {
                ZipOutputStream zo = new ZipOutputStream(os);
                zo.putNextEntry(new ZipEntry("settings.json"));
                zo.write(json);
                zo.closeEntry();
                for (Map.Entry<String, String> e : imgs.entrySet()) {
                    InputStream is = null;
                    try {
                        is = c.getContentResolver().openInputStream(Uri.parse(e.getValue()));
                        if (is == null) continue;
                        zo.putNextEntry(new ZipEntry("images/" + e.getKey()));
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) zo.write(buf, 0, n);
                        zo.closeEntry();
                    } catch (Exception skip) {
                        // 读不到的图片直接跳过
                    } finally {
                        if (is != null) is.close();
                    }
                }
                zo.finish();
            }
        } finally {
            os.close();
        }
    }

    /** 把图片集里的链接换成 zip 内的文件名；读不到的图片不写入。 */
    private static String packSets(Context c, String json, LinkedHashMap<String, String> imgs) {
        try {
            JSONArray arr = new JSONArray(json);
            HashMap<String, String> byUri = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject set = arr.getJSONObject(i);
                JSONArray src = set.optJSONArray("uris");
                JSONArray dst = new JSONArray();
                if (src != null) {
                    for (int k = 0; k < src.length(); k++) {
                        String uri = src.getString(k);
                        String name = byUri.get(uri);
                        if (name == null) {
                            if (!readable(c, uri)) continue;
                            name = unique(imgs, ImgStore.safeName(ImgStore.displayName(c, uri)));
                            imgs.put(name, uri);
                            byUri.put(uri, name);
                        }
                        dst.put("zip:" + name);
                    }
                }
                set.put("uris", dst);
            }
            return arr.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private static boolean readable(Context c, String uri) {
        try {
            InputStream is = c.getContentResolver().openInputStream(Uri.parse(uri));
            if (is == null) return false;
            is.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String unique(Map<String, String> used, String name) {
        if (!used.containsKey(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int n = 2;
        while (used.containsKey(base + "_" + n + ext)) n++;
        return base + "_" + n + ext;
    }

    /** 恢复：自动识别 .zip 或 .json。 */
    static void read(Context c, Uri u) throws Exception {
        InputStream raw = c.getContentResolver().openInputStream(u);
        if (raw == null) throw new Exception("cannot open");
        BufferedInputStream is = new BufferedInputStream(raw);
        byte[] json = null;
        try {
            is.mark(4);
            int b0 = is.read(), b1 = is.read();
            is.reset();
            if (b0 == 'P' && b1 == 'K') {
                ZipInputStream zi = new ZipInputStream(is);
                ZipEntry e;
                while ((e = zi.getNextEntry()) != null) {
                    String name = e.getName();
                    if ("settings.json".equals(name)) {
                        json = readAll(zi);
                    } else if (name.startsWith("images/") && !e.isDirectory()) {
                        // 同名图片直接覆盖
                        File f = new File(ImgStore.dir(c), ImgStore.safeName(name.substring(7)));
                        FileOutputStream fo = new FileOutputStream(f);
                        try {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = zi.read(buf)) > 0) fo.write(buf, 0, n);
                        } finally {
                            fo.close();
                        }
                    }
                    zi.closeEntry();
                }
            } else {
                json = readAll(is);
            }
        } finally {
            is.close();
        }
        if (json == null) throw new Exception("not a backup");
        JSONObject o = new JSONObject(new String(json, "UTF-8"));
        if (!"screentool".equals(o.optString("app"))) throw new Exception("not a backup");
        JSONArray a = o.getJSONArray("data");
        SharedPreferences.Editor ed = Prefs.sp(c).edit();
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i);
            String k = j.getString("k");
            if (Prefs.isProtectedKey(k)) continue;   // 即使备份文件里混入了这类字段（例如被人改过），恢复时也直接忽略，不能借备份改掉保护设置
            String t = j.getString("t");
            switch (t) {
                case "b": ed.putBoolean(k, j.getBoolean("v")); break;
                case "i": ed.putInt(k, j.getInt("v")); break;
                case "l": ed.putLong(k, j.getLong("v")); break;
                case "f": ed.putFloat(k, (float) j.getDouble("v")); break;
                case "s":
                    String sv = j.getString("v");
                    if ("imgsets".equals(k)) sv = unpackSets(c, sv);
                    ed.putString(k, sv);
                    break;
                default: break;
            }
        }
        ed.commit();
        StyleStore.syncActive(c);
    }

    /** zip:文件名 -> 应用内图片；文件不存在就跳过。 */
    private static String unpackSets(Context c, String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject set = arr.getJSONObject(i);
                JSONArray src = set.optJSONArray("uris");
                JSONArray dst = new JSONArray();
                if (src != null) {
                    for (int k = 0; k < src.length(); k++) {
                        String uri = src.getString(k);
                        if (uri.startsWith("zip:")) {
                            File f = new File(ImgStore.dir(c), ImgStore.safeName(uri.substring(4)));
                            if (f.exists()) dst.put(Uri.fromFile(f).toString());
                        } else {
                            dst.put(uri);
                        }
                    }
                }
                set.put("uris", dst);
            }
            return arr.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
