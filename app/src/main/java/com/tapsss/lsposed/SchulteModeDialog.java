package com.tapsss.lsposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

/**
 * 「模式与阶数」弹窗：复用 App 自己的 SquareSizeDialog（com.star.minesweeping.k.b.z3），
 * 保留原版卡片背景、标题和阶数格子，在标题下方插入：
 *  1. 官方模式（6 个格子，与阶数格子同款）；
 *  2. 高级模式（顺序/爆手速/倒序/归中/max≤N/1start，每个模式旁有 i 查看说明）；
 *  3. 爆手速的目标 x（1..n²）；
 * 底部额外放「舒尔特设置」「模块设置」两个文字按钮。
 */
public final class SchulteModeDialog {

    private static final String DIALOG = "com.star.minesweeping.k.b.z3";
    private static final String DIALOG_CALLBACK = "com.star.minesweeping.k.b.z3$b";
    private static final String BUTTON = "com.star.minesweeping.ui.view.layout.ButtonLayout";
    private static final String SCHULTE_CONFIG = "com.star.minesweeping.i.c.d.a";
    private static final String COLOR_HELPER = "com.star.minesweeping.utils.n.o";
    private static final String THEME = "com.star.minesweeping.i.h.a";
    private static final String SCHULTE_SETTING =
            "com.star.minesweeping.ui.activity.setting.game.SettingSchulteActivity";

    private static final String[] LABELS = {
            "简单", "简单盲玩", "简单打乱", "普通", "普通盲玩", "普通打乱"};
    /** {type, blind}：1=简单 2=简单打乱 0=普通 3=打乱 */
    private static final int[][] MODES = {
            {1, 0}, {1, 1}, {2, 0}, {0, 0}, {0, 1}, {3, 0}};

    private static final String[] ADV_LABELS = {
            "顺序", "爆手速", "倒序", "归中", "max≤N", "1start"};

    private static final int LINK_COLOR = 0xFF6FA8FF;

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
            /** 已生效的高级模式（0=顺序/关闭，其余与 AdvancedSchulte 的常量一致） */
            final int[] adv = {Prefs.advMode(act)};
            final Object dlg = newInstance(dlgCls, new Class<?>[]{int.class, int.class, int.class},
                    curLevel, 3, 10);

            Object binding = XposedHelpers.getObjectField(dlg, "a");
            View root = (View) XposedHelpers.callMethod(binding, "getRoot");

            TextView title = (TextView) root.findViewById(Ui.resId(act, "title_text", "id"));
            if (title != null) title.setText("模式与阶数");

            final LinearLayout column = new LinearLayout(act);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(Ui.dp(act, 12), Ui.dp(act, 2), Ui.dp(act, 12), 0);
            column.addView(sectionLabel(act, "官方模式"));

            final View[] chips = new View[LABELS.length];
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
                    chips[idx] = chip;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, Ui.dp(act, 45), 1f);
                    lp.setMargins(Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3));
                    line.addView(chip, lp);
                }
                column.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            // ------------------------------------------------ 高级模式
            LinearLayout advHeader = new LinearLayout(act);
            advHeader.setOrientation(LinearLayout.HORIZONTAL);
            advHeader.setGravity(Gravity.CENTER_VERTICAL);
            advHeader.setPadding(Ui.dp(act, 3), Ui.dp(act, 10), Ui.dp(act, 3), Ui.dp(act, 2));
            TextView advTitle = new TextView(act);
            advTitle.setText("高级模式");
            advTitle.setTextSize(13);
            advTitle.getPaint().setFakeBoldText(true);
            advHeader.addView(advTitle);
            TextView advHint = new TextView(act);
            // 版本 + 接管状态：一眼确认装的是哪版、Hook 有没有真的生效
            advHint.setText("v" + MainHook.VERSION + " · "
                    + AdvancedSchulte.statusText() + " · 点 i 看说明");
            advHint.setTextSize(10);
            advHint.setAlpha(0.55f);
            advHint.setGravity(Gravity.END);
            advHeader.addView(advHint, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            column.addView(advHeader);

            final View[] advChips = new View[ADV_LABELS.length];
            final TextView[] advInfos = new TextView[ADV_LABELS.length];
            for (int row = 0; row < 3; row++) {
                LinearLayout line = new LinearLayout(act);
                line.setOrientation(LinearLayout.HORIZONTAL);
                for (int col = 0; col < 2; col++) {
                    final int idx = row * 2 + col;
                    LinearLayout cell = new LinearLayout(act);
                    cell.setOrientation(LinearLayout.HORIZONTAL);
                    cell.setGravity(Gravity.CENTER_VERTICAL);

                    final View chip = (View) newInstance(btnCls,
                            new Class<?>[]{Context.class}, act);
                    XposedHelpers.callMethod(chip, "setRadius", Ui.dp(act, 4));
                    XposedHelpers.callMethod(chip, "setText", ADV_LABELS[idx]);
                    centerText(act, chip);
                    styleCell(act, chip, idx == adv[0]);
                    chip.setClickable(true);
                    cell.addView(chip, new LinearLayout.LayoutParams(
                            0, Ui.dp(act, 40), 1f));

                    TextView info = infoIcon(act, dialogTextColor(act));
                    LinearLayout.LayoutParams infoLp =
                            new LinearLayout.LayoutParams(Ui.dp(act, 18), Ui.dp(act, 18));
                    infoLp.leftMargin = Ui.dp(act, 3);
                    cell.addView(info, infoLp);

                    advChips[idx] = chip;
                    advInfos[idx] = info;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(Ui.dp(act, 3), Ui.dp(act, 2), Ui.dp(act, 3), Ui.dp(act, 2));
                    line.addView(cell, lp);
                }
                column.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            // 爆手速的目标 x（仅爆手速显示）
            final LinearLayout xRow = new LinearLayout(act);
            xRow.setOrientation(LinearLayout.HORIZONTAL);
            xRow.setGravity(Gravity.CENTER_VERTICAL);
            xRow.setPadding(Ui.dp(act, 3), Ui.dp(act, 4), Ui.dp(act, 3), 0);
            TextView xLabel = new TextView(act);
            xLabel.setText("目标 x");
            xLabel.setTextSize(12);
            xLabel.setAlpha(0.85f);
            xRow.addView(xLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final EditText xInput = new EditText(act);
            xInput.setSingleLine(true);
            xInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            int textColor = dialogTextColor(act);
            int tint = textColor & 0x00FFFFFF;
            xInput.setTextSize(14);
            xInput.setTextColor(textColor);
            xInput.setHintTextColor(tint | 0x66000000);
            xInput.setPadding(Ui.dp(act, 8), 0, Ui.dp(act, 8), 0);
            GradientDrawable inputBg = new GradientDrawable();
            inputBg.setColor(tint | 0x1F000000);
            inputBg.setCornerRadius(Ui.dp(act, 4));
            xInput.setBackground(inputBg);
            int savedX = Prefs.advX(act);
            if (savedX > 0) xInput.setText(String.valueOf(savedX));
            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                    0, Ui.dp(act, 36), 1f);
            inputLp.leftMargin = Ui.dp(act, 8);
            xRow.addView(xInput, inputLp);
            final LinearLayout xWrap = new LinearLayout(act);
            xWrap.setOrientation(LinearLayout.VERTICAL);
            xWrap.addView(xRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(xWrap);

            final Runnable refreshAdv = new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < advChips.length; i++) {
                        styleCell(act, advChips[i], i == adv[0]);
                    }
                    xWrap.setVisibility(adv[0] == AdvancedSchulte.SPEED
                            ? View.VISIBLE : View.GONE);
                    int total = applied[2] * applied[2];
                    // 换阶数后目标 x 的上限跟着变：超了压回新上限并同步到输入框
                    int saved = Prefs.advX(act);
                    if (saved > total) {
                        Prefs.setAdvX(act, total);
                        xInput.setText(String.valueOf(total));
                    }
                    xInput.setHint("留空 = 全部 " + total);
                }
            };
            refreshAdv.run();

            xInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    int v;
                    try {
                        v = s.length() == 0 ? 0 : Integer.parseInt(s.toString());
                    } catch (Throwable t) {
                        return;
                    }
                    if (v < 0) v = 0;
                    int max = Math.max(1, applied[2] * applied[2]);
                    Prefs.setAdvX(act, Math.min(v, max));
                }
            });

            for (int i = 0; i < advChips.length; i++) {
                final int idx = i;
                advChips[i].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (idx == AdvancedSchulte.START1
                                && (applied[2] < 3 || applied[2] > 5)) {
                            Ui.toast(act, "1start 训练仅支持 3-5 阶");
                            return;
                        }
                        if (adv[0] == idx) return;   // 没有变化：留着弹窗让用户继续改
                        adv[0] = idx;
                        Prefs.setAdvMode(act, idx);
                        refreshAdv.run();
                        // 切换高级模式后直接关窗并重建本局，状态立刻生效
                        ((Dialog) dlg).dismiss();
                        AdvancedSchulte.resetRound(act);
                        Ui.toast(act, "已切换为 " + AdvancedSchulte.NAMES[idx]);
                    }
                });
                TextView info = advInfos[i];
                info.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showDesc(act, idx);
                    }
                });
            }

            for (int i = 0; i < chips.length; i++) {
                final int idx = i;
                chips[i].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        picked[0] = idx;
                        for (int k = 0; k < chips.length; k++) {
                            if (chips[k] != null) styleCell(act, chips[k], k == idx);
                        }
                        // 点模式格子立即切模式，无需再去点阶数格子。
                        // 注意：这里不动高级模式 —— 高级模式是在当前官方模式之上叠加规则，
                        // 切官方模式不应该把高级模式退回「顺序」
                        apply(act, (Dialog) dlg, applied, MODES[idx][0],
                                MODES[idx][1] == 1, applied[2], refreshAdv);
                    }
                });
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

            // ------------------------------------------------ 底部两个文字按钮
            LinearLayout footer = new LinearLayout(act);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setPadding(0, Ui.dp(act, 6), 0, 0);

            TextView schulteSetting = textButton(act, "舒尔特设置");
            schulteSetting.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ((Dialog) dlg).dismiss();
                    try {
                        // 标记「去过设置页」：返回游戏页时直接整页重建，
                        // 主题 / 倒计时 / 重开预设等一切设置都按新值重新初始化
                        XposedHelpers.setAdditionalInstanceField(act,
                                SchulteHooks.KEY_FROM_SETTINGS, Boolean.TRUE);
                        Intent it = new Intent();
                        it.setClassName(act, SCHULTE_SETTING);
                        act.startActivity(it);
                    } catch (Throwable t) {
                        XposedHelpers.removeAdditionalInstanceField(act,
                                SchulteHooks.KEY_FROM_SETTINGS);
                        MainHook.log("打开舒尔特设置失败: " + t);
                        Ui.toast(act, "打开舒尔特设置失败");
                    }
                }
            });
            footer.addView(schulteSetting, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView moduleSetting = textButton(act, "模块设置");
            moduleSetting.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ((Dialog) dlg).dismiss();
                    ModuleSettings.show(act);
                }
            });
            footer.addView(moduleSetting, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            callDialogMethod(dlg, "m", new Class<?>[]{View.class, ViewGroup.LayoutParams.class},
                    footer, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Object callback = Proxy.newProxyInstance(cl, new Class<?>[]{cbCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (args != null && args.length == 1 && args[0] instanceof Integer) {
                                apply(act, (Dialog) dlg, applied, applied[0],
                                        applied[1] == 1, (Integer) args[0], refreshAdv);
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

    private static TextView sectionLabel(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setAlpha(0.55f);
        tv.setPadding(Ui.dp(act, 4), Ui.dp(act, 3), 0, 0);
        return tv;
    }

    private static TextView infoIcon(Activity act, int color) {
        int tint = color & 0x00FFFFFF;
        final TextView info = new TextView(act);
        info.setText("i");
        info.setTextSize(10);
        info.setTextColor(tint | 0xCC000000);
        info.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(tint | 0x14000000);
        ring.setStroke(Ui.dp(act, 1), tint | 0x99000000);
        info.setBackground(ring);
        info.setClickable(true);
        // 点击区域比 18dp 的圆略大一点，方便点到
        info.setPadding(Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4));
        return info;
    }

    /** 弹窗正文颜色：跟随 App 自己阶数格子的文字色（浅色 #283437 / 深色 #d6e0e2） */
    private static int dialogTextColor(Activity act) {
        try {
            Class<?> colorHelper = XposedHelpers.findClass(COLOR_HELPER, act.getClassLoader());
            Object color = XposedHelpers.callStaticMethod(colorHelper, "d",
                    Ui.resId(act, "dark", "color"));
            if (color instanceof Integer) return (Integer) color;
        } catch (Throwable ignored) {
        }
        boolean night = (act.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return night ? 0xFFD6E0E2 : 0xFF283437;
    }

    private static TextView textButton(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(LINK_COLOR);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));
        tv.setClickable(true);
        return tv;
    }

    private static void showDesc(Activity act, int idx) {
        try {
            new AlertDialog.Builder(act)
                    .setTitle(AdvancedSchulte.NAMES[idx])
                    .setMessage(AdvancedSchulte.DESCS[idx])
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable t) {
            MainHook.log("显示模式说明失败: " + t);
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
    private static void apply(Activity act, Dialog dlg, int[] applied, int type, boolean blind,
                              int level, Runnable refreshAdv) {
        // 1start 仅支持 3-5 阶，换到高/低阶时自动退回顺序模式
        if (Prefs.advMode(act) == AdvancedSchulte.START1 && (level < 3 || level > 5)) {
            Prefs.setAdvMode(act, AdvancedSchulte.MODE_OFF);
            if (refreshAdv != null) refreshAdv.run();
            Ui.toast(act, "1start 训练仅支持 3-5 阶，已切回顺序模式");
        }

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
            // 阶数变了：目标 x 的上限/hint 立刻按新阶数刷新
            if (levelChanged && refreshAdv != null) refreshAdv.run();
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
