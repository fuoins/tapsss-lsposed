package com.tapsss.lsposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import de.robv.android.xposed.XposedHelpers;

/**
 * 把游戏右上角的「阶数选择」弹窗替换为「模式 + 阶数」二合一弹窗。
 *
 * 模式与 Intent 参数映射（与 SchulteActivity 一致）：
 *   简单(1,false) 简单盲打(1,true) 普通(0,false) 普通盲打(0,true) 混合简单(2,false) 混合(3,false)
 * 切换模式 = 带 type/blind/level extras 重启 SchulteGameActivity（ARouter 注入会读取这些 extras）。
 * 只切换阶数 = 调用原 Activity 的 W(int)，与原版阶数弹窗行为一致。
 */
public final class SchulteModeDialog {

    private static final String[] MODE_NAMES = {
            "简单", "简单·盲打", "普通", "普通·盲打", "混合·简单", "混合"
    };
    private static final int[] MODE_TYPE = {1, 1, 0, 0, 2, 3};
    private static final boolean[] MODE_BLIND = {false, true, false, true, false, false};
    private static final int MIN_LEVEL = 3;
    private static final int MAX_LEVEL = 10;
    private static final int MODE_ID_BASE = 100;

    private SchulteModeDialog() {
    }

    public static void show(final Activity act) {
        if (!Ui.isAlive(act)) return;

        int curType = getInt(act, "a", 0);
        boolean curBlind = getBoolean(act, "b", false);
        int curLevel = getInt(act, "c", 5);
        if (curLevel < MIN_LEVEL || curLevel > MAX_LEVEL) {
            curLevel = readGameLevel(act, curLevel);
        }

        final Context c = act;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 20);
        root.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 4));

        root.addView(label(c, "模式"));
        final RadioGroup modes = new RadioGroup(c);
        modes.setOrientation(RadioGroup.VERTICAL);
        int checked = 2; // 默认普通
        for (int i = 0; i < MODE_NAMES.length; i++) {
            RadioButton rb = new RadioButton(c);
            rb.setId(MODE_ID_BASE + i);
            rb.setText(MODE_NAMES[i]);
            rb.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
            modes.addView(rb);
            if (MODE_TYPE[i] == curType && MODE_BLIND[i] == curBlind) checked = i;
        }
        modes.check(MODE_ID_BASE + checked);
        root.addView(modes);

        root.addView(label(c, "阶数（3 - 10）"));
        final Spinner levelSpinner = new Spinner(c);
        String[] levels = new String[MAX_LEVEL - MIN_LEVEL + 1];
        for (int i = 0; i < levels.length; i++) levels[i] = String.valueOf(MIN_LEVEL + i);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                c, android.R.layout.simple_spinner_item, levels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        levelSpinner.setAdapter(adapter);
        levelSpinner.setSelection(curLevel - MIN_LEVEL);
        root.addView(levelSpinner);

        TextView hint = label(c, "切换模式会重新开局；只改阶数会直接重开本局。");
        hint.setTextSize(12);
        hint.setAlpha(0.75f);
        root.addView(hint);

        ScrollView sv = new ScrollView(c);
        sv.addView(root);

        final int type = curType;
        final boolean blind = curBlind;
        final int level = curLevel;

        new AlertDialog.Builder(act)
                .setTitle("模式与阶数")
                .setView(sv)
                .setPositiveButton("应用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            apply(act, modes, levelSpinner, type, blind, level);
                        } catch (Throwable t) {
                            MainHook.log("应用模式/阶数失败: " + t);
                            Ui.toast(act, "切换失败：" + t);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void apply(Activity act, RadioGroup modes, Spinner levelSpinner,
                              int oldType, boolean oldBlind, int oldLevel) {
        int id = modes.getCheckedRadioButtonId();
        int index = id >= MODE_ID_BASE ? id - MODE_ID_BASE : -1;
        if (index < 0 || index >= MODE_NAMES.length) {
            Ui.toast(act, "请选择模式");
            return;
        }
        int newType = MODE_TYPE[index];
        boolean newBlind = MODE_BLIND[index];
        int newLevel = MIN_LEVEL + levelSpinner.getSelectedItemPosition();

        if (newType == oldType && newBlind == oldBlind && newLevel == oldLevel) {
            Ui.toast(act, "模式与阶数没有变化");
            return;
        }

        if (isBusy(act)) {
            Ui.toast(act, "游戏进行中，请先结束本局再切换");
            return;
        }

        if (newType != oldType || newBlind != oldBlind) {
            restart(act, newType, newBlind, newLevel);
            return;
        }

        // 只改阶数：优先走原版逻辑
        try {
            XposedHelpers.callMethod(act, "W", newLevel);
            Ui.toast(act, "已切换为 " + newLevel + "×" + newLevel);
        } catch (Throwable t) {
            restart(act, newType, newBlind, newLevel);
        }
    }

    private static boolean isBusy(Activity act) {
        try {
            Object busy = XposedHelpers.callMethod(act, "M");
            return Boolean.TRUE.equals(busy);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void restart(Activity act, int type, boolean blind, int level) {
        Intent intent = new Intent(act, act.getClass());
        intent.putExtra("type", type);
        intent.putExtra("blind", blind);
        intent.putExtra("level", level);
        act.startActivity(intent);
        act.finish();
    }

    private static int readGameLevel(Activity act, int fallback) {
        try {
            Object game = XposedHelpers.getObjectField(act, "g");
            if (game != null) {
                Object size = XposedHelpers.callMethod(game, "f");
                if (size instanceof Integer) {
                    int s = (Integer) size;
                    if (s >= MIN_LEVEL && s <= MAX_LEVEL) return s;
                }
            }
        } catch (Throwable ignored) {
        }
        if (fallback >= MIN_LEVEL && fallback <= MAX_LEVEL) return fallback;
        return 5;
    }

    private static int getInt(Object target, String field, int def) {
        try {
            return XposedHelpers.getIntField(target, field);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean getBoolean(Object target, String field, boolean def) {
        try {
            return XposedHelpers.getBooleanField(target, field);
        } catch (Throwable t) {
            return def;
        }
    }

    private static TextView label(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setPadding(0, Ui.dp(c, 8), 0, 0);
        return tv;
    }
}
