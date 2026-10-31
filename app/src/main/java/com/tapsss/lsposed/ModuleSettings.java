package com.tapsss.lsposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 模块设置界面：一级为游戏列表，二级为各游戏/账号选项。 */
public final class ModuleSettings {

    /** 「已启用」等强调色，与模式弹窗的链接色一致 */
    private static final int ACCENT = 0xFF4C8DFF;

    private ModuleSettings() {
    }

    public static void show(final Activity act) {
        if (!Ui.isAlive(act)) return;
        final String[] items = {
                "舒尔特方格", "2048", "账号与登录（多设备掉线自动重登）", "关于"};
        try {
            new AlertDialog.Builder(act)
                    .setTitle("扫雷联萌增强")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) showSchulte(act);
                            else if (which == 1) showTZFE(act);
                            else if (which == 2) showAccount(act);
                            else showAbout(act);
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            Ui.toast(act, "打开模块设置失败：" + t);
        }
    }

    static void showSchulte(final Activity act) {
        final Context c = act;
        LinearLayout root = vertical(c);
        root.addView(hint(act, "以下为本模块新增的功能，App 原有设置（如开始倒计时）保持不变"));

        final CheckBox cbBlock = check(act, "取消倒计时期间点击会立即开始", Prefs.blockTouch(c));
        root.addView(cbBlock);
        root.addView(hint(act, "勾选后：倒计时期间点击棋盘不会跳过倒计时"));

        final CheckBox cbAutoNext = check(act,
                "非新纪录不展示成绩，自动下一把", Prefs.autoNextNoRecord(c));
        root.addView(cbAutoNext);
        root.addView(hint(act, "勾选后：高级模式每局结束若没有刷新纪录（含失败/点错），不展示成绩直接下一把"));
        root.addView(hint(act, "关掉后：每局结束都会显示成绩或失败原因"));
        root.addView(hint(act, "注意：某个模式/阶数/目标 x 的第一次完成一定算新纪录，会正常显示"));

        root.addView(feature(act,
                "高级模式（顺序/爆手速/倒序/归中/max≤N/1start/顺序区间/倒序区间，"
                        + "成绩仅本地记录，不上传）"));
        root.addView(feature(act, "右上角阶数弹窗增强"));
        root.addView(feature(act, "倒计时取消下限限制"));
        root.addView(feature(act, "重开预设"));
        root.addView(hint(act, "一键预设入口：App 设置 → 舒尔特方格 → 重开，页面顶部的「重开预设」按钮"
                + "（含「错一个就重开」：一次把所有模式、所有阶数的错误重开设为 1）"));

        showDialog(act, "舒尔特方格", root, "保存", new Runnable() {
            @Override
            public void run() {
                Prefs.setBool(c, Prefs.BLOCK_TOUCH, cbBlock.isChecked());
                Prefs.setBool(c, Prefs.AUTO_NEXT_NO_RECORD, cbAutoNext.isChecked());
                Ui.toast(c, "已保存");
            }
        });
    }

    static void showTZFE(final Activity act) {
        final Context c = act;
        LinearLayout root = vertical(c);
        root.addView(hint(act, "以下功能在 2048 中默认生效，无需额外开关；App 原有设置保持不变"));

        root.addView(feature(act, "棋盘 3×3 – 10×10，目标分数 = 2^(3×短边−1)"));
        root.addView(feature(act, "自定义棋盘：行、列均可选 3–10"));
        root.addView(feature(act, "对局内「撤销」：回退上一步，本局成绩不上传"));
        root.addView(feature(act, "成绩面板按新棋盘尺寸适配"));
        root.addView(feature(act, "排行榜：尺寸主标签 + 时间/分数子标签"));
        root.addView(hint(act, "目标分数示例：3×3=256，4×4=2048，5×5=16384，"
                + "之后每增加一阶 ×8，10×10=536870912"));
        root.addView(hint(act, "难度弹窗底部的「2048设置」可打开官方 2048 设置页（倒计时、音效等）"));

        Button open = new Button(c);
        open.setText("打开官方 2048 设置");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TZFEHooks.openOfficialSetting(act);
            }
        });
        root.addView(open);

        new AlertDialog.Builder(act)
                .setTitle("2048")
                .setView(wrap(act, root))
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 无开关的功能项：左侧功能名（深色正文），右侧蓝色胶囊「已启用」 */
    private static View feature(Activity act, String label) {
        Context c = act;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));

        TextView name = new TextView(c);
        name.setText(label);
        name.setTextSize(16);
        name.setTextColor(SchulteModeDialog.dialogTextColor(act));
        row.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = new TextView(c);
        state.setText("已启用");
        state.setTextSize(12);
        state.setTextColor(ACCENT);
        state.getPaint().setFakeBoldText(true);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x1A4C8DFF);
        pill.setCornerRadius(Ui.dp(c, 10));
        state.setBackground(pill);
        state.setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3));
        row.addView(state, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    static void showAccount(final Activity act) {
        final Context c = act;
        LinearLayout root = vertical(c);
        root.addView(hint(act, "当同一账号在另一台设备登录后，本机会回到登录页；开启后自动用保存的账号密码重登。"));

        final CheckBox cbAuto = check(act, "多设备掉线自动重登（默认开启）", Prefs.autoRelogin(c));
        root.addView(cbAuto);

        final String uid = Prefs.savedUid(c);
        final TextView info = new TextView(c);
        info.setText(uid.isEmpty() ? "当前未保存账号密码" : "已保存账号：" + mask(uid));
        info.setTextColor(SchulteModeDialog.dialogTextColor(act));
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
        tv.setText("扫雷联萌增强 v" + MainHook.VERSION + "\n\n"
                + "· 舒尔特功能：倒计时期间点击不直接开始（可关闭）、"
                + "右上角阶数弹窗内直接切换模式与高级模式、倒计时时长下限取消（可设为 0）、重开预设\n"
                + "· 高级模式：顺序 / 爆手速（可设目标 x）/ 倒序 / 归中 / max≤N / 1start / "
                + "顺序区间 / 倒序区间，"
                + "成绩只在本地记录、不上传；每局结束有独立效果，可开启「非新纪录不展示成绩，自动下一把」\n"
                + "· 2048：棋盘 3×3 – 10×10 与自定义 (3–10)×(3–10)，目标分数 = 2^(3×短边−1)；"
                + "对局内可「撤销」，用过撤销的这局成绩不上传；排行榜按尺寸分组展示\n"
                + "· 账号：多设备登录掉线后自动重登（默认开启）\n\n"
                + "模式弹窗里「高级模式」右侧会显示 v" + MainHook.VERSION + " 与接管状态；\n"
                + "若显示「未接管」，请在 LSPosed 里确认作用域勾选「扫雷联萌」，"
                + "安装后强制停止并重新打开游戏。\n\n"
                + "适用版本：扫雷联萌 3.6.x\n"
                + "使用方式：在 LSPosed 中启用模块，作用域勾选「扫雷联萌」，"
                + "然后进入 App 的「设置」→「模块设置」。");
        tv.setTextSize(14);
        tv.setTextColor(SchulteModeDialog.dialogTextColor(act));
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

    private static CheckBox check(Activity act, String text, boolean checked) {
        CheckBox cb = new CheckBox(act);
        cb.setText(text);
        cb.setChecked(checked);
        cb.setTextColor(SchulteModeDialog.dialogTextColor(act));
        cb.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6));
        return cb;
    }

    /** 说明文字：不用灰色弱化，统一用弹窗正文的深色（深色主题下自动转浅） */
    private static TextView hint(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(SchulteModeDialog.dialogTextColor(act));
        tv.setPadding(0, 0, 0, Ui.dp(act, 4));
        return tv;
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 5) return s;
        return s.substring(0, 3) + "****" + s.substring(s.length() - 2);
    }
}
