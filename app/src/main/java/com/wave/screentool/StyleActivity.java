package com.wave.screentool;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 风格页：列表模式（点击应用、长按拖动排序、编辑/复制），
 * 编辑模式（改名、改各处颜色、删除）。
 */
public class StyleActivity extends android.app.Activity {
    private String editId;
    private Ui.SwipeBack swipe;
    private Ui.Page ui;
    private int rowH;

    // 拖动排序状态
    private View dragRow;
    private int dragIdx, dragTarget;
    private float dragStartRaw, lastRaw;
    private LinearLayout box;
    private List<StyleModel> items;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S.acquire(this);
        swipe = new Ui.SwipeBack(this);
        editId = getIntent().getStringExtra("edit");
        rowH = Sys.dp(this, 68);
    }

    @Override
    protected void onDestroy() {
        S.release();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (dragRow == null) swipe.feed(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Prefs.putInt(this, "last_screen", 1);
        S.init(this);
        if (editId == null) buildList();
        else buildEdit();
    }

    // ================= 列表模式 =================

    private void buildList() {
        ui = Ui.page(this, S.t("Styles"));
        TextView hint = Ui.tv(this, S.t("Tap to apply  ·  long-press and drag to reorder"), 13, ui.fg2);
        hint.setPadding(Sys.dp(this, 20), Sys.dp(this, 4), Sys.dp(this, 20), Sys.dp(this, 8));
        ui.list.addView(hint);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        ui.list.addView(box, new LinearLayout.LayoutParams(-1, -2));

        items = StyleStore.all(this);
        String activeId = StyleStore.active(this).id;
        for (int i = 0; i < items.size(); i++) {
            box.addView(styleRow(items.get(i), items.get(i).id.equals(activeId)),
                    new LinearLayout.LayoutParams(-1, rowH));
        }

        Button add = new Button(this);
        add.setText(S.t("New style (copy of current)"));
        add.setOnClickListener(v -> {
            StyleModel n = StyleStore.newCopy(this, StyleStore.active(this));
            StyleStore.upsert(this, n);
            openEdit(n.id);
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        int m = Sys.dp(this, 20);
        ap.setMargins(m, m, m, m);
        ui.list.addView(add, ap);
        setContentView(ui.root);
    }

    private View styleRow(final StyleModel s, boolean active) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Sys.dp(this, 20), 0, Sys.dp(this, 12), 0);

        // 预览小方块：背景色 + 秒表文字色
        FrameLayout pv = new FrameLayout(this);
        pv.addView(Ui.swatch(this, Sys.opaque(s.c[StyleModel.HOME]), ui.fg2),
                new FrameLayout.LayoutParams(Sys.dp(this, 48), Sys.dp(this, 36)));
        TextView ab = Ui.tv(this, "12", 14, s.c[StyleModel.WATCH]);
        ab.setGravity(Gravity.CENTER);
        pv.addView(ab, new FrameLayout.LayoutParams(Sys.dp(this, 48), Sys.dp(this, 36)));
        r.addView(pv, new LinearLayout.LayoutParams(Sys.dp(this, 48), Sys.dp(this, 36)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Sys.dp(this, 14), 0, 0, 0);
        col.addView(Ui.tv(this, s.displayName(), 16, ui.fg));
        col.addView(Ui.tv(this, s.builtin ? S.t("Built-in") : S.t("Custom"), 12, ui.fg2));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        if (active) r.addView(Ui.tv(this, "✓", 20, ui.fg));

        TextView act = Ui.tv(this, s.builtin ? S.t("Copy") : S.t("Edit"), 14, ui.fg);
        act.setPadding(Sys.dp(this, 14), Sys.dp(this, 10), Sys.dp(this, 8), Sys.dp(this, 10));
        act.setOnClickListener(v -> {
            if (s.builtin) {
                StyleModel n = StyleStore.newCopy(this, s);
                n.name = s.displayName() + " *";
                StyleStore.upsert(this, n);
                openEdit(n.id);
            } else {
                openEdit(s.id);
            }
        });
        r.addView(act);

        r.setOnClickListener(v -> {
            StyleStore.setActive(this, s.id);
            FloatCtl.refresh(this);
            buildList();
        });
        r.setOnLongClickListener(v -> {
            dragRow = v;
            dragIdx = box.indexOfChild(v);
            dragTarget = dragIdx;
            dragStartRaw = lastRaw;
            v.setAlpha(0.7f);
            ui.sv.requestDisallowInterceptTouchEvent(true);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
        r.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) lastRaw = e.getRawY();
            if (dragRow != v) return false;
            if (a == MotionEvent.ACTION_MOVE) {
                dragMove(e.getRawY());
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                dragEnd();
            }
            return true;
        });
        return r;
    }

    private void dragMove(float rawY) {
        int n = box.getChildCount();
        float visual = dragIdx * rowH + (rawY - dragStartRaw);
        visual = Math.max(0, Math.min((n - 1) * rowH, visual));
        dragRow.setTranslationY(visual - dragIdx * rowH);
        dragTarget = Math.round(visual / rowH);
        for (int i = 0; i < n; i++) {
            if (i == dragIdx) continue;
            float tr = 0;
            if (dragIdx < dragTarget && i > dragIdx && i <= dragTarget) tr = -rowH;
            else if (dragIdx > dragTarget && i >= dragTarget && i < dragIdx) tr = rowH;
            box.getChildAt(i).setTranslationY(tr);
        }
    }

    private void dragEnd() {
        for (int i = 0; i < box.getChildCount(); i++) box.getChildAt(i).setTranslationY(0);
        dragRow.setAlpha(1f);
        if (dragTarget != dragIdx) {
            ArrayList<String> ids = new ArrayList<>();
            for (StyleModel s : items) ids.add(s.id);
            String moved = ids.remove(dragIdx);
            ids.add(dragTarget, moved);
            StyleStore.reorder(this, ids);
        }
        dragRow = null;
        buildList();
    }

    private void openEdit(String id) {
        startActivity(new android.content.Intent(this, StyleActivity.class).putExtra("edit", id));
    }

    // ================= 编辑模式 =================

    private void buildEdit() {
        final StyleModel s = StyleStore.get(this, editId);
        if (s == null || s.builtin) {
            finish();
            return;
        }
        ui = Ui.page(this, S.t("Edit style"));
        View name = Ui.row(ui, S.t("Name"), s.name, null);
        name.setOnClickListener(v -> {
            final EditText et = new EditText(this);
            et.setSingleLine(true);
            et.setText(s.name);
            et.setSelectAllOnFocus(true);
            FrameLayout fl = new FrameLayout(this);
            int pad = Sys.dp(this, 20);
            fl.setPadding(pad, pad / 2, pad, 0);
            fl.addView(et);
            new AlertDialog.Builder(this)
                    .setTitle(S.t("Rename"))
                    .setView(fl)
                    .setPositiveButton(S.t("OK"), (d, w) -> {
                        String t = et.getText().toString().trim();
                        if (t.length() > 0) {
                            s.name = t;
                            StyleStore.upsert(this, s);
                            buildEdit();
                        }
                    })
                    .setNegativeButton(S.t("Cancel"), null)
                    .show();
        });

        Ui.header(ui, S.t("Colors"));
        slotImg(s, S.t("Home background / settings background"), StyleModel.HOME);
        slot(s, S.t("Floating ball"), StyleModel.BALL);
        slotImg(s, S.t("Curtain"), StyleModel.CURTAIN);
        slot(s, S.t("System time text"), StyleModel.TIME);
        slot(s, S.t("Stopwatch text"), StyleModel.WATCH);
        slot(s, S.t("Settings text"), StyleModel.TEXT);
        Ui.header(ui, S.t("Eye rest"));
        slotImg(s, S.t("Eye rest background"), StyleModel.EYEBG);
        slot(s, S.t("Eye rest text"), StyleModel.EYETEXT);

        View use = Ui.row(ui, S.t("Use this style"), null, null);
        use.setOnClickListener(v -> {
            StyleStore.setActive(this, s.id);
            FloatCtl.refresh(this);
            finish();
        });
        View del = Ui.row(ui, S.t("Delete this style"), null, null);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(S.t("Delete this style"))
                .setMessage(s.name)
                .setPositiveButton(S.t("Delete"), (d, w) -> {
                    StyleStore.delete(this, s.id);
                    FloatCtl.refresh(this);
                    finish();
                })
                .setNegativeButton(S.t("Cancel"), null)
                .show());
        setContentView(ui.root);
    }

    /** 背景槽位：颜色 + 右边“图片”按钮（可导入图片作为背景，多张作为幻灯片）。 */
    private void slotImg(final StyleModel s, String title, final int slot) {
        final int cur = s.c[slot];
        ImgSet set = ImgStore.get(this, s.img[slot]);
        String sub = String.format("#%08X", cur);
        if (set != null) sub += "  ·  " + S.t("Image set") + ": " + set.name + " (" + set.uris.size() + ")";
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(Ui.swatch(this, cur, ui.fg2));
        TextView pill = Ui.tv(this, S.t("Image"), 13, ui.fg);
        pill.setPadding(Sys.dp(this, 12), Sys.dp(this, 6), Sys.dp(this, 12), Sys.dp(this, 6));
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(Sys.dp(this, 14));
        d.setStroke(Sys.dp(this, 1), ui.fg2);
        d.setColor(set != null ? (ui.fg2 & 0x00FFFFFF) | 0x33000000 : 0);
        pill.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Sys.dp(this, 10);
        right.addView(pill, lp);
        View r = Ui.row(ui, title, sub, right);
        r.setOnClickListener(v -> ColorPickerDialog.show(this, cur, col -> {
            s.c[slot] = col;
            StyleStore.upsert(this, s);
            if (s.id.equals(StyleStore.active(this).id)) FloatCtl.refresh(this);
            buildEdit();
        }));
        pill.setOnClickListener(v -> startActivity(new android.content.Intent(this, ImageSetActivity.class)
                .putExtra("style", s.id).putExtra("slot", slot)));
    }

    private void slot(final StyleModel s, String title, final int slot) {
        final int cur = s.c[slot];
        View r = Ui.row(ui, title, String.format("#%08X", cur), Ui.swatch(this, cur, ui.fg2));
        r.setOnClickListener(v -> ColorPickerDialog.show(this, cur, col -> {
            s.c[slot] = col;
            StyleStore.upsert(this, s);
            if (s.id.equals(StyleStore.active(this).id)) FloatCtl.refresh(this);
            buildEdit();
        }));
    }
}
