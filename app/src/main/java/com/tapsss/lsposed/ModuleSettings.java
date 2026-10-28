package com.tapsss.lsposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 模块设置界面：一级为游戏列表，二级为各游戏/账号选项。 */
public final class ModuleSettings {

    private ModuleSettings() {
    }

    public static void show(final Activity act) {
        if (!Ui.isAlive(act)) return;
        final String[] items = {"舒尔特方格", "账号与登录（多设备掉线自动重登）", "关于"};
        try {
            new AlertDialog.Builder(act)
                    .setTitle("扫雷联萌增强")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) showSchulte(act);
                            else if (which == 1) showAccount(act);
                            else showAbout(act);
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            Ui.toast(act, "打开模块设置失败：" + t);
        }
    }

    private static void showSchulte(final Activity act) {
        final Context c = act;
        LinearLayout root = vertical(c);
        root.addView(hint(c, "修改后重新进入游戏生效"));

        final CheckBox cbEnable = check(c, "启用舒尔特增强", Prefs.schulteEnabled(c));
        final CheckBox cbCount = check(c, "开始倒计时（关闭后点击立即开始）", Prefs.countdownEnabled(c));
        final CheckBox cbBlock = check(c, "倒计时期间点击不开始", Prefs.blockTouch(c));
        final CheckBox cbMode = check(c, "右上角阶数弹窗加入模式切换", Prefs.modeSwitch(c));
        root.addView(cbEnable);
        root.addView(cbCount);
        root.addView(cbBlock);
        root.addView(cbMode);

        root.addView(hint(c, "倒计时时长（毫秒，下限 1000）"));
        final EditText et = new EditText(c);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        et.setText(String.valueOf(Prefs.countdownMs(c)));
        et.setSelectAllOnFocus(true);
        root.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        showDialog(act, "舒尔特方格", root, "保存", new Runnable() {
            @Override
            public void run() {
                Prefs.setBool(c, Prefs.SCHULTE_ENABLED, cbEnable.isChecked());
                Prefs.setBool(c, Prefs.COUNTDOWN_ENABLED, cbCount.isChecked());
                Prefs.setBool(c, Prefs.BLOCK_TOUCH, cbBlock.isChecked());
                Prefs.setBool(c, Prefs.MODE_SWITCH, cbMode.isChecked());

                int ms;
                try {
                    ms = Integer.parseInt(et.getText().toString().trim());
                } catch (Throwable t) {
                    ms = 3000;
                }
                int clamped = Prefs.clampMs(ms);
                Prefs.setInt(c, Prefs.COUNTDOWN_MS, clamped);
                if (clamped != ms) {
                    Ui.toast(c, "倒计时时长已调整为 " + clamped + " 毫秒（下限 1000）");
                } else {
                    Ui.toast(c, "已保存");
                }
            }
        });
    }

    private static void showAccount(final Activity act) {
        final Context c = act;
        LinearLayout root = vertical(c);
        root.addView(hint(c, "当同一账号在另一台设备登录后，本机会回到登录页；开启后自动用保存的账号密码重登。"));

        final CheckBox cbAuto = check(c, "多设备掉线自动重登（默认开启）", Prefs.autoRelogin(c));
        root.addView(cbAuto);

        final String uid = Prefs.savedUid(c);
        final TextView info = new TextView(c);
        info.setText(uid.isEmpty() ? "当前未保存账号密码" : "已保存账号：" + mask(uid));
        info.setPadding(0, Ui.dp(c, 8), 0, 0);
        root.addView(info);

        Button clear = new Button(c);
        clear.setText("清除已保存的账号与密码");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.clearCredentials(c);
                info.setText("当前未保存账号密码");
                Ui.toast(c, "已清除保存的账号密码");
            }
        });
        root.addView(clear);

        showDialog(act, "账号与登录", root, "保存", new Runnable() {
            @Override
            public void run() {
                Prefs.setBool(c, Prefs.AUTO_RELOGIN, cbAuto.isChecked());
                Ui.toast(c, "已保存");
            }
        });
    }

    private static void showAbout(final Activity act) {
        LinearLayout root = vertical(act);
        TextView tv = new TextView(act);
        tv.setText("扫雷联萌增强 v1.0.0\n\n"
                + "· 舒尔特：开始倒计时（下限 1000ms）、倒计时期间点击不开始、"
                + "右上角阶数弹窗内可直接切换模式与阶数\n"
                + "· 账号：多设备登录掉线后自动重登（默认开启）\n\n"
                + "适用版本：扫雷联萌 3.6.x\n"
                + "使用方式：在 LSPosed 中启用模块，作用域勾选「扫雷联萌」，"
                + "然后进入 App 的「设置」→「模块设置」。");
        tv.setTextSize(14);
        tv.setLineSpacing(0, 1.25f);
        root.addView(tv);
        new AlertDialog.Builder(act)
                .setTitle("关于")
                .setView(wrap(act, root))
                .setPositiveButton("知道了", null)
                .show();
    }

    private static void showDialog(Activity act, String title, LinearLayout content,
                                   String positive, final Runnable onSave) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(wrap(act, content))
                .setPositiveButton(positive, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            onSave.run();
                        } catch (Throwable t) {
                            MainHook.log("保存设置失败: " + t);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static View wrap(Context c, LinearLayout content) {
        ScrollView sv = new ScrollView(c);
        sv.addView(content);
        return sv;
    }

    private static LinearLayout vertical(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 20);
        ll.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 4));
        return ll;
    }

    private static CheckBox check(Context c, String text, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(text);
        cb.setChecked(checked);
        cb.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
        return cb;
    }

    private static TextView hint(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setAlpha(0.75f);
        tv.setPadding(0, 0, 0, Ui.dp(c, 4));
        return tv;
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 5) return s;
        return s.substring(0, 3) + "****" + s.substring(s.length() - 2);
    }
}
