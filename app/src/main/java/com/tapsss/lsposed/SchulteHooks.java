package com.tapsss.lsposed;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 舒尔特方格增强。
 *
 * 3.6.10 关键实现：
 *  - SchulteView 构造时 g() 读取 KV Game.Schulte.Count.Down.Enable/Time 到字段 y(boolean)、z(long)；
 *  - i(null) 在 y=true 时记录 x=elapsedRealtime() 并每 32ms 刷新倒计时，倒计时结束才真正开局；
 *  - onTouchEvent 在状态 d.b（准备）收到 ACTION_DOWN 会直接 j(null) 跳过倒计时 —— 这就是要拦截的点；
 *  - 右上角阶数按钮由 ActionBar.c(style, ic_game_level, listener) 创建。
 */
public final class SchulteHooks {

    private static final String VIEW = "com.star.minesweeping.module.game.schulte.core.SchulteView";
    private static final String ACTION_BAR = "com.star.minesweeping.ui.view.ActionBar";
    private static final int FALLBACK_LEVEL_ICON = 0x7f0e004f;

    private static long lastToastAt = 0L;

    private SchulteHooks() {
    }

    static void install(ClassLoader cl) {
        Class<?> viewCls = XposedHelpers.findClassIfExists(VIEW, cl);
        if (viewCls != null) {
            try {
                XposedHelpers.findAndHookMethod(viewCls, "onTouchEvent", MotionEvent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    blockTouchDuringCountdown(param);
                                } catch (Throwable t) {
                                    MainHook.log("倒计时点击拦截失败: " + t);
                                }
                            }
                        });
            } catch (Throwable t) {
                MainHook.log("onTouchEvent Hook 失败: " + t);
            }

            // 记录倒计时截止时间（i(null) 会开启倒计时），不依赖混淆字段名
            try {
                XposedBridge.hookAllMethods(viewCls, "i", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            trackCountdown(param);
                        } catch (Throwable t) {
                            MainHook.log("倒计时跟踪失败: " + t);
                        }
                    }
                });
            } catch (Throwable t) {
                MainHook.log("i() Hook 失败: " + t);
            }

            // 兜底：倒计时期间直接拦掉 j(null) 的“跳过倒计时开局”
            try {
                XposedBridge.hookAllMethods(viewCls, "j", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length != 1 || param.args[0] != null) return;
                            long until = countdownUntil(param.thisObject);
                            if (until > 0 && SystemClock.elapsedRealtime() + 16 < until) {
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            MainHook.log("j() 拦截失败: " + t);
                        }
                    }
                });
            } catch (Throwable t) {
                MainHook.log("j() Hook 失败: " + t);
            }
        }

        // 舒尔特倒计时时长的输入框：去掉 App 自带的下限（原版构造 NumberInterceptor 时传入 min>0）
        Class<?> rangeCls = XposedHelpers.findClassIfExists(RANGE, cl);
        if (rangeCls != null) {
            XposedBridge.hookAllConstructors(rangeCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 2 || !(param.args[0] instanceof Integer)) return;
                    if (!fromSchulteStart()) return;
                    param.args[0] = Integer.valueOf(0);
                }
            });
        }

        Class<?> barCls = XposedHelpers.findClassIfExists(ACTION_BAR, cl);
        if (barCls != null) {
            try {
                XposedHelpers.findAndHookMethod(barCls, "c",
                        int.class, int.class, View.OnClickListener.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    replaceLevelButton(param);
                                } catch (Throwable t) {
                                    MainHook.log("替换阶数按钮失败: " + t);
                                }
                            }
                        });
            } catch (Throwable t) {
                MainHook.log("ActionBar.c Hook 失败: " + t);
            }
        }

        // 切换模式时绕过「游戏进行中」检查，让 W(int) 直接重建本局
        Class<?> actCls = XposedHelpers.findClassIfExists(ACTIVITY, cl);
        if (actCls != null) {
            XposedBridge.hookAllMethods(actCls, "M", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (XposedHelpers.getAdditionalInstanceField(
                            param.thisObject, KEY_BYPASS_BUSY) != null) {
                        param.setResult(Boolean.FALSE);
                    }
                }
            });
        }

        // 高级模式（本地记录，不上传）
        try {
            AdvancedSchulte.install(cl);
        } catch (Throwable t) {
            MainHook.log("AdvancedSchulte 安装失败: " + t);
        }
    }

    static final String KEY_BYPASS_BUSY = "tapsss_bypass_busy";
    private static final String ACTIVITY =
            "com.star.minesweeping.ui.activity.game.schulte.SchulteGameActivity";
    private static final String RANGE = "com.star.minesweeping.k.b.h4.l";
    private static final String SCHULTE_START_ACTIVITY =
            "com.star.minesweeping.ui.activity.setting.game.SettingSchulteStartActivity";

    /** NumberInterceptor 也被其它设置页使用，只在舒尔特「开始」页构造时去下限 */
    private static boolean fromSchulteStart() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if (SCHULTE_START_ACTIVITY.equals(e.getClassName())) return true;
        }
        return false;
    }

    private static final String KEY_UNTIL = "tapsss_countdown_until";

    /** i(null) = 开始倒计时；i(cells) = 恢复/续局，清掉截止时间 */
    private static void trackCountdown(XC_MethodHook.MethodHookParam param) {
        Object view = param.thisObject;
        if (param.args.length != 1) return;
        Object cells = param.args[0];
        boolean empty = cells == null
                || (cells.getClass().isArray() && java.lang.reflect.Array.getLength(cells) == 0);
        if (!empty) {
            XposedHelpers.removeAdditionalInstanceField(view, KEY_UNTIL);
            return;
        }
        Context c = ((View) view).getContext();
        if (!Prefs.moduleEnabled(c)) {
            XposedHelpers.removeAdditionalInstanceField(view, KEY_UNTIL);
            return;
        }
        // 倒计时开关与时长都跟随 App 自己的设置（字段 y/z），模块只做拦截
        boolean enabled;
        long ms;
        try {
            enabled = XposedHelpers.getBooleanField(view, "y");
            ms = XposedHelpers.getLongField(view, "z");
        } catch (Throwable t) {
            MainHook.log("读取倒计时配置失败: " + t);
            XposedHelpers.removeAdditionalInstanceField(view, KEY_UNTIL);
            return;
        }
        if (!enabled || ms <= 0) {
            XposedHelpers.removeAdditionalInstanceField(view, KEY_UNTIL);
            return;
        }
        XposedHelpers.setAdditionalInstanceField(view, KEY_UNTIL,
                Long.valueOf(SystemClock.elapsedRealtime() + ms));
    }

    private static long countdownUntil(Object view) {
        Object v = XposedHelpers.getAdditionalInstanceField(view, KEY_UNTIL);
        return v instanceof Long ? ((Long) v).longValue() : 0L;
    }

    private static void blockTouchDuringCountdown(XC_MethodHook.MethodHookParam param) {
        Object view = param.thisObject;
        MotionEvent event = (MotionEvent) param.args[0];
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return;

        Context c = ((View) view).getContext();
        if (!Prefs.moduleEnabled(c) || !Prefs.blockTouch(c)) return;

        long until = countdownUntil(view);
        if (until <= 0 || SystemClock.elapsedRealtime() >= until) return; // 不在倒计时中

        param.setResult(Boolean.TRUE);
        toastThrottled(c, "倒计时中，点击不会直接开始");
    }

    private static void replaceLevelButton(XC_MethodHook.MethodHookParam param) {
        Object result = param.getResult();
        if (!(result instanceof ImageView)) return;
        ImageView icon = (ImageView) result;

        Context c = icon.getContext();
        if (!Prefs.moduleEnabled(c)) return;

        Activity act = Ui.findActivity(c);
        if (act == null || !act.getClass().getName().endsWith("SchulteGameActivity")) return;

        int iconId = ((Integer) param.args[1]).intValue();
        int levelIcon = Ui.resId(c, "ic_game_level", "mipmap");
        if (levelIcon == 0) levelIcon = FALLBACK_LEVEL_ICON;
        if (iconId != levelIcon) return;

        final Activity activity = act;
        icon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SchulteModeDialog.show(activity);
            }
        });
    }

    static void toastThrottled(Context c, String msg) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastToastAt < 1200) return;
        lastToastAt = now;
        Ui.toast(c, msg);
    }
}
