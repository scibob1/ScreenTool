package com.wave.screentool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 背景图片 / 幻灯片管理页：选择或新建图片集、多选添加图片（只保存链接）、
 * 图库里删减图片、放映设置（间隔/顺序/填充方式/渐变）、小窗预览。用到才创建。
 */
public class ImageSetActivity extends Activity {
    private interface IntCb {
        void on(int v);
    }

    private static final int REQ_PICK = 11;
    private String styleId;
    private int slot;
    private Ui.Page ui;
    private Ui.SwipeBack swipe;
    private SlideShow show;
    private BgView preview;
    private boolean destroyed;
    // 缩略图：全局小缓存 + 单线程解码（图库很大时也不会同时起一堆线程）
    private static final android.util.LruCache<String, Bitmap> THUMBS = new android.util.LruCache<>(48);
    private java.util.concurrent.ExecutorService pool;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S.acquire(this);
        swipe = new Ui.SwipeBack(this);
        pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        styleId = getIntent().getStringExtra("style");
        slot = getIntent().getIntExtra("slot", StyleModel.HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Prefs.putInt(this, "last_screen", 1);
        S.init(this);
        build();
    }

    @Override
    protected void onPause() {
        stopShow();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        pool.shutdownNow();
        S.release();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        swipe.feed(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void stopShow() {
        if (show != null) {
            show.stop();
            show = null;
        }
    }

    private String slotTitle() {
        switch (slot) {
            case StyleModel.CURTAIN: return S.t("Curtain background");
            case StyleModel.EYEBG: return S.t("Eye rest background");
            default: return S.t("Home background");
        }
    }

    private void build() {
        stopShow();
        final StyleModel s = StyleStore.get(this, styleId);
        if (s == null || s.builtin) {
            finish();
            return;
        }
        ui = Ui.page(this, slotTitle());
        final ImgSet set = ImgStore.get(this, s.img[slot]);

        // ---- 小窗预览 ----
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(0, Sys.dp(this, 8), 0, Sys.dp(this, 4));
        int[] sc = Sys.screen(this);
        int ph = Sys.dp(this, 220);
        int pw = Math.round(ph * (float) sc[0] / sc[1]);
        preview = new BgView(this);
        preview.setBase(Sys.opaque(s.c[slot]));
        wrap.addView(preview, new LinearLayout.LayoutParams(pw, ph));
        ui.list.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = Ui.tv(this, S.t("Preview - tap for the next image"), 12, ui.fg2);
        hint.setGravity(Gravity.CENTER);
        ui.list.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        if (set != null && !set.uris.isEmpty()) {
            show = new SlideShow(this, set, pw, ph, (b, f, fit) -> preview.show(b, f, fit));
            show.start();
            preview.setOnClickListener(v -> {
                if (show != null) show.next();
            });
        }

        // ---- 图片集 ----
        Ui.header(ui, S.t("Image set"));
        String sub = set == null ? S.t("None - the color is used")
                : set.name + "  ·  " + set.uris.size() + " " + S.t("images");
        Ui.row(ui, S.t("Image set"), sub, null).setOnClickListener(v -> chooseSet(s));
        Ui.row(ui, S.t("Add images"),
                S.t("Pick one or more pictures. Only links are saved, no storage permission is needed."), null)
                .setOnClickListener(v -> pick());

        if (set != null) {
            // ---- 图库 ----
            Ui.header(ui, S.t("Gallery"));
            if (set.uris.isEmpty()) {
                Ui.row(ui, S.t("No images yet"), S.t("Tap \"Add images\" above"), null);
            }
            for (int i = 0; i < set.uris.size(); i++) galleryRow(set, i);

            // ---- 放映设置 ----
            Ui.header(ui, S.t("Slideshow"));
            numRow(S.t("Switch every"), set.interval, 5, 3600, " s", v -> {
                set.interval = v;
                save(set);
            });
            choiceRow(S.t("Order"), new String[]{S.t("In order"), S.t("Random")}, set.random ? 1 : 0, v -> {
                set.random = v == 1;
                save(set);
            });
            choiceRow(S.t("Picture size"), new String[]{S.t("Fill the screen (crop)"), S.t("Show the whole picture")},
                    set.fit, v -> {
                        set.fit = v;
                        save(set);
                    });
            final Switch fs = new Switch(this);
            fs.setChecked(set.fade);
            fs.setOnCheckedChangeListener((bv, on) -> {
                set.fade = on;
                ImgStore.upsert(this, set);
                FloatCtl.refresh(this);
            });
            Ui.row(ui, S.t("Fade transition"), null, fs).setOnClickListener(v -> fs.toggle());

            // ---- 图片集管理 ----
            Ui.header(ui, S.t("Manage"));
            Ui.row(ui, S.t("Rename image set"), set.name, null).setOnClickListener(v -> renameSet(set));
            Ui.row(ui, S.t("Use the color instead"), S.t("Keeps the image set, just stops using it here"), null)
                    .setOnClickListener(v -> {
                        s.img[slot] = "";
                        StyleStore.upsert(this, s);
                        FloatCtl.refresh(this);
                        build();
                    });
            Ui.row(ui, S.t("Delete image set"), S.t("Only removes the links; your pictures are untouched"), null)
                    .setOnClickListener(v -> new AlertDialog.Builder(this)
                            .setTitle(S.t("Delete image set"))
                            .setMessage(set.name)
                            .setPositiveButton(S.t("Delete"), (d, w) -> {
                                ImgStore.delete(this, set.id);
                                s.img[slot] = "";
                                StyleStore.upsert(this, s);
                                FloatCtl.refresh(this);
                                build();
                            })
                            .setNegativeButton(S.t("Cancel"), null)
                            .show());
        }
        setContentView(ui.root);
    }

    private void save(ImgSet set) {
        ImgStore.upsert(this, set);
        FloatCtl.refresh(this);
        build();
    }

    private void galleryRow(final ImgSet set, final int i) {
        final String uri = set.uris.get(i);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0x33888888);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(iv, new LinearLayout.LayoutParams(Sys.dp(this, 56), Sys.dp(this, 56)));
        TextView del = Ui.tv(this, "✕", 18, ui.fg);
        del.setPadding(Sys.dp(this, 14), Sys.dp(this, 8), Sys.dp(this, 4), Sys.dp(this, 8));
        del.setOnClickListener(v -> {
            set.uris.remove(i);
            save(set);
        });
        right.addView(del);
        final View row = Ui.row(ui, ImgStore.displayName(this, uri), "…", right);
        final TextView subTv = (TextView) ((LinearLayout) ((LinearLayout) row).getChildAt(0)).getChildAt(1);
        // 缩略图：命中缓存直接显示，否则交给单线程池解码；找不到的图片会标出来（放映时自动跳过）
        final String kind = uri.startsWith("file:") ? S.t("Copy inside the app") : S.t("Link");
        Bitmap hit = THUMBS.get(uri);
        if (hit != null) {
            iv.setImageBitmap(hit);
            subTv.setText(kind);
            return;
        }
        pool.execute(() -> {
            final Bitmap b = SlideShow.decode(this, uri, 160, 160);
            if (b != null) THUMBS.put(uri, b);
            runOnUiThread(() -> {
                if (destroyed) return;
                if (b != null) {
                    iv.setImageBitmap(b);
                    subTv.setText(kind);
                } else {
                    subTv.setText(S.t("Missing - will be skipped"));
                }
            });
        });
    }

    // ---- 选择 / 新建图片集 ----
    private void chooseSet(final StyleModel s) {
        final List<ImgSet> all = ImgStore.all(this);
        String[] items = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) {
            items[i] = all.get(i).name + " (" + all.get(i).uris.size() + ")";
        }
        items[all.size()] = S.t("+ New image set");
        new AlertDialog.Builder(this)
                .setTitle(S.t("Image set"))
                .setItems(items, (d, w) -> {
                    if (w == all.size()) {
                        ImgSet n = ImgStore.create(this, S.t("Images") + " " + (all.size() + 1));
                        s.img[slot] = n.id;
                    } else {
                        s.img[slot] = all.get(w).id;
                    }
                    StyleStore.upsert(this, s);
                    FloatCtl.refresh(this);
                    build();
                })
                .setNegativeButton(S.t("Cancel"), null)
                .show();
    }

    private void renameSet(final ImgSet set) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(set.name);
        et.setSelectAllOnFocus(true);
        FrameLayout fl = new FrameLayout(this);
        int pad = Sys.dp(this, 20);
        fl.setPadding(pad, pad / 2, pad, 0);
        fl.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(S.t("Rename image set"))
                .setView(fl)
                .setPositiveButton(S.t("OK"), (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.length() > 0) {
                        set.name = t;
                        save(set);
                    }
                })
                .setNegativeButton(S.t("Cancel"), null)
                .show();
    }

    // ---- 多选添加图片（系统文件选择器，只存链接） ----
    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, S.t("No file manager available"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        ArrayList<Uri> picked = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) picked.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.isEmpty()) return;
        StyleModel s = StyleStore.get(this, styleId);
        if (s == null) return;
        ImgSet set = ImgStore.get(this, s.img[slot]);
        if (set == null) {   // 还没有图片集：自动新建一个并绑定到这个背景
            set = ImgStore.create(this, S.t("Images") + " " + (ImgStore.all(this).size() + 1));
            s.img[slot] = set.id;
            StyleStore.upsert(this, s);
        }
        int added = 0, copied = 0;
        for (Uri u : picked) {
            String key = u.toString();
            if (set.uris.contains(key)) continue;
            boolean linked = false;
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                linked = true;
            } catch (Exception ignored) {
            }
            if (linked) {
                set.uris.add(key);
                added++;
            } else {
                // 有的系统文件选择器不给长期授权：这种图片复制一份到应用内，避免重启后失效
                String local = ImgStore.copyToLocal(this, u);
                if (local != null) {
                    set.uris.add(local);
                    added++;
                    copied++;
                }
            }
        }
        ImgStore.upsert(this, set);
        FloatCtl.refresh(this);
        String msg = S.t("Added") + " " + added;
        if (copied > 0) msg += "  (" + copied + " " + S.t("copied into the app") + ")";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        build();
    }

    // ---- 小型行控件（不依赖全局设置项） ----
    private void numRow(final String title, final int cur, final int min, final int max, final String unit,
                        final IntCb cb) {
        Ui.row(ui, title, cur + unit, null).setOnClickListener(v -> {
            final EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(cur));
            et.setSelectAllOnFocus(true);
            FrameLayout box = new FrameLayout(this);
            int pad = Sys.dp(this, 20);
            box.setPadding(pad, pad / 2, pad, 0);
            box.addView(et);
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(min + " – " + max + unit)
                    .setView(box)
                    .setPositiveButton(S.t("OK"), (d, w) -> {
                        try {
                            int val = Integer.parseInt(et.getText().toString().trim());
                            cb.on(Math.max(min, Math.min(max, val)));
                        } catch (NumberFormatException ignored) {
                        }
                    })
                    .setNegativeButton(S.t("Cancel"), null)
                    .show();
        });
    }

    private void choiceRow(final String title, final String[] labels, final int cur, final IntCb cb) {
        Ui.row(ui, title, labels[cur], null).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, cur, (d, w) -> {
                    d.dismiss();
                    cb.on(w);
                }).show());
    }
}
