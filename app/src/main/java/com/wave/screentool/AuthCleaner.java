package com.wave.screentool;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 外部授权文件/文件夹的清除（通过系统文件选择器授权的 SAF 链接）。
 *
 * 1.5.0 重写，修掉旧版的几个问题：
 *  1. 旧版递归删除子文件夹时，用子文件夹链接去取“树根 ID”，结果又列出根目录，子文件夹既删不掉又会无限递归。
 *     现在每一层都用自己的文档 ID，并且用循环代替递归，也限制了总数量，不会栈溢出。
 *  2. 旧版“无法访问”也被当成“已清除”，权限失效时会误以为成功，随后照样清除软件数据。
 *     现在只有“再次读取确认已经没有了”才算成功；权限失效、读取失败都算失败。
 *  3. 旧版只清空文件夹内容。现在清空后会再确认一次，并在系统允许时把文件夹本身也删掉。
 *  4. 在主线程上做耗时的删除会卡死界面；现在要求在后台线程调用，并带有重试。
 */
final class AuthCleaner {
    private AuthCleaner() {}

    static final class Result {
        boolean ok = true;
        int items, cleaned, failed;
        final ArrayList<String> failures = new ArrayList<>();
    }

    private static final int MAX_ENTRIES = 200_000;

    // ---------- 权限校验（非破坏性） ----------

    static boolean hasPersisted(Context c, Uri u) {
        try {
            for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static boolean canAccess(Context c, Uri u) {
        ContentResolver cr = c.getContentResolver();
        try {
            if (DocumentsContract.isTreeUri(u)) {
                Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(u, DocumentsContract.getTreeDocumentId(u));
                try (Cursor cur = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                    return cur != null && hasPersisted(c, u);
                }
            }
            try (InputStream in = cr.openInputStream(u)) {
                if (in == null) return false;
                in.read();
            }
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "rw")) {
                return pfd != null && hasPersisted(c, u);
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 清除 ----------

    /** 清除全部授权项，最多尝试 3 轮。必须在后台线程调用。 */
    static Result cleanAll(Context c) {
        List<String> uris = Prefs.authUris(c);
        Result r = new Result();
        r.items = uris.size();
        HashSet<String> done = new HashSet<>();
        for (int round = 0; round < 3; round++) {
            for (String s : uris) {
                if (done.contains(s)) continue;
                boolean ok;
                try {
                    ok = cleanOne(c, Uri.parse(s));
                } catch (Throwable t) {
                    ok = false;
                }
                if (ok) done.add(s);
            }
            if (done.size() == uris.size()) break;
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                break;
            }
        }
        for (String s : uris) {
            if (done.contains(s)) r.cleaned++;
            else {
                r.failed++;
                r.failures.add(s);
            }
        }
        r.ok = r.failed == 0;
        return r;
    }

    private static boolean cleanOne(Context c, Uri u) {
        if (!hasPersisted(c, u)) return false;      // 权限已失效：不能当作成功
        if (DocumentsContract.isTreeUri(u)) return cleanTree(c, u);
        return cleanFile(c, u);
    }

    private static boolean cleanFile(Context c, Uri u) {
        int ex = existence(c, u);
        if (ex == 0) return true;
        if (ex < 0) return false;
        deleteUri(c, u);
        return existence(c, u) == 0;
    }

    /** 1 存在，0 确认已不存在，-1 无法判断（没权限/读取失败）。 */
    private static int existence(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cur == null) return -1;
            return cur.moveToFirst() ? 1 : 0;
        } catch (SecurityException e) {
            return -1;
        } catch (Exception e) {
            return hasPersisted(c, u) ? 0 : -1;   // 权限还在、提供者却说找不到：确认已经没有了
        }
    }

    private static boolean deleteUri(Context c, Uri u) {
        ContentResolver cr = c.getContentResolver();
        try {
            if (DocumentsContract.deleteDocument(cr, u)) return true;
        } catch (Exception ignored) {
        }
        try {
            return cr.delete(u, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class Kid {
        final String id;
        final boolean dir;

        Kid(String id, boolean dir) {
            this.id = id;
            this.dir = dir;
        }
    }

    private static ArrayList<Kid> list(Context c, Uri tree, String docId) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        ArrayList<Kid> out = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                out.add(new Kid(cur.getString(0), DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(1))));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean cleanTree(Context c, Uri tree) {
        String root;
        try {
            root = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            return false;
        }
        // 先收集所有子文件夹（广度遍历），遍历过程中顺手删文件
        ArrayDeque<String> queue = new ArrayDeque<>();
        ArrayList<String> dirs = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        queue.add(root);
        seen.add(root);
        int count = 0;
        while (!queue.isEmpty()) {
            String dir = queue.poll();
            ArrayList<Kid> kids = list(c, tree, dir);
            if (kids == null) return false;
            for (Kid k : kids) {
                if (++count > MAX_ENTRIES) return false;
                if (k.dir) {
                    if (seen.add(k.id)) {
                        queue.add(k.id);
                        dirs.add(k.id);
                    }
                } else {
                    deleteUri(c, DocumentsContract.buildDocumentUriUsingTree(tree, k.id));
                }
            }
        }
        // 子文件夹从最深的开始删（发现顺序的倒序）
        for (int i = dirs.size() - 1; i >= 0; i--) {
            deleteUri(c, DocumentsContract.buildDocumentUriUsingTree(tree, dirs.get(i)));
        }
        // 再列一次：必须真的空了才算成功
        ArrayList<Kid> left = list(c, tree, root);
        if (left == null || !left.isEmpty()) return false;
        // 系统允许时把文件夹本身也删掉；被拒绝（例如存储根目录）不算失败
        deleteUri(c, DocumentsContract.buildDocumentUriUsingTree(tree, root));
        return true;
    }
}
