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
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「模式与阶数」弹窗：复用 App 自己的 SquareSizeDialog（com.star.minesweeping.k.b.z3），
 * 保留原版卡片背景、标题和阶数格子，在标题下方插入：
 *  1. 官方模式（6 个格子，与阶数格子同款）；
 *  2. 高级模式（顺序/爆手速/倒序/归中/max≤N/1start/顺序区间/倒序区间，每个模式旁有 i 查看说明）；
 *  3. 爆手速的目标 x（1..n²）、顺序/倒序区间的起点与终点；
 * 底部额外放「舒尔特设置」「模块设置」两个文字按钮，最下面是「确认」——
 * 弹窗里的所有选择都只是暂存，点确认才生效并关闭。
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
            "顺序", "爆手速", "倒序", "归中", "max≤N", "1start", "顺序区间", "倒序区间"};

    private static final int LINK_COLOR = 0xFF6FA8FF;

    /** 阶数格子被点击时，把选中阶数暂存到这里（int[1]，与弹窗里的 pickedLevel 同一个对象） */
    private static final String KEY_PICKED_LEVEL = "tapsss_mode_picked_level";
    /** 阶数变化后刷新弹窗提示用 */
    private static final String KEY_REFRESH_ADV = "tapsss_mode_refresh_adv";
    private static volatile boolean itemClickHooked;

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
            /** 弹窗里暂存的阶数：点格子只改这里，点「确认」才真正应用 */
            final int[] pickedLevel = {curLevel};
            /** 已生效的高级模式（0=顺序/关闭，其余与 AdvancedSchulte 的常量一致） */
            final int[] adv = {Prefs.advMode(act)};
            final Object dlg = newInstance(dlgCls, new Class<?>[]{int.class, int.class, int.class},
                    curLevel, 3, 10);
            installItemClickHook(cl);

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
            advTitle.setTextColor(dialogTextColor(act));
            advTitle.getPaint().setFakeBoldText(true);
            advHeader.addView(advTitle);
            TextView advHint = new TextView(act);
            // 版本 + 接管状态：一眼确认装的是哪版、Hook 有没有真的生效
            advHint.setText("v" + MainHook.VERSION + " · "
                    + AdvancedSchulte.statusText() + " · 点 i 看说明");
            advHint.setTextSize(10);
            advHint.setTextColor(LINK_COLOR);
            advHint.setGravity(Gravity.END);
            advHeader.addView(advHint, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            column.addView(advHeader);

            final View[] advChips = new View[ADV_LABELS.length];
            final TextView[] advInfos = new TextView[ADV_LABELS.length];
            for (int row = 0; row < (ADV_LABELS.length + 1) / 2; row++) {
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
            xLabel.setTextColor(dialogTextColor(act));
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

            // max≤N 的间隔上限（ms，仅 max≤N 显示；留空用内置参考值）
            final LinearLayout gapRow = new LinearLayout(act);
            gapRow.setOrientation(LinearLayout.HORIZONTAL);
            gapRow.setGravity(Gravity.CENTER_VERTICAL);
            gapRow.setPadding(Ui.dp(act, 3), Ui.dp(act, 4), Ui.dp(act, 3), 0);
            TextView gapLabel = new TextView(act);
            gapLabel.setText("间隔 N");
            gapLabel.setTextSize(12);
            gapLabel.setTextColor(dialogTextColor(act));
            gapRow.addView(gapLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final EditText gapInput = new EditText(act);
            gapInput.setSingleLine(true);
            gapInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            gapInput.setTextSize(14);
            gapInput.setTextColor(textColor);
            gapInput.setHintTextColor(tint | 0x66000000);
            gapInput.setPadding(Ui.dp(act, 8), 0, Ui.dp(act, 8), 0);
            GradientDrawable gapBg = new GradientDrawable();
            gapBg.setColor(tint | 0x1F000000);
            gapBg.setCornerRadius(Ui.dp(act, 4));
            gapInput.setBackground(gapBg);
            int savedGap = Prefs.advMaxGapMs(act);
            if (savedGap > 0) gapInput.setText(String.valueOf(savedGap));
            LinearLayout.LayoutParams gapInputLp = new LinearLayout.LayoutParams(
                    0, Ui.dp(act, 36), 1f);
            gapInputLp.leftMargin = Ui.dp(act, 8);
            gapRow.addView(gapInput, gapInputLp);
            TextView gapUnit = new TextView(act);
            gapUnit.setText("ms");
            gapUnit.setTextSize(12);
            gapUnit.setTextColor(dialogTextColor(act));
            gapUnit.setPadding(Ui.dp(act, 6), 0, 0, 0);
            gapRow.addView(gapUnit, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final LinearLayout gapWrap = new LinearLayout(act);
            gapWrap.setOrientation(LinearLayout.VERTICAL);
            gapWrap.addView(gapRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(gapWrap);

            // 1start 的开局反应上限（ms，仅 1start 显示；留空用内置 450ms）
            final LinearLayout startRow = new LinearLayout(act);
            startRow.setOrientation(LinearLayout.HORIZONTAL);
            startRow.setGravity(Gravity.CENTER_VERTICAL);
            startRow.setPadding(Ui.dp(act, 3), Ui.dp(act, 4), Ui.dp(act, 3), 0);
            TextView startLabel = new TextView(act);
            startLabel.setText("反应 ≤");
            startLabel.setTextSize(12);
            startLabel.setTextColor(dialogTextColor(act));
            startRow.addView(startLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final EditText startInput = new EditText(act);
            startInput.setSingleLine(true);
            startInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            startInput.setTextSize(14);
            startInput.setTextColor(textColor);
            startInput.setHintTextColor(tint | 0x66000000);
            startInput.setPadding(Ui.dp(act, 8), 0, Ui.dp(act, 8), 0);
            GradientDrawable startBg = new GradientDrawable();
            startBg.setColor(tint | 0x1F000000);
            startBg.setCornerRadius(Ui.dp(act, 4));
            startInput.setBackground(startBg);
            int savedStart1 = Prefs.advStart1Ms(act);
            if (savedStart1 > 0) startInput.setText(String.valueOf(savedStart1));
            LinearLayout.LayoutParams startInputLp = new LinearLayout.LayoutParams(
                    0, Ui.dp(act, 36), 1f);
            startInputLp.leftMargin = Ui.dp(act, 8);
            startRow.addView(startInput, startInputLp);
            TextView startUnit = new TextView(act);
            startUnit.setText("ms");
            startUnit.setTextSize(12);
            startUnit.setTextColor(dialogTextColor(act));
            startUnit.setPadding(Ui.dp(act, 6), 0, 0, 0);
            startRow.addView(startUnit, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final LinearLayout startWrap = new LinearLayout(act);
            startWrap.setOrientation(LinearLayout.VERTICAL);
            startWrap.addView(startRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(startWrap);

            // 区间模式：起点 / 终点
            final EditText rangeStartInput = numberInput(act, textColor);
            final EditText rangeEndInput = numberInput(act, textColor);
            final LinearLayout rangeWrap = new LinearLayout(act);
            rangeWrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout rangeRow1 = labelInputRow(act, "起点", rangeStartInput);
            LinearLayout rangeRow2 = labelInputRow(act, "终点", rangeEndInput);
            rangeWrap.addView(rangeRow1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rangeWrap.addView(rangeRow2, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(rangeWrap);

            final Runnable loadRange = new Runnable() {
                @Override
                public void run() {
                    int total = pickedLevel[0] * pickedLevel[0];
                    boolean rev = adv[0] == AdvancedSchulte.RANGE_REV;
                    int s = rev ? Prefs.advRangeRevStart(act) : Prefs.advRangeStart(act);
                    int e = rev ? Prefs.advRangeRevEnd(act) : Prefs.advRangeEnd(act);
                    rangeStartInput.setText(s <= 0 ? "" : String.valueOf(s));
                    rangeEndInput.setText(e <= 0 ? "" : String.valueOf(e));
                    rangeStartInput.setHint(rev ? "留空 = " + total : "留空 = 1");
                    rangeEndInput.setHint(rev ? "留空 = 1" : "留空 = " + total);
                }
            };

            final Runnable refreshAdv = new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < advChips.length; i++) {
                        styleCell(act, advChips[i], i == adv[0]);
                    }
                    xWrap.setVisibility(adv[0] == AdvancedSchulte.SPEED
                            ? View.VISIBLE : View.GONE);
                    gapWrap.setVisibility(adv[0] == AdvancedSchulte.MAXN
                            ? View.VISIBLE : View.GONE);
                    startWrap.setVisibility(adv[0] == AdvancedSchulte.START1
                            ? View.VISIBLE : View.GONE);
                    rangeWrap.setVisibility(adv[0] == AdvancedSchulte.RANGE
                            || adv[0] == AdvancedSchulte.RANGE_REV
                            ? View.VISIBLE : View.GONE);
                    int total = pickedLevel[0] * pickedLevel[0];
                    // 换阶数后目标 x 的上限跟着变：超了压回新上限并同步到输入框
                    int saved = Prefs.advX(act);
                    if (saved > total) {
                        Prefs.setAdvX(act, total);
                        xInput.setText(String.valueOf(total));
                    }
                    xInput.setHint("留空 = 全部 " + total);
                    gapInput.setHint("留空 = 内置 "
                            + AdvancedSchulte.fmt(AdvancedSchulte.builtinMaxGapMs(pickedLevel[0])));
                    startInput.setHint("留空 = 内置 " + AdvancedSchulte.builtinStart1Ms());
                    if (adv[0] == AdvancedSchulte.RANGE) {
                        rangeStartInput.setHint("留空 = 1");
                        rangeEndInput.setHint("留空 = " + total);
                    } else if (adv[0] == AdvancedSchulte.RANGE_REV) {
                        rangeStartInput.setHint("留空 = " + total);
                        rangeEndInput.setHint("留空 = 1");
                    }
                }
            };
            refreshAdv.run();
            // 打开弹窗时已经是区间模式：先把已保存的起点/终点填进去
            if (adv[0] == AdvancedSchulte.RANGE || adv[0] == AdvancedSchulte.RANGE_REV) {
                loadRange.run();
            }
            // 供阶数点击拦截使用：选中阶数暂存 + 刷新提示
            XposedHelpers.setAdditionalInstanceField(dlg, KEY_PICKED_LEVEL, pickedLevel);
            XposedHelpers.setAdditionalInstanceField(dlg, KEY_REFRESH_ADV, refreshAdv);

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
                    int max = Math.max(1, pickedLevel[0] * pickedLevel[0]);
                    Prefs.setAdvX(act, Math.min(v, max));
                }
            });

            gapInput.addTextChangedListener(new TextWatcher() {
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
                    Prefs.setAdvMaxGapMs(act, Math.min(Math.max(v, 0), 60000));
                }
            });

            startInput.addTextChangedListener(new TextWatcher() {
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
                    Prefs.setAdvStart1Ms(act, Math.min(Math.max(v, 0), 60000));
                }
            });

            for (int i = 0; i < advChips.length; i++) {
                final int idx = i;
                advChips[i].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (adv[0] == idx) return;   // 没有变化：留在弹窗继续改
                        adv[0] = idx;
                        refreshAdv.run();
                        if (idx == AdvancedSchulte.RANGE || idx == AdvancedSchulte.RANGE_REV) {
                            loadRange.run();
                        }
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
                        // 只暂存选择，点底部「确认」才真正切换模式 / 重建本局
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

            // ------------------------------------------------ 最底部「确认」
            // 区间模式的起点/终点是边输入边存的，这里记一份打开弹窗时的值，
            // 确认时若改过就重开本局，让新区间立刻生效
            final int[] rangeSnapshot = {
                    Prefs.advRangeStart(act), Prefs.advRangeEnd(act),
                    Prefs.advRangeRevStart(act), Prefs.advRangeRevEnd(act)};
            final Runnable applyAndClose = new Runnable() {
                @Override
                public void run() {
                    int level = pickedLevel[0];
                    int advMode = adv[0];
                    int total = level * level;
                    if (advMode == AdvancedSchulte.START1 && (level < 3 || level > 5)) {
                        Ui.toast(act, "1start 训练仅支持 3-5 阶");
                        return;
                    }
                    // 区间同样是「确认才生效」：输入框里的值在这里才写进 Prefs
                    int rs = parseOrZero(rangeStartInput.getText().toString());
                    int re = parseOrZero(rangeEndInput.getText().toString());
                    boolean rangeEdited = false;
                    if (advMode == AdvancedSchulte.RANGE) {
                        int s = rs <= 0 ? 1 : rs;
                        int e = re <= 0 ? total : re;
                        if (s < 1 || s >= e || e > total) {
                            Ui.toast(act, "顺序区间需满足 1 ≤ 起点 < 终点 ≤ " + total);
                            return;
                        }
                        rangeEdited = rs != rangeSnapshot[0] || re != rangeSnapshot[1];
                        Prefs.setAdvRangeStart(act, rs);
                        Prefs.setAdvRangeEnd(act, re);
                    } else if (advMode == AdvancedSchulte.RANGE_REV) {
                        int s = rs <= 0 ? total : rs;
                        int e = re <= 0 ? 1 : re;
                        if (s > total || s <= e || e < 1) {
                            Ui.toast(act, "倒序区间需满足 1 ≤ 终点 < 起点 ≤ " + total);
                            return;
                        }
                        rangeEdited = rs != rangeSnapshot[2] || re != rangeSnapshot[3];
                        Prefs.setAdvRangeRevStart(act, rs);
                        Prefs.setAdvRangeRevEnd(act, re);
                    }
                    if (advMode != Prefs.advMode(act)) {
                        Prefs.setAdvMode(act, advMode);
                        AdvancedSchulte.resetRound(act);
                        Ui.toast(act, "已切换为 " + AdvancedSchulte.NAMES[advMode]);
                    } else if (rangeEdited) {
                        AdvancedSchulte.resetRound(act);
                        Ui.toast(act, "区间已更新，已重开本局");
                    }
                    int type = MODES[picked[0]][0];
                    boolean blind = MODES[picked[0]][1] == 1;
                    apply(act, (Dialog) dlg, applied, type, blind, level, refreshAdv);
                    try {
                        if (((Dialog) dlg).isShowing()) ((Dialog) dlg).dismiss();
                    } catch (Throwable ignored) {
                    }
                }
            };

            View confirm = (View) newInstance(btnCls, new Class<?>[]{Context.class}, act);
            XposedHelpers.callMethod(confirm, "setRadius", Ui.dp(act, 6));
            XposedHelpers.callMethod(confirm, "setText", "确认");
            centerText(act, confirm);
            styleCell(act, confirm, true);
            confirm.setClickable(true);
            confirm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyAndClose.run();
                }
            });
            // extra_layout 是 FrameLayout，多个子 View 会互相叠住：
            // 文字按钮与「确认」合成一个竖向容器，一次加进去
            LinearLayout bottom = new LinearLayout(act);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.addView(footer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 46));
            confirmLp.topMargin = Ui.dp(act, 2);
            bottom.addView(confirm, confirmLp);
            callDialogMethod(dlg, "m", new Class<?>[]{View.class, ViewGroup.LayoutParams.class},
                    bottom, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Object callback = Proxy.newProxyInstance(cl, new Class<?>[]{cbCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (args != null && args.length == 1 && args[0] instanceof Integer) {
                                pickedLevel[0] = (Integer) args[0];
                                refreshAdv.run();
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
     * 官方阶数格子点击（z3.o(c,View,int)）第一句就是 dismiss()，
     * 这里只拦「本模块创建的弹窗」：点击不关窗，只把阶数暂存下来等「确认」。
     */
    private static void installItemClickHook(final ClassLoader cl) {
        if (itemClickHooked) return;
        try {
            Class<?> adapterCls = XposedHelpers.findClass("com.chad.library.b.a.c", cl);
            XposedHelpers.findAndHookMethod(DIALOG, cl, "o", adapterCls, View.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object dlg = param.thisObject;
                            Object picked = XposedHelpers.getAdditionalInstanceField(
                                    dlg, KEY_PICKED_LEVEL);
                            if (!(picked instanceof int[])) return;   // 不是本模块弹窗，走官方逻辑
                            param.setResult(null);                    // 不让弹窗自己 dismiss
                            try {
                                Object adapter = param.args[0];
                                Object level = XposedHelpers.callMethod(adapter, "q0",
                                        (Integer) param.args[2]);
                                if (level instanceof Integer) {
                                    ((int[]) picked)[0] = (Integer) level;
                                    // 官方格子选中态来自 adapter 的 k0 集合，点一下就把
                                    // 集合换成新阶数并刷新列表，否则高亮还停在旧阶数上
                                    refreshAdapterSelection(adapter, (Integer) level);
                                    Object refresh = XposedHelpers.getAdditionalInstanceField(
                                            dlg, KEY_REFRESH_ADV);
                                    if (refresh instanceof Runnable) ((Runnable) refresh).run();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            itemClickHooked = true;
        } catch (Throwable t) {
            MainHook.log("模式弹窗阶数点击拦截失败: " + t);
        }
    }

    /**
     * 官方阶数格子的选中态来自 adapter 的私有集合 k0：
     * 绑定时按 setSelected(k0.contains(item)) 画高亮。只改暂存阶数而不动这个集合，
     * 格子就会一直高亮在旧阶数上，所以这里把集合换成新阶数并让列表重新绑定。
     */
    private static void refreshAdapterSelection(Object adapter, int level) {
        try {
            Object selected = XposedHelpers.getObjectField(adapter, "k0");
            if (!(selected instanceof Set)) return;
            @SuppressWarnings("unchecked")
            Set<Integer> set = (Set<Integer>) selected;
            set.clear();
            set.add(level);
            XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
        } catch (Throwable t) {
            MainHook.log("阶数选中态刷新失败: " + t);
        }
    }

    /** 输入框内容转数字：空 / 非法一律当 0（0 表示用默认区间） */
    private static int parseOrZero(String s) {
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 数字输入框：与「目标 x」「间隔 N」同款样式 */
    private static EditText numberInput(Activity act, int textColor) {
        int tint = textColor & 0x00FFFFFF;
        EditText input = new EditText(act);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextSize(14);
        input.setTextColor(textColor);
        input.setHintTextColor(tint | 0x66000000);
        input.setPadding(Ui.dp(act, 8), 0, Ui.dp(act, 8), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(tint | 0x1F000000);
        bg.setCornerRadius(Ui.dp(act, 4));
        input.setBackground(bg);
        return input;
    }

    /** 「标签 + 数字输入」一行，给区间的起点/终点用 */
    private static LinearLayout labelInputRow(Activity act, String label, EditText input) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 3), Ui.dp(act, 4), Ui.dp(act, 3), 0);
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(dialogTextColor(act));
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, Ui.dp(act, 36), 1f);
        inputLp.leftMargin = Ui.dp(act, 8);
        row.addView(input, inputLp);
        return row;
    }

    private static TextView sectionLabel(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(dialogTextColor(act));
        tv.getPaint().setFakeBoldText(true);
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
    static int dialogTextColor(Activity act) {
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

    /** 高级模式说明：与模块设置页同款排版（深色正文、蓝色副标题、可滚动） */
    private static void showDesc(Activity act, int idx) {
        try {
            int textColor = dialogTextColor(act);
            LinearLayout content = new LinearLayout(act);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(Ui.dp(act, 22), Ui.dp(act, 6), Ui.dp(act, 22), Ui.dp(act, 4));

            TextView tag = new TextView(act);
            tag.setText("高级模式 · 成绩仅本地记录，不上传");
            tag.setTextSize(11);
            tag.setTextColor(LINK_COLOR);
            content.addView(tag);

            TextView body = new TextView(act);
            body.setText(AdvancedSchulte.DESCS[idx]);
            body.setTextSize(14);
            body.setTextColor(textColor);
            body.setLineSpacing(0, 1.35f);
            body.setPadding(0, Ui.dp(act, 10), 0, 0);
            content.addView(body);

            ScrollView sv = new ScrollView(act);
            sv.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            new AlertDialog.Builder(act)
                    .setTitle(AdvancedSchulte.NAMES[idx])
                    .setView(sv)
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
                // W() 只改棋盘，不会回写 Activity 的阶数字段；不写会导致下次打开弹窗仍显示旧阶数
                XposedHelpers.setIntField(act, "c", level);
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
        // 以棋盘当前的真实阶数为准：W(int) 切阶数时只改 core.b，不会回写 Activity.c
        try {
            Object game = XposedHelpers.getObjectField(act, "g");
            if (game != null) {
                Object l = XposedHelpers.callMethod(game, "f");
                if (l instanceof Integer && (Integer) l >= 3 && (Integer) l <= 10) {
                    return (Integer) l;
                }
            }
        } catch (Throwable ignored) {
        }
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
