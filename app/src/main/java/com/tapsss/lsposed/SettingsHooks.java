package com.tapsss.lsposed;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在「设置」页注入「模块设置」入口，并拦截点击打开模块设置面板。
 *
 * 目标结构（3.6.10）：
 *   SettingHomeActivity.init() 先创建设置列表 adapter（字段 c，类型 SettingHomeActivity$a），
 *   最后调用 I() -> adapter.setNewData(utils.h.c())。
 *   adapter.p(adapter, view, position) 点击后调用 ActionRouter.go(item.getRoute())。
 */
public final class SettingsHooks {

    public static final String ROUTE = "/module/settings";

    private static final String HOME = "com.star.minesweeping.ui.activity.setting.SettingHomeActivity";
    private static final String ADAPTER = HOME + "$a";
    private static final String MENU = "com.star.minesweeping.data.bean.item.GameMenu";
    private static final String MENU_HELPER = "com.star.minesweeping.utils.h";

    private SettingsHooks() {
    }

    static void install(final ClassLoader cl) {
        Class<?> adapterCls = XposedHelpers.findClassIfExists(ADAPTER, cl);
        Class<?> homeCls = XposedHelpers.findClassIfExists(HOME, cl);

        if (adapterCls != null && homeCls != null) {
            XC_MethodHook inject = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        injectIntoAdapter(param.thisObject, cl);
                    } catch (Throwable t) {
                        MainHook.log("注入模块设置入口失败: " + t);
                    }
                }
            };
            XposedBridge.hookAllMethods(homeCls, "init", inject);
            XposedBridge.hookAllMethods(homeCls, "I", inject);

            XposedBridge.hookAllMethods(adapterCls, "p", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object item = XposedHelpers.callMethod(
                                param.thisObject, "q0", ((Integer) param.args[2]).intValue());
                        String route = (String) XposedHelpers.callMethod(item, "getRoute");
                        if (!ROUTE.equals(route)) return;

                        param.setResult(null);
                        Object v = param.args[1];
                        Context ctx = v instanceof View ? ((View) v).getContext() : null;
                        Activity act = ctx == null ? null : Ui.findActivity(ctx);
                        if (act != null) ModuleSettings.show(act);
                    } catch (Throwable t) {
                        MainHook.log("模块设置入口点击失败: " + t);
                    }
                }
            });
            return;
        }

        // 兜底：如果设置页 adapter 结构变化，直接把入口追加进设置列表数据源
        Class<?> helper = XposedHelpers.findClassIfExists(MENU_HELPER, cl);
        if (helper != null) {
            XposedBridge.hookAllMethods(helper, "c", new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof List)) return;
                        List<Object> list = (List<Object>) result;
                        for (Object o : list) {
                            if (ROUTE.equals(XposedHelpers.callMethod(o, "getRoute"))) return;
                        }
                        list.add(newMenu(cl));
                    } catch (Throwable t) {
                        MainHook.log("设置列表追加入口失败: " + t);
                    }
                }
            });
        }
    }

    private static void injectIntoAdapter(Object activity, ClassLoader cl) {
        Object adapter = XposedHelpers.getObjectField(activity, "c");
        if (adapter == null) return;
        Object data = XposedHelpers.callMethod(adapter, "getData");
        if (data instanceof List) {
            for (Object o : (List<?>) data) {
                try {
                    if (ROUTE.equals(XposedHelpers.callMethod(o, "getRoute"))) return;
                } catch (Throwable ignored) {
                }
            }
        }
        XposedHelpers.callMethod(adapter, "y", newMenu(cl));
    }

    private static Object newMenu(ClassLoader cl) {
        Class<?> menuCls = XposedHelpers.findClass(MENU, cl);
        return XposedHelpers.newInstance(menuCls,
                "ic_setting", "模块设置", "", Boolean.TRUE);
    }
}
