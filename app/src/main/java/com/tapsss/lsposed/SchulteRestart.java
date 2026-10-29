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
 * 舒尔特「重开」设置页：顶部加一个「重开预设」按钮。
 *
 * 官方这一页只支持逐个模式、逐个阶数去改错误数/倒计时阈值，很繁琐；
 * 预设弹窗里点一条（如「错一个就重开」）确认后，直接把
 * 所有模式 × 所有阶数（3-10）的错误自动重开阈值一次性设好。
 */
public final class SchulteRestart {

    private static final String RESTART_SETTING =
            "com.star.minesweeping.ui.activity.setting.game.SettingSchulteRestartActivity";
    /** 配置 KV：i = 错误自动重开开关（key = type+blind），j = 错误数阈值（key = type+blind+level） */
    private static final String SCHULTE_SETTING_KV = "com.star.minesweeping.i.f.j";

    private static final String TAG_PRESET_BOX = "tapsss_adv_restart_preset";

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

    private static void decorate(Activity act) {
        if (!Ui.isAlive(act)) return;
        ViewGroup content = scrollContent(act);
        if (content == null) return;
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

        content.addView(box, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 预设列表：错一个就重开 / 错误不重开 */
    private static void showPresets(final Activity act) {
        try {
            new AlertDialog.Builder(act)
                    .setTitle("重开预设")
                    .setItems(new String[]{
                            "错一个就重开（所有模式 · 所有阶数）",
                            "错误不重开（所有模式 · 所有阶数）"},
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which == 0) confirmPreset(act, true, 1);
                                    else confirmPreset(act, false, 0);
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            MainHook.log("重开预设弹窗失败: " + t);
        }
    }

    private static void confirmPreset(final Activity act, final boolean enable, final int count) {
        String action = enable
                ? "会把所有模式（简单 / 简单盲玩 / 普通 / 普通盲玩 / 简单打乱 / 打乱）"
                + "和所有阶数（3×3 ~ 10×10）的「错误自动重开」都打开，并把错误数设为 1。"
                : "会把所有模式（简单 / 简单盲玩 / 普通 / 普通盲玩 / 简单打乱 / 打乱）"
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
