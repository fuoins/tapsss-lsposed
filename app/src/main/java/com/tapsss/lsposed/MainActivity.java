package com.tapsss.lsposed;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** 模块自身的说明页（模块配置入口在扫雷联萌 App 的「设置」里）。 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        int p = Ui.dp(this, 24);
        tv.setPadding(p, p, p, p);
        tv.setTextSize(16);
        tv.setLineSpacing(0, 1.35f);
        tv.setText("扫雷联萌增强 v" + MainHook.VERSION + "\n\n"
                + "这是一个 LSPosed 模块，不是独立应用。\n\n"
                + "使用步骤：\n"
                + "1. 在 LSPosed 管理器中启用本模块；\n"
                + "2. 作用域勾选「扫雷联萌」（com.star.minesweeping）；\n"
                + "3. 强制停止并重新打开扫雷联萌；\n"
                + "4. 进入 App 的「设置」，点击新增的「模块设置」。\n\n"
                + "功能：\n"
                + "· 舒尔特方格：开始倒计时（下限 1000 毫秒）、倒计时期间点击不开始、"
                + "右上角阶数弹窗中加入模式切换；\n"
                + "· 账号与登录：多设备登录掉线后自动重登（默认开启）。\n\n"
                + "适用版本：扫雷联萌 3.6.x（基于 3.6.10 适配）。");
        sv.addView(tv);
        setContentView(sv);
    }
}
