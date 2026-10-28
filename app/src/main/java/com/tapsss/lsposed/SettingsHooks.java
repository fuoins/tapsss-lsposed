package com.tapsss.lsposed;

import android.app.Activity;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在「设置」页右上角 ActionBar 增加一个独立的「模块设置」按钮。
 *
 * 3.6.10 的 SettingHomeActivity 只有两个 RecyclerView（快速开始网格 + 全部游戏网格），
 * 没有设置项列表；因此入口放在 ActionBar 上，避免混进「快速开始」里。
 * ActionBar#c(position, resId, listener) 用于向 ActionBar 追加图标按钮。
 */
public final class SettingsHooks {

    private static final String HOME = "com.star.minesweeping.ui.activity.setting.SettingHomeActivity";
    private static final String ACTION_BAR = "com.star.minesweeping.ui.view.ActionBar";
    private static final String FIELD_ADDED = "tapsss_setting_button_added";

    /** mipmap/ic_setting 在 3.6.10 中的固定 ID，资源名解析失败时兜底 */
    private static final int FALLBACK_SETTING_ICON = 0x7f0e00e1;

    private SettingsHooks() {
    }

    static void install(ClassLoader cl) {
        final Class<?> homeCls = XposedHelpers.findClassIfExists(HOME, cl);
        final Class<?> barCls = XposedHelpers.findClassIfExists(ACTION_BAR, cl);
        if (homeCls == null || barCls == null) return;

        XposedBridge.hookAllMethods(homeCls, "initActionBar", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    addSettingsButton((Activity) param.thisObject, param.args[0]);
                } catch (Throwable t) {
                    MainHook.log("添加模块设置按钮失败: " + t);
                }
            }
        });
    }

    private static void addSettingsButton(final Activity act, Object bar) {
        if (act == null || bar == null) return;
        if (XposedHelpers.getAdditionalInstanceField(act, FIELD_ADDED) != null) return;

        int icon = Ui.resId(act, "ic_setting", "mipmap");
        if (icon == 0) icon = FALLBACK_SETTING_ICON;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModuleSettings.show(act);
            }
        };
        // position=1 与页面原有的「添加」按钮同一组，会并排显示
        XposedHelpers.callMethod(bar, "c", 1, icon, listener);
        XposedHelpers.setAdditionalInstanceField(act, FIELD_ADDED, Boolean.TRUE);
        MainHook.log("已在设置页 ActionBar 添加模块设置按钮");
    }
}
