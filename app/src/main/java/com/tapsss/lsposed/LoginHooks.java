package com.tapsss.lsposed;

import android.app.Activity;
import android.widget.EditText;
import android.widget.ImageView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 多设备掉线自动重登。
 *
 * 场景：账号在另一台设备登录后，本机进入 App 会被踢到登录页（LoginActivity）。
 * 做法：
 *  - 用户手动登录提交时（LoginActivity.L()）保存手机号与明文密码到目标应用私有 SharedPreferences；
 *  - LoginActivity.init() 后，如果开关开启且有保存的凭据，则自动填入并调用 L() 提交。
 *
 * 3.6.10 的登录页绑定类 h.g1 字段：T=手机号 EditText，d0=密码 EditText，
 * e0=协议勾选 ImageView（isSelected 表示已同意），U=登录按钮。
 */
public final class LoginHooks {

    private static final String LOGIN = "com.star.minesweeping.ui.activity.user.LoginActivity";
    private static final String FIELD_TRIED = "tapsss_auto_login_tried";

    private LoginHooks() {
    }

    static void install(ClassLoader cl) {
        Class<?> loginCls = XposedHelpers.findClassIfExists(LOGIN, cl);
        if (loginCls == null) return;

        XposedBridge.hookAllMethods(loginCls, "init", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    scheduleAutoLogin((Activity) param.thisObject);
                } catch (Throwable t) {
                    MainHook.log("自动重登调度失败: " + t);
                }
            }
        });

        try {
            XposedHelpers.findAndHookMethod(loginCls, "L", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        saveCredentials((Activity) param.thisObject);
                    } catch (Throwable t) {
                        MainHook.log("保存登录凭据失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            MainHook.log("登录方法 Hook 失败: " + t);
        }
    }

    private static void scheduleAutoLogin(final Activity act) {
        if (act == null) return;
        Prefs.get(act);
        if (!Prefs.moduleEnabled(act) || !Prefs.autoRelogin(act)) return;

        final String uid = Prefs.savedUid(act);
        final String pwd = Prefs.savedPwd(act);
        if (uid.isEmpty() || pwd.isEmpty()) return;

        if (XposedHelpers.getAdditionalInstanceField(act, FIELD_TRIED) != null) return;
        XposedHelpers.setAdditionalInstanceField(act, FIELD_TRIED, Boolean.TRUE);

        Ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                fillAndSubmit(act, uid, pwd);
            }
        }, 500);
    }

    private static void fillAndSubmit(final Activity act, final String uid, final String pwd) {
        if (!Ui.isAlive(act)) return;
        final Object binding;
        try {
            binding = XposedHelpers.getObjectField(act, "view");
            if (binding == null) return;

            final EditText phone = (EditText) XposedHelpers.getObjectField(binding, "T");
            final EditText password = (EditText) XposedHelpers.getObjectField(binding, "d0");
            Object agreeObj = XposedHelpers.getObjectField(binding, "e0");

            if (phone != null) phone.setText(uid);
            if (password != null) password.setText(pwd);
            if (agreeObj instanceof ImageView) {
                ((ImageView) agreeObj).setSelected(true);
            }

            Ui.toast(act, "检测到账号掉线，正在自动登录…");

            Ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!Ui.isAlive(act)) return;
                    try {
                        XposedHelpers.callMethod(act, "L");
                    } catch (Throwable t) {
                        try {
                            Object button = XposedHelpers.getObjectField(binding, "U");
                            XposedHelpers.callMethod(button, "performClick");
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, 300);
        } catch (Throwable t) {
            MainHook.log("自动填入登录信息失败: " + t);
        }
    }

    private static void saveCredentials(Activity act) {
        if (!Prefs.moduleEnabled(act)) return;
        Object binding = XposedHelpers.getObjectField(act, "view");
        if (binding == null) return;

        EditText phone = (EditText) XposedHelpers.getObjectField(binding, "T");
        EditText password = (EditText) XposedHelpers.getObjectField(binding, "d0");
        if (phone == null || password == null) return;

        String uid = phone.getText().toString().trim();
        String pwd = password.getText().toString();
        if (uid.isEmpty() || pwd.isEmpty()) return;

        Prefs.saveCredentials(act, uid, pwd);
        MainHook.log("已保存登录凭据");
    }
}
