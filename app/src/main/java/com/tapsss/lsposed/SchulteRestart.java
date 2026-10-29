package com.tapsss.lsposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 舒尔特「重开」设置页：
 * 1) 顶部把官方右上角的模式选择换成两排滑栏标签（第一排官方模式，第二排高级模式）；
 * 2) 高级模式的重开配置直接复用官方界面（开关 + 3~10 阶错误数），
 *    通过改写官方 KV 的 key，把高级模式的数据存到 adv<mode>_<level>，与顺序模式互不干扰；
 * 3) 顶部保留「重开预设」入口，一条预设即可批量写好所有模式 × 所有阶数。
 */
public final class SchulteRestart {

    private static final String RESTART_SETTING =
            "com.star.minesweeping.ui.activity.setting.game.SettingSchulteRestartActivity";
    /** 配置 KV：i = 错误自动重开开关（key = type+blind），j = 错误数阈值（key = type+blind+level） */
    private static final String SCHULTE_SETTING_KV = "com.star.minesweeping.i.f.j";
    private static final String KV_DATA = "com.star.minesweeping.data.bean.keyvalue.KVData";

    private static final String TAG_PRESET_BOX = "tapsss_adv_restart_preset";
    private static final String TAG_TAB_BOX = "tapsss_adv_restart_tabs";

    /** 第一排：官方模式（type + blind），与 App 的 a/b 字段对应 */
    private static final String[] OFFICIAL_LABELS =
            {"简单", "简单盲玩", "普通", "盲玩", "简单打乱", "打乱"};
    private static final int[] OFFICIAL_TYPE = {0, 0, 1, 1, 2, 3};
    private static final boolean[] OFFICIAL_BLIND = {false, true, false, true, false, false};

    /** 当前页面选中的高级模式（0 = 顺序，不覆盖官方 key）；选中时官方 KV 读写被重写到 adv<mode>_ */
    private static volatile int overrideAdv;
    /** 当前页面官方模式的 KV 前缀（如 2true），用于判断哪些 key 需要改写 */
    private static volatile String overrideBase;

    private static volatile boolean hooked;
    private static ClassLoader loader;
    private static Class<?> kvCls;
    private static Object errEnable;
    private static Object errCount;

    private SchulteRestart() {
    }

    static void install(ClassLoader cl) {
        if (hooked) return;
        try {
            loader = cl;
            Class<?> cls = XposedHelpers.findClassIfExists(RESTART_SETTING, cl);
            if (cls == null) return;
            XposedBridge.hookAllMethods(cls, "init", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        decorate((Activity) param.thisObject);
                    } catch (Throwable t) {
                        MainHook.log("重开页预设入口失败: " + t);
                    }
                }
            });
            // 离开页面立刻恢复官方 key，避免影响游戏里读配置
            XposedBridge.hookAllMethods(cls, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    overrideAdv = 0;
                    overrideBase = null;
                }
            });
            // 官方 KV 的 key 改写：选高级模式时，页面上的开关 / 阶数条目读写的是
            // adv<mode>_<suffix>，界面上完全还是官方那套控件
            try {
                Class<?> kv = XposedHelpers.findClass(KV_DATA, cl);
                XposedBridge.hookAllMethods(kv, "getValue", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        rewriteKey(param);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 兜底：adv key 还没有值（从没进过该高级模式）时返回默认值，
                        // 否则官方页面把 null 强转 Boolean 会直接崩掉（表现为返回上一页）
                        if (param.getResult() != null || param.args.length == 0
                                || !(param.args[0] instanceof String)) return;
                        String key = (String) param.args[0];
                        if (!key.startsWith("adv")) return;
                        Object def = null;
                        try {
                            def = XposedHelpers.getObjectField(param.thisObject, "defaultValue");
                        } catch (Throwable ignored) {
                        }
                        if (!(def instanceof Boolean) && !(def instanceof Integer)) {
                            def = key.endsWith("_") ? Boolean.FALSE : Integer.valueOf(3);
                        }
                        param.setResult(def);
                    }
                });
                XposedBridge.hookAllMethods(kv, "setValue", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        rewriteKey(param);
                    }
                });
            } catch (Throwable t) {
                MainHook.log("重开页 KV 改写 Hook 失败: " + t);
            }
            hooked = true;
        } catch (Throwable t) {
            MainHook.log("重开页 Hook 失败: " + t);
        }
    }

    // ------------------------------------------------------------------ KV 改写

    private static void rewriteKey(XC_MethodHook.MethodHookParam param) {
        int adv = overrideAdv;
        String base = overrideBase;
        if (adv <= 0 || base == null || param.args.length == 0) return;
        if (!(param.args[0] instanceof String)) return;
        if (!ensureKv()) return;
        Object self = param.thisObject;
        if (self != errEnable && self != errCount) return;
        String key = (String) param.args[0];
        if (!key.startsWith(base)) return;
        param.args[0] = "adv" + adv + "_" + key.substring(base.length());
    }

    private static boolean ensureKv() {
        if (errEnable != null && errCount != null) return true;
        if (loader == null) return false;
        try {
            if (kvCls == null) kvCls = XposedHelpers.findClass(SCHULTE_SETTING_KV, loader);
            errEnable = XposedHelpers.getStaticObjectField(kvCls, "i");
            errCount = XposedHelpers.getStaticObjectField(kvCls, "j");
            return errEnable != null && errCount != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 高级模式重开：是否开启（adv<mode>_ 的开关） */
    static boolean advErrEnabled(int mode) {
        ensureKv();
        if (errEnable == null) return false;
        try {
            Object v = XposedHelpers.callMethod(errEnable, "getValue", "adv" + mode + "_");
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 高级模式重开：该阶数下的错误数阈值 */
    static int advErrLimit(int mode, int n) {
        ensureKv();
        if (errCount == null) return 1;
        try {
            Object v = XposedHelpers.callMethod(errCount, "getValue", "adv" + mode + "_" + n);
            return v instanceof Integer ? Math.max(1, (Integer) v) : 1;
        } catch (Throwable t) {
            return 1;
        }
    }

    // ------------------------------------------------------------------ 顶部装饰

    private static void decorate(Activity act) {
        if (!Ui.isAlive(act)) return;
        ViewGroup content = scrollContent(act);
        if (content == null) return;

        // 右上角的官方模式选择（点击弹 popup 的 TextView）隐藏，改用下面两排标签
        try {
            hideModePicker(act);
        } catch (Throwable ignored) {
        }

        if (content.findViewWithTag(TAG_TAB_BOX) == null) {
            View tabs = buildTabs(act);
            content.addView(tabs, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (content.findViewWithTag(TAG_PRESET_BOX) != null) return;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(TAG_PRESET_BOX);

        TextView title = new TextView(act);
        title.setText("一键配置预设");
        title.setTextSize(11);
        title.setAlpha(0.55f);
        title.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), 0, Ui.dp(act, 2));
        box.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 32));
        rowLp.setMargins(Ui.dp(act, 6), Ui.dp(act, 2), Ui.dp(act, 6), Ui.dp(act, 4));

        TextView preset = presetButton(act, "重开预设");
        preset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPresets(act);
            }
        });
        row.addView(preset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(row, rowLp);

        content.addView(box, Math.min(1, content.getChildCount()),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ 两排标签

    private static final class TabUi {
        LinearLayout box;
        ViewGroup officialRow;
        ViewGroup advRow;
        int adv;
    }

    private static View buildTabs(final Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(TAG_TAB_BOX);
        box.setBackgroundColor(color(act, "foreground", 0xFFFFFFFF));

        final TabUi ui = new TabUi();
        ui.box = box;

        int type = 0;
        boolean blind = false;
        try {
            type = XposedHelpers.getIntField(act, "a");
            blind = XposedHelpers.getBooleanField(act, "b");
        } catch (Throwable ignored) {
        }

        View official = AdvancedPages.chipRow(act, OFFICIAL_LABELS, officialIndex(type, blind),
                new AdvancedPages.Pick() {
                    @Override
                    public void onPick(int index) {
                        pickOfficial(act, ui, index);
                    }
                });
        ui.officialRow = rowOf(official);
        box.addView(official, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 38)));

        View advRow = AdvancedPages.chipRow(act, AdvancedSchulte.SHORT, ui.adv,
                new AdvancedPages.Pick() {
                    @Override
                    public void onPick(int index) {
                        pickAdv(act, ui, index);
                    }
                });
        ui.advRow = rowOf(advRow);
        box.addView(advRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 38)));

        return box;
    }

    private static void pickOfficial(Activity act, TabUi ui, int index) {
        try {
            XposedHelpers.setIntField(act, "a", OFFICIAL_TYPE[index]);
            XposedHelpers.setBooleanField(act, "b", OFFICIAL_BLIND[index]);
            XposedHelpers.callMethod(act, "w", OFFICIAL_TYPE[index], OFFICIAL_BLIND[index]);
        } catch (Throwable t) {
            MainHook.log("重开页切换官方模式失败: " + t);
        }
        AdvancedPages.applyChipState(ui.officialRow, index);
        if (overrideAdv > 0) {
            overrideBase = base(act);
            refreshOfficial(act);
        }
    }

    private static void pickAdv(Activity act, TabUi ui, int adv) {
        ui.adv = adv;
        AdvancedPages.applyChipState(ui.advRow, adv);
        if (adv > 0) ensureAdvInit(act, adv);
        overrideAdv = adv;
        overrideBase = adv > 0 ? base(act) : null;
        refreshOfficial(act);
    }

    /** 高级模式的 adv key 首次使用前写一遍默认值，避免官方页面读到 null */
    private static void ensureAdvInit(Activity act, int adv) {
        if (adv <= 0 || Prefs.advInitDone(act, adv)) return;
        try {
            if (!ensureKv()) return;
            Object defE = XposedHelpers.getObjectField(errEnable, "defaultValue");
            Object defC = XposedHelpers.getObjectField(errCount, "defaultValue");
            Object enable = defE instanceof Boolean ? defE : Boolean.FALSE;
            Object count = defC instanceof Integer ? defC : Integer.valueOf(3);
            XposedHelpers.callMethod(errEnable, "setValue", "adv" + adv + "_", enable);
            for (int level = 3; level <= 10; level++) {
                XposedHelpers.callMethod(errCount, "setValue", "adv" + adv + "_" + level, count);
            }
            Prefs.setAdvInitDone(act, adv);
        } catch (Throwable t) {
            MainHook.log("高级模式重开初始化失败: " + t);
        }
    }

    private static String base(Activity act) {
        try {
            return XposedHelpers.getIntField(act, "a")
                    + String.valueOf(XposedHelpers.getBooleanField(act, "b"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 让官方控件按当前 key（顺序 = 官方 key，高级模式 = adv<mode>_）重读一遍 */
    private static void refreshOfficial(Activity act) {
        if (!ensureKv()) return;
        try {
            int type = XposedHelpers.getIntField(act, "a");
            boolean blind = XposedHelpers.getBooleanField(act, "b");
            for (String m : new String[]{"r0", "s0"}) {
                try {
                    XposedHelpers.callMethod(act, m);
                } catch (Throwable ignored) {
                }
            }
            XposedHelpers.callMethod(act, "w", type, blind);
            try {
                Object binding = XposedHelpers.getObjectField(act, "view");
                Object sw = XposedHelpers.getObjectField(binding, "Z");
                Object v = XposedHelpers.callMethod(errEnable, "getValue",
                        overrideAdv > 0 ? "adv" + overrideAdv + "_" : base(act));
                if (sw != null && v instanceof Boolean) {
                    XposedHelpers.callMethod(sw, "setChecked", v);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            MainHook.log("重开页刷新失败: " + t);
        }
    }

    private static int officialIndex(int type, boolean blind) {
        for (int i = 0; i < OFFICIAL_TYPE.length; i++) {
            if (OFFICIAL_TYPE[i] == type && OFFICIAL_BLIND[i] == blind) return i;
        }
        return 0;
    }

    private static ViewGroup rowOf(View chipRow) {
        if (chipRow instanceof ViewGroup && ((ViewGroup) chipRow).getChildCount() > 0
                && ((ViewGroup) chipRow).getChildAt(0) instanceof ViewGroup) {
            return (ViewGroup) ((ViewGroup) chipRow).getChildAt(0);
        }
        return null;
    }

    private static void hideModePicker(Activity act) {
        View bar = findById(act, "actionBar");
        if (bar instanceof ViewGroup) hideModeName((ViewGroup) bar);
    }

    private static void hideModeName(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                if (tv.isClickable() && isModeName(String.valueOf(tv.getText()))) {
                    tv.setVisibility(View.GONE);
                }
            } else if (v instanceof ViewGroup) {
                hideModeName((ViewGroup) v);
            }
        }
    }

    private static boolean isModeName(String s) {
        if (s == null || s.length() == 0) return false;
        for (String name : OFFICIAL_LABELS) {
            if (s.startsWith(name)) return true;
        }
        return false;
    }

    private static View findById(Activity act, String name) {
        try {
            int id = Ui.resId(act, name, "id");
            return id == 0 ? null : act.findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 预设

    /** 预设列表：官方顺序模式一条 + 每个高级模式各一条 + 全关 */
    private static void showPresets(final Activity act) {
        try {
            final String[] items = new String[AdvancedSchulte.SHORT.length + 1];
            items[0] = "错一个就重开（所有模式 · 顺序）";
            for (int i = 1; i < AdvancedSchulte.SHORT.length; i++) {
                items[i] = "错一个就重开（" + AdvancedSchulte.SHORT[i] + "）";
            }
            items[items.length - 1] = "错误不重开（所有模式 · 顺序）";
            new AlertDialog.Builder(act)
                    .setTitle("重开预设")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) {
                                confirmPreset(act, true, 1);
                            } else if (which == items.length - 1) {
                                confirmPreset(act, false, 0);
                            } else {
                                applyAdvPreset(act, which);
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            MainHook.log("重开预设弹窗失败: " + t);
        }
    }

    /** 单个高级模式：打开错误自动重开，所有阶数错误数设为 1（写 adv<mode>_ key） */
    private static void applyAdvPreset(Activity act, int adv) {
        try {
            if (!ensureKv()) {
                Ui.toast(act, "高级模式预设失败：KV 未就绪");
                return;
            }
            XposedHelpers.callMethod(errEnable, "setValue", "adv" + adv + "_", Boolean.TRUE);
            for (int level = 3; level <= 10; level++) {
                XposedHelpers.callMethod(errCount, "setValue",
                        "adv" + adv + "_" + level, Integer.valueOf(1));
            }
            Prefs.setAdvInitDone(act, adv);
            if (overrideAdv == adv) refreshOfficial(act);
            Ui.toast(act, "已开启：" + AdvancedSchulte.SHORT[adv] + " 错一个就重开（错误数 1）");
        } catch (Throwable t) {
            MainHook.log("高级模式预设失败: " + t);
        }
    }

    private static void confirmPreset(final Activity act, final boolean enable, final int count) {
        String action = enable
                ? "会把所有模式（简单 / 简单盲玩 / 普通 / 盲玩 / 简单打乱 / 打乱）"
                + "和所有阶数（3×3 ~ 10×10）的「错误自动重开」都打开，并把错误数设为 1。"
                + "\n\n只影响「顺序」模式（不叠加高级模式时）；高级模式请在第二排标签里各自设置。"
                : "会把所有模式（简单 / 简单盲玩 / 普通 / 盲玩 / 简单打乱 / 打乱）"
                + "和所有阶数（3×3 ~ 10×10）的「错误自动重开」关闭，并把错误数设为 0。";
        try {
            new AlertDialog.Builder(act)
                    .setTitle(enable ? "错一个就重开" : "错误不重开")
                    .setMessage(action + "\n\n确定吗？")
                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            applyPreset(act, enable, count);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            MainHook.log("重开预设确认失败: " + t);
        }
    }

    /**
     * 写入官方配置。
     * 官方开关回调 X(...) 的 key 是「type + 布尔」→ 0true / 0false，
     * 错误数的 key 是「type + 布尔 + level」→ 0true3；不能拼成 0/1，否则官方读不到。
     */
    private static void applyPreset(Activity act, boolean enable, int count) {
        try {
            if (!ensureKv()) return;
            Boolean flag = enable ? Boolean.TRUE : Boolean.FALSE;
            Integer value = Integer.valueOf(count);

            int savedAdv = overrideAdv;
            String savedBase = overrideBase;
            int written;
            overrideAdv = 0;
            overrideBase = null;
            try {
                for (int type = 0; type <= 3; type++) {
                    for (int blind = 0; blind <= 1; blind++) {
                        String base = type + String.valueOf(blind == 1);
                        XposedHelpers.callMethod(errEnable, "setValue", base, flag);
                        for (int level = 3; level <= 10; level++) {
                            XposedHelpers.callMethod(errCount, "setValue", base + level, value);
                        }
                    }
                }
                // 写完后读回 8 个组合的开关值，确认真的落到了官方读的地方
                // （校验必须在 override 清零期间做，否则 key 会被改写成 adv 的）
                written = countWritten(errEnable, flag);
                MainHook.log("重开预设写入校验: " + written + "/8 enable=" + enable);
                if (written < 8) {
                    // 少数情况下 App 的 SharedPreferences 内存缓存与写入不同步：
                    // 直接写官方 SP 落点并清空缓存后再校验一次
                    rewriteViaSp(act.getClassLoader(), errEnable, errCount, flag, value);
                    written = countWritten(errEnable, flag);
                    MainHook.log("重开预设二次校验: " + written + "/8");
                }
            } finally {
                overrideAdv = savedAdv;
                overrideBase = savedBase;
            }
            if (savedAdv > 0) ensureAdvInit(act, savedAdv);

            refreshOfficial(act);
            String head = enable
                    ? "已把所有模式、所有阶数的错误自动重开打开，错误数设为 1"
                    : "已把所有模式、所有阶数的错误自动重开关闭，错误数设为 0";
            Ui.toast(act, written >= 8 ? head : head + "（校验 " + written + "/8，请切换模式确认）");
        } catch (Throwable t) {
            MainHook.log("应用重开预设失败: " + t);
            Ui.toast(act, "应用预设失败: " + t);
        }
    }

    /** 读回 8 个模式组合的错误自动重开开关，统计与目标值一致的数量 */
    private static int countWritten(Object errEnable, Boolean flag) {
        int ok = 0;
        for (int type = 0; type <= 3; type++) {
            for (int blind = 0; blind <= 1; blind++) {
                try {
                    Object v = XposedHelpers.callMethod(errEnable, "getValue",
                            type + String.valueOf(blind == 1));
                    if (v instanceof Boolean && ((Boolean) v).equals(flag)) ok++;
                } catch (Throwable ignored) {
                }
            }
        }
        return ok;
    }

    /** 直接写官方 SharedPreferences 的落点（utils.o.g#s），并清空其内存缓存 */
    private static void rewriteViaSp(ClassLoader cl, Object errEnable, Object errCount,
                                     Boolean flag, Integer value) {
        try {
            String realEnable = String.valueOf(
                    XposedHelpers.callMethod(errEnable, "getRealKey"));
            String realCount = String.valueOf(
                    XposedHelpers.callMethod(errCount, "getRealKey"));
            Class<?> sp = XposedHelpers.findClass("com.star.minesweeping.utils.o.g", cl);
            Method put = sp.getDeclaredMethod("s", String.class, Object.class);
            put.setAccessible(true);
            for (int type = 0; type <= 3; type++) {
                for (int blind = 0; blind <= 1; blind++) {
                    String base = type + String.valueOf(blind == 1);
                    put.invoke(null, realEnable + base, flag);
                    for (int level = 3; level <= 10; level++) {
                        put.invoke(null, realCount + base + level, value);
                    }
                }
            }
            try {
                Field cache = sp.getDeclaredField("a");
                cache.setAccessible(true);
                Object map = cache.get(null);
                if (map instanceof Map) ((Map<?, ?>) map).clear();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            MainHook.log("重开预设直接写 SP 失败: " + t);
        }
    }

    private static TextView presetButton(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);
        try {
            int border = color(act, "game_schulte", 0xFF2ED47A);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x00000000);
            bg.setCornerRadius(Ui.dp(act, 16));
            bg.setStroke(Math.max(1, Ui.dp(act, 1)), border);
            tv.setBackground(bg);
            tv.setTextColor(color(act, "dark", 0xFF283437));
        } catch (Throwable ignored) {
        }
        return tv;
    }

    private static int color(Context c, String name, int fallback) {
        try {
            int id = Ui.resId(c, name, "color");
            if (id != 0) return c.getResources().getColor(id);
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 重开页的 ScrollView 内那一层 LinearLayout（整个设置列表的容器） */
    private static ViewGroup scrollContent(Activity act) {
        try {
            View root = act.findViewById(android.R.id.content);
            ScrollView sv = findScroll(root);
            if (sv != null && sv.getChildCount() > 0
                    && sv.getChildAt(0) instanceof ViewGroup) {
                return (ViewGroup) sv.getChildAt(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static ScrollView findScroll(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView found = findScroll(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
