package com.tapsss.lsposed;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
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
 * 「模式与阶数」弹窗：直接复用 App 自己的 SquareSizeDialog（com.star.minesweeping.k.b.z3），
 * 保留原版卡片背景、标题样式和 6 列阶数格子，只在标题下方插入一行模式选择。
 *
 * 原版对话框布局 dialog_square_size：
 *   CardView > LinearLayout[ RelativeLayout(title_text) , ConstraintLayout(RecyclerView) , FrameLayout(extra_layout) ]
 * 阶数点击由 z3 内部 dismiss 后回调 z3$b.a(int level)，这里用 Proxy 实现该接口。
 */
public final class SchulteModeDialog {

    private static final String DIALOG = "com.star.minesweeping.k.b.z3";
    private static final String DIALOG_CALLBACK = "com.star.minesweeping.k.b.z3$b";
    private static final String BUTTON = "com.star.minesweeping.ui.view.layout.ButtonLayout";
    private static final String SCHULTE_CONFIG = "com.star.minesweeping.i.c.d.a";

    private static final String[] LABELS = {"简单", "简单·盲打", "普通", "普通·盲打", "混合·简单", "混合"};
    /** {type, blind}，与前缀模式一一对应 */
    private static final int[][] MODES = {{1, 0}, {1, 1}, {0, 0}, {0, 1}, {2, 0}, {3, 0}};

    private SchulteModeDialog() {
    }

    static void show(final Activity act) {
        if (!Ui.isAlive(act)) return;
        try {
            ClassLoader cl = act.getClassLoader();
            Class<?> dlgCls = XposedHelpers.findClass(DIALOG, cl);
            Class<?> btnCls = XposedHelpers.findClass(BUTTON, cl);
            Class<?> cbCls = XposedHelpers.findClass(DIALOG_CALLBACK, cl);

            int curType = readType(act);
            boolean curBlind = readBlind(act);
            int curLevel = readLevel(act);

            final int[] picked = {indexOf(curType, curBlind)};
            final Object dlg = newInstance(dlgCls, new Class<?>[]{int.class, int.class, int.class},
                    curLevel, 3, 10);

            Object binding = XposedHelpers.getObjectField(dlg, "a");
            View root = (View) XposedHelpers.callMethod(binding, "getRoot");

            TextView title = (TextView) root.findViewById(Ui.resId(act, "title_text", "id"));
            if (title != null) title.setText("模式与阶数");

            final View[] chips = new View[LABELS.length];
            LinearLayout column = new LinearLayout(act);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(Ui.dp(act, 12), Ui.dp(act, 2), Ui.dp(act, 12), Ui.dp(act, 2));
            for (int row = 0; row < 2; row++) {
                LinearLayout line = new LinearLayout(act);
                line.setOrientation(LinearLayout.HORIZONTAL);
                for (int col = 0; col < 3; col++) {
                    final int idx = row * 3 + col;
                    final View chip = (View) newInstance(btnCls,
                            new Class<?>[]{android.content.Context.class}, act);
                    XposedHelpers.callMethod(chip, "setRadius", Ui.dp(act, 4));
                    XposedHelpers.callMethod(chip, "setText", LABELS[idx]);
                    XposedHelpers.callMethod(chip, "setCommonStyle", idx == picked[0]);
                    chip.setClickable(true);
                    chip.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            picked[0] = idx;
                            for (int i = 0; i < chips.length; i++) {
                                if (chips[i] != null) {
                                    XposedHelpers.callMethod(chips[i], "setCommonStyle", i == idx);
                                }
                            }
                        }
                    });
                    chips[idx] = chip;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, Ui.dp(act, 42), 1f);
                    lp.setMargins(Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3));
                    line.addView(chip, lp);
                }
                column.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            FrameLayout extra = (FrameLayout) root.findViewById(Ui.resId(act, "extra_layout", "id"));
            ViewGroup host = extra == null ? null : (ViewGroup) extra.getParent();
            if (host instanceof LinearLayout) {
                // 插到标题和阶数格子之间
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
                                apply(act, (Integer) args[0], MODES[picked[0]][0], MODES[picked[0]][1] == 1,
                                        curType, curBlind, curLevel);
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

    private static void apply(Activity act, int level, int type, boolean blind,
                              int curType, boolean curBlind, int curLevel) {
        if (type == curType && blind == curBlind && level == curLevel) return;

        boolean modeChanged = type != curType || blind != curBlind;
        if (!modeChanged) {
            // 只改阶数：优先走原版 W(int)，它内部会检查游戏是否进行中
            try {
                Object busy = XposedHelpers.callMethod(act, "M");
                if (!Boolean.TRUE.equals(busy)) {
                    XposedHelpers.callMethod(act, "W", level);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            // 写字段 + 更新 Intent，再重建页面：避免 launchMode=singleTask 下
            // startActivity 复用旧实例、onNewIntent 不重新读取 type/blind 的问题
            XposedHelpers.setIntField(act, "a", type);
            XposedHelpers.setBooleanField(act, "b", blind);
            XposedHelpers.setIntField(act, "c", level);
            Intent intent = act.getIntent();
            if (intent != null) {
                intent.putExtra("type", type);
                intent.putExtra("blind", blind);
                intent.putExtra("level", level);
            }
            act.recreate();
        } catch (Throwable t) {
            MainHook.log("切换模式失败: " + t);
            Ui.toast(act, "切换失败: " + t);
        }
    }

    private static int indexOf(int type, boolean blind) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i][0] == type && (MODES[i][1] == 1) == blind) return i;
        }
        return 2; // 兜底：普通
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
