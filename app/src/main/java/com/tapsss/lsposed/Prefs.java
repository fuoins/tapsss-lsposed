package com.tapsss.lsposed;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 模块配置存储在目标应用（扫雷联萌）私有目录下，Hook 与被 Hook 在同一进程内，
 * 因此直接用普通 SharedPreferences 即可，无需 XSharedPreferences 的跨进程方案。
 */
public final class Prefs {

    private static final String FILE = "tapsss_module";

    public static final String MODULE_ENABLED = "module_enabled";
    public static final String SCHULTE_ENABLED = "schulte_enabled";
    public static final String COUNTDOWN_ENABLED = "schulte_countdown";
    public static final String COUNTDOWN_MS = "schulte_countdown_ms";
    public static final String BLOCK_TOUCH = "schulte_block_touch";
    public static final String MODE_SWITCH = "schulte_mode_switch";
    public static final String AUTO_RELOGIN = "auto_relogin";
    public static final String SAVED_UID = "saved_uid";
    public static final String SAVED_PWD = "saved_pwd";
    public static final String TILE_SEEDED = "module_tile_seeded";

    public static final int MIN_COUNTDOWN_MS = 0;
    public static final int MAX_COUNTDOWN_MS = 60000;

    private static volatile SharedPreferences sp;

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        if (sp == null && c != null) {
            synchronized (Prefs.class) {
                if (sp == null) {
                    sp = c.getApplicationContext()
                            .getSharedPreferences(FILE, Context.MODE_PRIVATE);
                }
            }
        }
        return sp;
    }

    private static boolean bool(Context c, String key, boolean def) {
        SharedPreferences p = get(c);
        return p == null ? def : p.getBoolean(key, def);
    }

    private static int integer(Context c, String key, int def) {
        SharedPreferences p = get(c);
        return p == null ? def : p.getInt(key, def);
    }

    private static String str(Context c, String key) {
        SharedPreferences p = get(c);
        return p == null ? "" : p.getString(key, "");
    }

    public static boolean moduleEnabled(Context c) {
        return bool(c, MODULE_ENABLED, true);
    }

    public static boolean schulteEnabled(Context c) {
        return bool(c, SCHULTE_ENABLED, true);
    }

    public static boolean countdownEnabled(Context c) {
        return bool(c, COUNTDOWN_ENABLED, true);
    }

    /** 倒计时时长，0 表示不等待；上限 60000ms */
    public static long countdownMs(Context c) {
        return clampMs(integer(c, COUNTDOWN_MS, 3000));
    }

    public static boolean blockTouch(Context c) {
        return bool(c, BLOCK_TOUCH, true);
    }

    public static boolean modeSwitch(Context c) {
        return bool(c, MODE_SWITCH, true);
    }

    public static boolean autoRelogin(Context c) {
        return bool(c, AUTO_RELOGIN, true);
    }

    public static String savedUid(Context c) {
        return str(c, SAVED_UID);
    }

    public static String savedPwd(Context c) {
        return str(c, SAVED_PWD);
    }

    /** 是否已经把「模块设置」默认加进快速开始（加过一次就不再强塞） */
    public static boolean tileSeeded(Context c) {
        return bool(c, TILE_SEEDED, false);
    }

    public static void setTileSeeded(Context c, boolean v) {
        setBool(c, TILE_SEEDED, v);
    }

    public static int clampMs(int ms) {
        if (ms < MIN_COUNTDOWN_MS) return MIN_COUNTDOWN_MS;
        if (ms > MAX_COUNTDOWN_MS) return MAX_COUNTDOWN_MS;
        return ms;
    }

    public static void setBool(Context c, String key, boolean value) {
        SharedPreferences p = get(c);
        if (p != null) p.edit().putBoolean(key, value).apply();
    }

    public static void setInt(Context c, String key, int value) {
        SharedPreferences p = get(c);
        if (p != null) p.edit().putInt(key, value).apply();
    }

    public static void saveCredentials(Context c, String uid, String pwd) {
        SharedPreferences p = get(c);
        if (p != null) {
            p.edit().putString(SAVED_UID, uid).putString(SAVED_PWD, pwd).apply();
        }
    }

    public static void clearCredentials(Context c) {
        SharedPreferences p = get(c);
        if (p != null) {
            p.edit().remove(SAVED_UID).remove(SAVED_PWD).apply();
        }
    }
}
