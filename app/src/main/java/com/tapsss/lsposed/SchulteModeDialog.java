package com.tapsss.lsposed;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

/**
 * 「模式与阶数」弹窗：复用 App 自己的 SquareSizeDialog（com.star.minesweeping.k.b.z3），
 * 保留原版卡片背景、标题和 6 列阶数格子，只在标题下方插入一行模式选择。
 * 模式格子与阶数格子同款（ButtonLayout + 相同的选中态）。
 */
public final class SchulteModeDialog {

    private static final String DIALOG = "com.star.minesweeping.k.b.z3";
    private static final String DIALOG_CALLBACK = "com.star.minesweeping.k.b.z3$b";
    private static final String BUTTON = "com.star.minesweeping.ui.view.layout.ButtonLayout";
    private static final String SCHULTE_CONFIG = "com.star.minesweeping.i.c.d.a";
    private static final String COLOR_HELPER = "com.star.minesweeping.utils.n.o";
    private static final String THEME = "com.star.minesweeping.i.h.a";

    private static final String[] LABELS = {
            "简单", "简单盲玩", "简单打乱", "普通", "普通盲玩", "普通打乱"};
    /** {type, blind}：1=简单 2=简单打乱 0=普通 3=打乱 */
    private static final int[][] MODES = {
            {1, 0}, {1, 1}, {2, 0}, {0, 0}, {0, 1}, {3, 0}};

    private SchulteModeDialog() {
    }

    static void show(final Activity act) {
        if (!Ui.isAlive(act)) return;
        try {
            ClassLoader cl = act.getClassLoader();
            Class<?> dlgCls = XposedHelpers.findClass(DIALOG, cl);
            Class<?> btnCls = XposedHelpers.findClass(BUTTON, cl);
            Class<?> cbCls = XposedHelpers.findClass(DIALOG_CALLBACK, cl);

            final int curType = readType(act);
            final boolean curBlind = readBlind(act);
            final int curLevel = readLevel(act);

            final int[] picked = {indexOf(curType, curBlind)};
            /** 已生效的 {type, blind(0/1), level}，连续点多个格子时以此为准 */
            final int[] applied = {curType, curBlind ? 1 : 0, curLevel};
            final Object dlg = newInstance(dlgCls, new Class<?>[]{int.class, int.class, int.class},
                    curLevel, 3, 10);

            Object binding = XposedHelpers.getObjectField(dlg, "a");
            View root = (View) XposedHelpers.callMethod(binding, "getRoot");

            TextView title = (TextView) root.findViewById(Ui.resId(act, "title_text", "id"));
            if (title != null) title.setText("模式与阶数");

            final View[] chips = new View[LABELS.length];
            LinearLayout column = new LinearLayout(act);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(Ui.dp(act, 12), Ui.dp(act, 2), Ui.dp(act, 12), 0);
            for (int row = 0; row < 2; row++) {
                LinearLayout line = new LinearLayout(act);
                line.setOrientation(LinearLayout.HORIZONTAL);
                for (int col = 0; col < 3; col++) {
                    final int idx = row * 3 + col;
                    final View chip = (View) newInstance(btnCls,
                            new Class<?>[]{Context.class}, act);
                    XposedHelpers.callMethod(chip, "setRadius", Ui.dp(act, 4));
                    XposedHelpers.callMethod(chip, "setText", LABELS[idx]);
                    centerText(act, chip);
                    styleCell(act, chip, idx == picked[0]);
                    chip.setClickable(true);
                    chip.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            picked[0] = idx;
                            for (int i = 0; i < chips.length; i++) {
                                if (chips[i] != null) styleCell(act, chips[i], i == idx);
                            }
                            // 点模式格子立即切模式，无需再去点阶数格子
                            apply(act, (Dialog) dlg, applied, MODES[idx][0],
                                    MODES[idx][1] == 1, applied[2]);
                        }
                    });
                    chips[idx] = chip;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, Ui.dp(act, 45), 1f);
                    lp.setMargins(Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3));
                    line.addView(chip, lp);
                }
                column.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            FrameLayout extra = (FrameLayout) root.findViewById(Ui.resId(act, "extra_layout", "id"));
            ViewGroup host = extra == null ? null : (ViewGroup) extra.getParent();
            if (host instanceof LinearLayout) {
                host.addView(column, 1, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (extra != null) {
                callDialogMethod(dlg, "m", new Class<?>[]{View.class, ViewGroup.LayoutParams.class},
                        column, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            Object callback = Proxy.newProxyInstance(cl, new Class<?>[]{cbCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (args != null && args.length == 1 && args[0] instanceof Integer) {
                                apply(act, (Dialog) dlg, applied, applied[0],
                                        applied[1] == 1, (Integer) args[0]);
                            }
                            return null;
                        }
                    });
            callDialogMethod(dlg, "q", new Class<?>[]{cbCls}, callback);

            ((Dialog) dlg).show();
        } catch (Throwable t) {
            MainHook.log("打开模式与阶数弹窗失败: " + t);
            Ui.toast(act, "打开模式与阶数失败: " + t);
        }
    }

    /**
     * 阶数格子同款文字：ButtonLayout 里的 TextView 是私有字段 a（无 id），
     * setText() 内部用默认 wrap_content 参数 addView，程序化创建时文字会顶着左上角；
     * 这里把它改成 fill_parent + 居中（ButtonLayout 本身没有 setTextSizePx 之类的方法）。
     */
    private static void centerText(Activity act, View chip) {
        try {
            LinearLayout layout = (LinearLayout) chip;
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            TextView tv = innerText(chip, layout);
            if (tv == null) return;
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tv.setGravity(Gravity.CENTER);
            tv.setIncludeFontPadding(false);
            tv.getPaint().setFakeBoldText(true);
            int id = Ui.resId(act, "font_normal", "dimen");
            if (id != 0) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        act.getResources().getDimensionPixelSize(id));
            }
        } catch (Throwable t) {
            MainHook.log("模式文字居中失败: " + t);
        }
    }

    /** ButtonLayout 内部 TextView：优先取私有字段 a，取不到再退化为第一个 TextView 子 View */
    private static TextView innerText(View chip, LinearLayout layout) {
        try {
            Object f = XposedHelpers.getObjectField(chip, "a");
            if (f instanceof TextView && ((TextView) f).getParent() == layout) return (TextView) f;
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < layout.getChildCount(); i++) {
            View v = layout.getChildAt(i);
            if (v instanceof TextView) return (TextView) v;
        }
        return null;
    }

    /** 与 k.a.g 绑定格子时的 setTextColor / setPressedColor / setSelected 保持一致 */
    private static void styleCell(Activity act, View chip, boolean selected) {
        try {
            ClassLoader cl = act.getClassLoader();
            Class<?> colorHelper = XposedHelpers.findClass(COLOR_HELPER, cl);
            int textColor = (Integer) XposedHelpers.callStaticMethod(colorHelper, "d",
                    Ui.resId(act, "dark", "color"));
            int pressColor = (Integer) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(THEME, cl), "c");
            XposedHelpers.callMethod(chip, "setTextColor", textColor);
            XposedHelpers.callMethod(chip, "setTextPressedColor", textColor);
            XposedHelpers.callMethod(chip, "setBackgroundColor", 0);
            XposedHelpers.callMethod(chip, "setPressedColor", pressColor);
            XposedHelpers.callMethod(chip, "setSelected", selected);
        } catch (Throwable t) {
            MainHook.log("模式格子样式失败: " + t);
        }
    }

    private static Object newInstance(Class<?> cls, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private static void callDialogMethod(Object dlg, String name, Class<?>[] types, Object... args) {
        try {
            Method m = XposedHelpers.findMethodExact(dlg.getClass(), name, types);
            m.setAccessible(true);
            m.invoke(dlg, args);
        } catch (Throwable t) {
            MainHook.log("调用 " + name + " 失败: " + t);
        }
    }

    /**
     * 原地生效：
     *  - 只改阶数：直接调原版 W(level) 重建本局；
     *  - 改模式：3.6.10 的盲玩/模式控制器（i.c.d.c.a）在 init() 时按 Intent 的
     *    type/blind 创建，改字段不会刷新它，因此改 Intent extras 后 recreate()，
     *    让 ARouter 重新注入。两者都改时一并交给 recreate()。
     */
    private static void apply(Activity act, Dialog dlg, int[] applied, int type, boolean blind, int level) {
        boolean modeChanged = type != applied[0] || blind != (applied[1] == 1);
        boolean levelChanged = level != applied[2];
        if (!modeChanged && !levelChanged) return;

        if (modeChanged) {
            try {
                Intent intent = act.getIntent();
                intent.putExtra("type", type);
                intent.putExtra("blind", blind);
                intent.putExtra("level", level);
                applied[0] = type;
                applied[1] = blind ? 1 : 0;
                applied[2] = level;
                Ui.toast(act, "已切换为 " + LABELS[indexOf(type, blind)]);
                if (dlg != null && dlg.isShowing()) dlg.dismiss();
                act.recreate();
                return;
            } catch (Throwable t) {
                MainHook.log("重建游戏页失败，改为原地修改: " + t);
            }
        }

        try {
            XposedHelpers.setAdditionalInstanceField(act, SchulteHooks.KEY_BYPASS_BUSY, Boolean.TRUE);
            try {
                XposedHelpers.setIntField(act, "a", type);
                XposedHelpers.setBooleanField(act, "b", blind);
                Object game = XposedHelpers.getObjectField(act, "g");
                if (game != null) {
                    XposedHelpers.callMethod(game, "y", type);
                    XposedHelpers.callMethod(game, "o", blind);
                }
                XposedHelpers.callMethod(act, "W", level);
            } finally {
                XposedHelpers.removeAdditionalInstanceField(act, SchulteHooks.KEY_BYPASS_BUSY);
            }
            applied[0] = type;
            applied[1] = blind ? 1 : 0;
            applied[2] = level;
            if (modeChanged) {
                Ui.toast(act, "已切换为 " + LABELS[indexOf(type, blind)]);
            }
        } catch (Throwable t) {
            MainHook.log("切换模式失败: " + t);
            Ui.toast(act, "切换失败: " + t);
        }
    }

    private static int indexOf(int type, boolean blind) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i][0] == type && (MODES[i][1] == 1) == blind) return i;
        }
        return 3; // 兜底：普通
    }

    private static int readType(Activity act) {
        try {
            return XposedHelpers.getIntField(act, "a");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean readBlind(Activity act) {
        try {
            return XposedHelpers.getBooleanField(act, "b");
        } catch (Throwable t) {
            return false;
        }
    }

    private static int readLevel(Activity act) {
        try {
            int l = XposedHelpers.getIntField(act, "c");
            if (l >= 3 && l <= 10) return l;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> config = XposedHelpers.findClass(SCHULTE_CONFIG, act.getClassLoader());
            Object l = XposedHelpers.callStaticMethod(config, "h");
            if (l instanceof Integer) return (Integer) l;
        } catch (Throwable ignored) {
        }
        return 3;
    }
}
