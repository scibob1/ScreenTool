package com.wave.screentool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.util.Random;

/**
 * 幻灯片放映：按间隔解码并切换图片。解码在后台线程进行；找不到的图片自动跳过。
 * 只在 start() 之后、stop() 之前才有定时器，图片只有 1 张时不会有定时器。
 */
final class SlideShow {
    interface Target {
        void onImage(Bitmap b, boolean fade, int fit);
    }

    private final Context c;
    private final ImgSet set;
    private final int w, h;
    private final Target target;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private int idx = -1;
    private int gen;
    private boolean running;

    private final Runnable tick = this::next;

    SlideShow(Context c, ImgSet set, int w, int h, Target t) {
        this.c = c.getApplicationContext();
        this.set = set;
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        this.target = t;
    }

    void start() {
        running = true;
        gen++;
        next();
    }

    void stop() {
        running = false;
        gen++;
        handler.removeCallbacks(tick);
    }

    /** 立刻切到下一张（预览里点按也走这里）。 */
    void next() {
        handler.removeCallbacks(tick);
        if (!running || set.uris.isEmpty()) return;
        final int g = ++gen;
        final int n = set.uris.size();
        final int startIdx = idx;
        new Thread(() -> {
            Bitmap b = null;
            int use = startIdx;
            for (int tries = 0; tries < n && b == null; tries++) {
                if (set.random && n > 1) {
                    int r = rnd.nextInt(n);
                    if (r == use) r = (r + 1) % n;
                    use = r;
                } else {
                    use = (use + 1) % n;
                }
                b = decode(c, set.uris.get(use), w, h);
            }
            final Bitmap res = b;
            final int fin = use;
            handler.post(() -> {
                if (!running || g != gen) return;
                idx = fin;
                if (res != null) target.onImage(res, set.fade, set.fit);
                if (res != null && n > 1) {
                    handler.postDelayed(tick, Math.max(5, set.interval) * 1000L);
                }
            });
        }).start();
    }

    /** 按目标尺寸缩小解码，节省内存；找不到或损坏时返回 null。 */
    static Bitmap decode(Context c, String uriStr, int w, int h) {
        try {
            Uri u = Uri.parse(uriStr);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream is = c.getContentResolver().openInputStream(u);
            if (is == null) return null;
            try {
                BitmapFactory.decodeStream(is, null, o);
            } finally {
                is.close();
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            is = c.getContentResolver().openInputStream(u);
            if (is == null) return null;
            Bitmap b;
            try {
                b = BitmapFactory.decodeStream(is, null, o);
            } finally {
                is.close();
            }
            if (b == null) return null;
            int rot = rotation(c, u);
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    private static int rotation(Context c, Uri u) {
        if (Build.VERSION.SDK_INT < 24) return 0;
        try {
            InputStream is = c.getContentResolver().openInputStream(u);
            if (is == null) return 0;
            try {
                android.media.ExifInterface ex = new android.media.ExifInterface(is);
                switch (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    case 6: return 90;
                    case 3: return 180;
                    case 8: return 270;
                    default: return 0;
                }
            } finally {
                is.close();
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
