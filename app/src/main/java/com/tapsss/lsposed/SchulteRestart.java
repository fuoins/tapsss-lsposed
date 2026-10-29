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
 * 2) 选中高级模式时，下方改为模块自己的「错误自动重开 / 错误数」配置（官方那套对高级模式不生效）；
 * 3) 顶部保留「重开预设」入口，一条预设即可批量写好所有模式 × 所有阶数。
 */
public final class SchulteRestart {

    private static final String RESTART_SETTING =
            "com.star.minesweeping.ui.activity.setting.game.SettingSchulteRestartActivity";
    /** 配置 KV：i = 错误自动重开开关（key = type+blind），j = 错误数阈值（key = type+blind+level） */
    private static final String SCHULTE_SETTING_KV = "com.star.minesweeping.i.f.j";

    private static final String TAG_PRESET_BOX = "tapsss_adv_restart_preset";
    private static final String TAG_TAB_BOX = "tapsss_adv_restart_tabs";

    /** 第一排：官方模式（type + blind），与 App 的 a/b 字段对应 */
    private static final String[] OFFICIAL_LABELS =
            {"简单", "简单盲玩", "普通", "盲玩", "简单打乱", "打乱"};
    private static final int[] OFFICIAL_TYPE = {0, 0, 1, 1, 2, 3};
    private static final boolean[] OFFICIAL_BLIND = {false, true, false, true, false, false};

    private static volatile boolean hooked;

    private SchulteRestart() {
    }

    static void install(ClassLoader cl) {
        if (hooked) return;
        try {
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
            hooked = true;
        } catch (Throwable t) {
            MainHook.log("重开页 Hook 失败: " + t);
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

    // ------------------------------------------------------------------ 两排标签 + 高级模式面板

    private static final class TabUi {
        LinearLayout box;
        ViewGroup officialRow;
        ViewGroup advRow;
        LinearLayout panel;
        TextView restartValue;
        TextView countValue;
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

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        panel.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), Ui.dp(act, 4));

        ui.restartValue = panelRow(act, panel, "错误自动重开", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int adv = Math.max(1, ui.adv);
                Prefs.setAdvErrRestart(act, adv, !Prefs.advErrRestart(act, adv));
                renderPanel(act, ui);
            }
        });
        ui.countValue = panelRow(act, panel, "错误数（错几次后重开）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickCount(act, ui);
            }
        });
        ui.panel = panel;
        panel.setTag(ui);
        box.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(act);
        hint.setText("高级模式下的重开由模块控制，与官方顺序模式的配置互不影响；"
                + "「顺序」表示按官方配置执行。");
        hint.setTextSize(10);
        hint.setAlpha(0.5f);
        hint.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 8));
        box.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return box;
    }

    private static TextView panelRow(Activity act, LinearLayout panel, String title,
                                     View.OnClickListener click) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));
        row.setClickable(true);
        row.setOnClickListener(click);

        TextView label = new TextView(act);
        label.setText(title);
        label.setTextSize(14);
        label.setTextColor(color(act, "dark", 0xFF283437));
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(act);
        value.setTextSize(14);
        value.setTextColor(color(act, "normal", 0xFF888888));
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(act);
        divider.setBackgroundColor(0x14000000);
        panel.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(act, 0.5f))));
        return value;
    }

    private static void renderPanel(Activity act, TabUi ui) {
        if (ui == null || ui.restartValue == null) return;
        int adv = Math.max(1, ui.adv);
        boolean on = Prefs.advErrRestart(act, adv);
        int n = Math.max(1, Prefs.advErrCount(act, adv));
        ui.restartValue.setText(on ? "已开启" : "已关闭");
        ui.restartValue.setTextColor(color(act, on ? "game_schulte" : "normal",
                on ? 0xFF2ED47A : 0xFF888888));
        ui.countValue.setText(n + " 次");
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
    }

    private static void pickAdv(Activity act, TabUi ui, int adv) {
        ui.adv = adv;
        AdvancedPages.applyChipState(ui.advRow, adv);
        boolean advOn = adv > 0;
        ui.panel.setVisibility(advOn ? View.VISIBLE : View.GONE);
        showOfficialError(act, !advOn);
        if (advOn) renderPanel(act, ui);
    }

    private static void pickCount(final Activity act, final TabUi ui) {
        final int adv = Math.max(1, ui.adv);
        String[] items = new String[10];
        for (int i = 0; i < items.length; i++) items[i] = (i + 1) + " 次";
        try {
            new AlertDialog.Builder(act)
                    .setTitle(AdvancedSchulte.SHORT[adv] + "：错误数（错几次后重开）")
                    .setSingleChoiceItems(items, Math.max(1, Prefs.advErrCount(act, adv)) - 1,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Prefs.setAdvErrCount(act, adv, which + 1);
                                    dialog.dismiss();
                                    renderPanel(act, ui);
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            MainHook.log("错误数选择失败: " + t);
        }
    }

    /** 选中高级模式时隐藏官方「重开时错误」整块（标题 + 开关 + 8 个阶数） */
    private static void showOfficialError(Activity act, boolean show) {
        View anchor = findById(act, "error_restart_item");
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) anchor.getParent();
        int start = Math.max(0, parent.indexOfChild(anchor) - 1);
        int count = Math.min(10, parent.getChildCount() - start);
        for (int i = 0; i < count; i++) {
            View v = parent.getChildAt(start + i);
            if (v != null) v.setVisibility(show ? View.VISIBLE : View.GONE);
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
            final String[] items = new String[AdvancedSchulte.SHORT.length + 2];
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

    /** 单个高级模式：打开错误自动重开，错误数 1 */
    private static void applyAdvPreset(Activity act, int adv) {
        try {
            Prefs.setAdvErrRestart(act, adv, true);
            Prefs.setAdvErrCount(act, adv, 1);
            TabUi ui = tabsOf(act);
            if (ui != null && ui.adv == adv) {
                ui.panel.setVisibility(View.VISIBLE);
                showOfficialError(act, false);
                AdvancedPages.applyChipState(ui.advRow, adv);
                renderPanel(act, ui);
            }
            Ui.toast(act, "已开启：" + AdvancedSchulte.SHORT[adv] + " 错一个就重开（错误数 1）");
        } catch (Throwable t) {
            MainHook.log("高级模式预设失败: " + t);
        }
    }

    private static TabUi tabsOf(Activity act) {
        try {
            ViewGroup content = scrollContent(act);
            if (content == null) return null;
            View box = content.findViewWithTag(TAG_TAB_BOX);
            if (!(box instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) box;
            for (int i = 0; i < group.getChildCount(); i++) {
                Object tag = group.getChildAt(i).getTag();
                if (tag instanceof TabUi) return (TabUi) tag;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void confirmPreset(final Activity act, final boolean enable, final int count) {
        String action = enable
                ? "会把所有模式（简单 / 简单盲玩 / 普通 / 盲玩 / 简单打乱 / 打乱）"
                + "和所有阶数（3×3 ~ 10×10）的「错误自动重开」都打开，并把错误数设为 1。"
                + "\n\n只影响「顺序」模式（不叠加高级模式时）；高级模式请在下面各自设置。"
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
            ClassLoader cl = act.getClassLoader();
            Class<?> kvCls = XposedHelpers.findClass(SCHULTE_SETTING_KV, cl);
            Object errEnable = XposedHelpers.getStaticObjectField(kvCls, "i");
            Object errCount = XposedHelpers.getStaticObjectField(kvCls, "j");
            Boolean flag = enable ? Boolean.TRUE : Boolean.FALSE;
            Integer value = Integer.valueOf(count);

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
            int written = countWritten(errEnable, flag);
            MainHook.log("重开预设写入校验: " + written + "/8 enable=" + enable);
            if (written < 8) {
                // 少数情况下 App 的 SharedPreferences 内存缓存与写入不同步：
                // 直接写官方 SP 落点并清空缓存后再校验一次
                rewriteViaSp(cl, errEnable, errCount, flag, value);
                written = countWritten(errEnable, flag);
                MainHook.log("重开预设二次校验: " + written + "/8");
            }

            // 刷新页面：r0 = 错误设置列表，s0 = 开关/倒计时面板，w(type, blind) = 当前模式
            for (String m : new String[]{"r0", "s0"}) {
                try {
                    XposedHelpers.callMethod(act, m);
                } catch (Throwable ignored) {
                }
            }
            try {
                int type = XposedHelpers.getIntField(act, "a");
                boolean blind = XposedHelpers.getBooleanField(act, "b");
                XposedHelpers.callMethod(act, "w", type, blind);
            } catch (Throwable ignored) {
            }
            // 页面开关是自定义 SwitchItem，w() 里刷新的是 KV 读取结果；
            // 这里直接把「错误自动重开」开关（binding 的 Z）也同步过来，确保视觉上就是开的
            try {
                Object binding = XposedHelpers.getObjectField(act, "view");
                Object sw = XposedHelpers.getObjectField(binding, "Z");
                if (sw != null) XposedHelpers.callMethod(sw, "setChecked", flag);
            } catch (Throwable ignored) {
            }
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
