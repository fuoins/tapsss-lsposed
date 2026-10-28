package com.tapsss.lsposed;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.Toast;

/** 与 UI 相关的小工具。所有弹窗/Toast 都保证在主线程执行。 */
public final class Ui {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {
    }

    public static void post(Runnable r) {
        MAIN.post(r);
    }

    public static void postDelayed(Runnable r, long delay) {
        MAIN.postDelayed(r, delay);
    }

    public static void toast(Context c, String msg) {
        if (c == null || msg == null) return;
        final Context app = c.getApplicationContext();
        MAIN.post(() -> {
            try {
                Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    public static int dp(Context c, float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics());
    }

    public static boolean isAlive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    public static Activity findActivity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** 通过资源名查 id（资源名在 3.6.x 中未被混淆） */
    public static int resId(Context c, String name, String type) {
        try {
            return c.getResources().getIdentifier(name, type, c.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String resName(Context c, int id) {
        try {
            return c.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }
}
