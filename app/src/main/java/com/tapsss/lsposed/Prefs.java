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
    public static final String BLOCK_TOUCH = "schulte_block_touch";
    public static final String AUTO_RELOGIN = "auto_relogin";
    public static final String SAVED_UID = "saved_uid";
    public static final String SAVED_PWD = "saved_pwd";
    public static final String TILE_SEEDED = "module_tile_seeded";
    public static final String ADV_MODE = "schulte_adv_mode";
    public static final String ADV_X = "schulte_adv_x";
    /** max≤N：自定义两数最大间隔（毫秒），0 = 用内置参考值 */
    public static final String ADV_MAX_GAP_MS = "schulte_adv_max_gap_ms";
    /** 1start：自定义开局点 1 的反应上限（毫秒），0 = 用内置 450ms */
    public static final String ADV_START1_MS = "schulte_adv_start1_ms";
    public static final String AUTO_NEXT_NO_RECORD = "schulte_auto_next_no_record";

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

    private static String str(Context c, String key) {
        SharedPreferences p = get(c);
        return p == null ? "" : p.getString(key, "");
    }

    public static boolean moduleEnabled(Context c) {
        return bool(c, MODULE_ENABLED, true);
    }

    public static boolean blockTouch(Context c) {
        return bool(c, BLOCK_TOUCH, true);
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

    public static void setBool(Context c, String key, boolean value) {
        SharedPreferences p = get(c);
        if (p != null) p.edit().putBoolean(key, value).apply();
    }

    /** 高级模式：0=关闭（官方顺序模式）1=爆手速 2=倒序 3=归中 4=max≤N 5=1start */
    public static int advMode(Context c) {
        SharedPreferences p = get(c);
        return p == null ? 0 : p.getInt(ADV_MODE, 0);
    }

    public static void setAdvMode(Context c, int mode) {
        SharedPreferences p = get(c);
        if (p != null) p.edit().putInt(ADV_MODE, mode).apply();
    }

    /** 爆手速目标 x，0 表示取 n² */
    public static int advX(Context c) {
        SharedPreferences p = get(c);
        return p == null ? 0 : p.getInt(ADV_X, 0);
    }

    public static void setAdvX(Context c, int x) {
        SharedPreferences p = get(c);
        if (p != null) p.edit().putInt(ADV_X, x).apply();
    }

    /** max≤N 的自定义间隔（毫秒），0 表示用内置参考表 */
    public static int advMaxGapMs(Context c) {
        SharedPreferences p = get(c);
        return p == null ? 0 : p.getInt(ADV_MAX_GAP_MS, 0);
    }

    public static void setAdvMaxGapMs(Context c, int ms) {
        SharedPreferences p = get(c);
        if (p != null) p.edit().putInt(ADV_MAX_GAP_MS, Math.max(0, ms)).apply();
    }

    /** 1start 的自定义反应上限（毫秒），0 表示用内置 450ms */
    public static int advStart1Ms(Context c) {
        SharedPreferences p = get(c);
        return p == null ? 0 : p.getInt(ADV_START1_MS, 0);
    }

    public static void setAdvStart1Ms(Context c, int ms) {
        SharedPreferences p = get(c);
        if (p != null) p.edit().putInt(ADV_START1_MS, Math.max(0, ms)).apply();
    }

    /** 非新纪录时不展示成绩，直接自动下一把 */
    public static boolean autoNextNoRecord(Context c) {
        return bool(c, AUTO_NEXT_NO_RECORD, true);
    }

    public static long getLong(Context c, String key, long def) {
        SharedPreferences p = get(c);
        return p == null ? def : p.getLong(key, def);
    }

    public static void setLong(Context c, String key, long value) {
        SharedPreferences p = get(c);
        if (p != null) p.edit().putLong(key, value).apply();
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
