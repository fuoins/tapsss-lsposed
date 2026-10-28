package com.tapsss.lsposed;

import android.app.Activity;
import android.view.View;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在「设置」页的「全部游戏」网格里放一个「模块设置」入口（只放一个）。
 *
 * 3.6.10 的 SettingHomeActivity 有两个 5 列网格：
 *   字段 b → $b 适配器，item 带 isActive() 开关状态（全部游戏）
 *   字段 c → $a 适配器，点击走 ActionRouter（快速开始）
 * 入口放在 b 里，避免污染「快速开始」。
 */
public final class SettingsHooks {

    private static final String HOME = "com.star.minesweeping.ui.activity.setting.SettingHomeActivity";
    private static final String GRID_ADAPTER = HOME + "$b";
    private static final String QUICK_ADAPTER = HOME + "$a";
    private static final String BASE_ADAPTER = "com.chad.library.b.a.c";
    private static final String GAME_MENU = "com.star.minesweeping.data.bean.item.GameMenu";
    private static final String ROUTE = "/module/settings";
    private static final String TEXT = "模块设置";

    private SettingsHooks() {
    }

    static void install(ClassLoader cl) {
        final Class<?> homeCls = XposedHelpers.findClassIfExists(HOME, cl);
        final Class<?> menuCls = XposedHelpers.findClassIfExists(GAME_MENU, cl);
        final Class<?> adapterCls = XposedHelpers.findClassIfExists(BASE_ADAPTER, cl);
        if (homeCls == null || menuCls == null || adapterCls == null) {
            MainHook.log("设置页入口未安装：类缺失");
            return;
        }

        XC_MethodHook injector = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    inject((Activity) param.thisObject, menuCls);
                } catch (Throwable t) {
                    MainHook.log("注入模块设置入口失败: " + t);
                }
            }
        };
        XposedBridge.hookAllMethods(homeCls, "init", injector);
        XposedBridge.hookAllMethods(homeCls, "I", injector);

        hookClick(cl, GRID_ADAPTER, adapterCls);
        hookClick(cl, QUICK_ADAPTER, adapterCls);
    }

    private static void hookClick(ClassLoader cl, String clsName, Class<?> adapterCls) {
        Class<?> cls = XposedHelpers.findClassIfExists(clsName, cl);
        if (cls == null) return;
        try {
            XposedHelpers.findAndHookMethod(cls, "p", adapterCls, View.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object item = XposedHelpers.callMethod(param.args[0], "q0", param.args[2]);
                                Object route = XposedHelpers.callMethod(item, "getRoute");
                                if (ROUTE.equals(route)) {
                                    param.setResult(null);
                                    Activity act = Ui.findActivity(((View) param.args[1]).getContext());
                                    if (act != null) ModuleSettings.show(act);
                                }
                            } catch (Throwable t) {
                                MainHook.log("模块设置点击处理失败: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("hook " + clsName + " 失败: " + t);
        }
    }

    private static void inject(Activity act, Class<?> menuCls) {
        if (act == null) return;
        Object adapter = XposedHelpers.getObjectField(act, "b");
        if (adapter == null) return;
        List<?> data = (List<?>) XposedHelpers.callMethod(adapter, "getData");
        if (data == null) return;
        for (Object o : data) {
            if (ROUTE.equals(XposedHelpers.callMethod(o, "getRoute"))) return;
        }
        // 复用已有项的颜色资源名，避免文字颜色解析失败
        Object colorId = "";
        if (!data.isEmpty()) {
            Object c = XposedHelpers.callMethod(data.get(0), "getColorID");
            if (c instanceof String) colorId = c;
        }
        Object menu = XposedHelpers.newInstance(menuCls, "ic_setting", TEXT, colorId, Boolean.TRUE);
        XposedHelpers.callMethod(menu, "setRoute", ROUTE);
        XposedHelpers.callMethod(adapter, "y", menu);
        MainHook.log("已注入模块设置入口");
    }
}
