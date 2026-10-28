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
            XposedBridge.hookAllConstructors(viewCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        applyCountdownConfig((View) param.thisObject);
                    } catch (Throwable t) {
                        MainHook.log("应用倒计时配置失败: " + t);
                    }
                }
            });

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
    }

    private static void applyCountdownConfig(View view) {
        Context c = view.getContext();
        Prefs.get(c);
        if (!Prefs.moduleEnabled(c) || !Prefs.schulteEnabled(c)) return;

        XposedHelpers.setBooleanField(view, "y", Prefs.countdownEnabled(c));
        if (Prefs.countdownEnabled(c)) {
            XposedHelpers.setLongField(view, "z", Prefs.countdownMs(c));
        }
    }

    private static void blockTouchDuringCountdown(XC_MethodHook.MethodHookParam param) {
        Object view = param.thisObject;
        MotionEvent event = (MotionEvent) param.args[0];
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return;

        Context c = ((View) view).getContext();
        if (!Prefs.moduleEnabled(c) || !Prefs.schulteEnabled(c) || !Prefs.blockTouch(c)) return;

        if (!XposedHelpers.getBooleanField(view, "y")) return;

        long start = XposedHelpers.getLongField(view, "x");
        long duration = XposedHelpers.getLongField(view, "z");
        if (start <= 0 || duration <= 0) return;

        long elapsed = SystemClock.elapsedRealtime() - start;
        if (elapsed < 0 || elapsed >= duration) return; // 倒计时已结束

        Object game = XposedHelpers.callMethod(view, "getGame");
        if (game == null) return;
        Object state = XposedHelpers.callMethod(game, "h");
        if (!(state instanceof Enum) || !"b".equals(((Enum<?>) state).name())) return;

        param.setResult(Boolean.TRUE);
        toastThrottled(c, "倒计时中，点击不会直接开始");
    }

    private static void replaceLevelButton(XC_MethodHook.MethodHookParam param) {
        Object result = param.getResult();
        if (!(result instanceof ImageView)) return;
        ImageView icon = (ImageView) result;

        Context c = icon.getContext();
        if (!Prefs.moduleEnabled(c) || !Prefs.schulteEnabled(c) || !Prefs.modeSwitch(c)) return;

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
