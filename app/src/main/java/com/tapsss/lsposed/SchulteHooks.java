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
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 每次开局（i(null) 会开一轮新的倒计时）都先重读 App 配置，
                        // 这样从「舒尔特设置」改完倒计时返回后，下一局立刻用新值，
                        // 不依赖 Activity 的 onResume 是否被调到
                        if (param.args.length != 1 || param.args[0] != null) return;
                        try {
                            XposedHelpers.callMethod(param.thisObject, "reload");
                        } catch (Throwable ignored) {
                        }
                    }

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

            // 从「舒尔特设置 / 模块设置」返回游戏页时重读 App 配置（倒计时开关/时长等），
            // 否则要退出游戏页再进来才生效
            XC_MethodHook reload = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 可能挂在父类（BaseGameDrawerActivity/BaseActivity）的 onResume 上，这里只处理舒尔特游戏页
                    if (!ACTIVITY.equals(param.thisObject.getClass().getName())) return;
                    try {
                        reloadViewConfig(param.thisObject);
                    } catch (Throwable t) {
                        MainHook.log("返回游戏页刷新配置失败: " + t);
                    }
                }
            };
            for (Class<?> c = actCls; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    XposedHelpers.findAndHookMethod(c, "onResume", reload);
                    resumeHooked = true;
                    break;
                } catch (NoSuchMethodError e) {
                    // 往上找声明 onResume 的父类
                } catch (Throwable t) {
                    MainHook.log("onResume Hook 失败: " + t);
                    break;
                }
            }
        }

        // 高级模式（本地记录，不上传）
        try {
            AdvancedSchulte.install(cl);
        } catch (Throwable t) {
            MainHook.log("AdvancedSchulte 安装失败: " + t);
        }
    }

    static final String KEY_BYPASS_BUSY = "tapsss_bypass_busy";
    /** onResume 是否真的挂上了：显示在模式弹窗里，方便确认「设置返回生效」有没有装 */
    static volatile boolean resumeHooked;
    private static final String KEY_LAST_CONFIG = "tapsss_last_config";
    private static final String ACTIVITY =
            "com.star.minesweeping.ui.activity.game.schulte.SchulteGameActivity";
    private static final String RANGE = "com.star.minesweeping.k.b.h4.l";
    private static final String SCHULTE_CONFIG = "com.star.minesweeping.i.c.d.a";
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

    /**
     * 从设置页回到游戏页：SchulteView 只在构造时读过一次 KV，这里重读一遍。
     * 只要发现阶数/倒计时开关/时长与上次不同，就直接按最新设置重建一局 ——
     * 从「舒尔特设置」改完返回时立刻生效，不用退出游戏页重进。
     */
    private static void reloadViewConfig(Object act) {
        Object binding = XposedHelpers.getObjectField(act, "view");
        if (binding == null) return;
        Object view = XposedHelpers.getObjectField(binding, "T");
        if (!(view instanceof View)) return;

        XposedHelpers.callMethod(view, "reload");

        Object game = XposedHelpers.getObjectField(act, "g");
        if (game == null) return;
        try {
            Class<?> configCls = XposedHelpers.findClassIfExists(SCHULTE_CONFIG,
                    act.getClass().getClassLoader());
            if (configCls != null) {
                Object config = XposedHelpers.callStaticMethod(configCls, "c");
                if (config != null) XposedHelpers.callMethod(game, "q", config);
            }
        } catch (Throwable ignored) {
        }

        boolean enabled = XposedHelpers.getBooleanField(view, "y");
        long ms = XposedHelpers.getLongField(view, "z");
        int level = 0;
        try {
            level = (Integer) XposedHelpers.callMethod(game, "f");
            Class<?> configCls = XposedHelpers.findClassIfExists(SCHULTE_CONFIG,
                    act.getClass().getClassLoader());
            if (configCls != null) {
                Object lv = XposedHelpers.callStaticMethod(configCls, "h");
                if (lv instanceof Integer && (Integer) lv >= 3 && (Integer) lv <= 10) {
                    level = (Integer) lv;
                }
            }
        } catch (Throwable t) {
            MainHook.log("读取最新阶数失败: " + t);
        }

        String now = level + "|" + enabled + "|" + ms;
        Object last = XposedHelpers.getAdditionalInstanceField(act, KEY_LAST_CONFIG);
        XposedHelpers.setAdditionalInstanceField(act, KEY_LAST_CONFIG, now);
        // 第一次 onResume 只记录基线；配置没变也不打断（例如从后台切回来）
        if (!(last instanceof String) || last.equals(now)) {
            ((View) view).invalidate();
            return;
        }

        MainHook.log("返回游戏页配置变化 " + last + " -> " + now + "，按新设置重建本局");
        try {
            XposedHelpers.setAdditionalInstanceField(act, KEY_BYPASS_BUSY, Boolean.TRUE);
            try {
                XposedHelpers.callMethod(act, "W", level);
            } finally {
                XposedHelpers.removeAdditionalInstanceField(act, KEY_BYPASS_BUSY);
            }
        } catch (Throwable t) {
            MainHook.log("按新设置重建本局失败: " + t);
            if (enabled) {
                try {
                    XposedHelpers.callMethod(view, "h");
                } catch (Throwable ignored) {
                }
            }
        }
        ((View) view).invalidate();
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
