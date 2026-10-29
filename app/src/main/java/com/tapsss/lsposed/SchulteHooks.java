package com.tapsss.lsposed;

import android.app.Activity;
import android.app.Dialog;
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
                    Object fromSettings = XposedHelpers.getAdditionalInstanceField(
                            param.thisObject, KEY_FROM_SETTINGS);
                    if (fromSettings != null) {
                        // 从「舒尔特设置」返回：主题/倒计时/重开预设都只在初始化时读一次，
                        // 直接整页重建，改完立刻生效（不用退回主页再进）
                        XposedHelpers.removeAdditionalInstanceField(param.thisObject, KEY_FROM_SETTINGS);
                        final Activity act = (Activity) param.thisObject;
                        MainHook.log("从舒尔特设置返回，整页重建");
                        Ui.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (Ui.isAlive(act)) act.recreate();
                                } catch (Throwable t) {
                                    MainHook.log("整页重建失败: " + t);
                                }
                            }
                        });
                        return;
                    }
                    try {
                        reloadViewConfig(param.thisObject);
                    } catch (Throwable t) {
                        MainHook.log("返回游戏页刷新配置失败: " + t);
                    }
                    // 每次回到游戏页都补装一次：进来得早时类可能还没加载完，
                    // 之前漏装的 Hook 在这里按需补上（install 内部已做幂等保护）
                    try {
                        AdvancedSchulte.ensureInstalled((Activity) param.thisObject);
                    } catch (Throwable t) {
                        MainHook.log("高级模式补装失败: " + t);
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

        // 官方模式：非新纪录也不展示成绩、直接下一把（与高级模式共用同一个开关）。
        // 两层保险：$b.d 记录本局是否新纪录，$c.z（官方成绩弹窗 + 上传入口）真正拦截
        installOfficialHooks(cl);

        // 高级模式（本地记录，不上传）
        try {
            AdvancedSchulte.install(cl);
        } catch (Throwable t) {
            MainHook.log("AdvancedSchulte 安装失败: " + t);
        }
    }

    /**
     * 官方结算的两个拦截（$b.d / $c.z）单独抽出：类由 App 自己懒加载，
     * 首次安装可能还没被加载，之后 onResume 会通过 ensureOfficialHooks 补装。
     */
    static void installOfficialHooks(ClassLoader cl) {
        installDialogGuard();
        if (officialHooked) return;
        Class<?> listenerCls = XposedHelpers.findClassIfExists(ACTIVITY + "$b", cl);
        if (!cbHooked) {
            if (listenerCls != null) {
                try {
                    XposedBridge.hookAllMethods(listenerCls, "d", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                officialAutoNext(param);
                            } catch (Throwable t) {
                                MainHook.log("官方模式非新纪录记账失败: " + t);
                            }
                        }
                    });
                    cbHooked = true;
                } catch (Throwable t) {
                    MainHook.log("$b.d Hook 失败: " + t);
                }
            } else {
                MainHook.log("未找到 SchulteGameActivity$b，稍后重试");
            }
        }
        Class<?> resultCls = XposedHelpers.findClassIfExists(ACTIVITY + "$c", cl);
        if (!resultHooked) {
            if (resultCls != null) {
                try {
                    XposedHelpers.findAndHookMethod(resultCls, "z", boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                officialResultGuard(param);
                            } catch (Throwable t) {
                                MainHook.log("官方成绩弹窗拦截失败: " + t);
                            }
                        }
                    });
                    resultHooked = true;
                } catch (Throwable t) {
                    MainHook.log("SchulteGameActivity$c.z Hook 失败: " + t);
                }
            } else {
                MainHook.log("未找到 SchulteGameActivity$c，稍后重试");
            }
        }
        officialHooked = cbHooked && resultHooked;
    }

    /** 幂等补装：只在没装全时重试，避免每次 onResume 都重复挂 Hook */
    static void ensureOfficialHooks(ClassLoader cl) {
        if (officialHooked) return;
        try {
            installOfficialHooks(cl);
        } catch (Throwable t) {
            MainHook.log("官方结算拦截补装失败: " + t);
        }
    }

    /** 成绩弹窗类（3.6.10 混淆名）：$c.z 没装上时用它兜底 */
    private static final String RESULT_DIALOG = "com.star.minesweeping.k.b.n4.o";
    private static volatile boolean dialogHooked;

    /**
     * 兜底拦截：成绩弹窗监听器 $c 由 App 懒加载，首次结算时模块可能还没挂上 z()，
     * 同一 Activity 内之后每一把非新纪录就仍会弹成绩（切一次模式重建后才会好）。
     * 这里直接拦成绩 Dialog 的 show：只要本局被 $b.d 判定为非新纪录，就挡掉并自动下一把。
     */
    private static void installDialogGuard() {
        if (dialogHooked) return;
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        guardResultDialog(param);
                    } catch (Throwable t) {
                        MainHook.log("成绩弹窗兜底拦截失败: " + t);
                    }
                }
            });
            dialogHooked = true;
            MainHook.log("成绩弹窗兜底拦截已安装");
        } catch (Throwable t) {
            MainHook.log("成绩弹窗兜底 Hook 失败: " + t);
        }
    }

    private static void guardResultDialog(XC_MethodHook.MethodHookParam param) {
        Object dialog = param.thisObject;
        if (!(dialog instanceof Dialog)) return;
        if (!dialog.getClass().getName().startsWith(RESULT_DIALOG)) return;
        Activity a = Ui.findActivity(((Dialog) dialog).getContext());
        if (a == null) return;
        if (!Prefs.moduleEnabled(a) || !Prefs.autoNextNoRecord(a)) return;
        if (AdvancedSchulte.advCompleting) return;
        if (XposedHelpers.getAdditionalInstanceField(a, KEY_SILENT) == null) return;

        XposedHelpers.removeAdditionalInstanceField(a, KEY_SILENT);
        param.setResult(null);
        MainHook.log("官方模式非新纪录，成绩弹窗由 Dialog 兜底拦截");
        if (XposedHelpers.getAdditionalInstanceField(a, KEY_RESTART_PENDING) != null) return;
        XposedHelpers.setAdditionalInstanceField(a, KEY_RESTART_PENDING, Boolean.TRUE);
        final Activity target = a;
        Ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                nextOfficialRound(target);
            }
        }, 150);
    }

    static final String KEY_BYPASS_BUSY = "tapsss_bypass_busy";
    /** 去过「舒尔特设置」：返回游戏页时整页重建，主题/倒计时/重开预设全部按新值初始化 */
    static final String KEY_FROM_SETTINGS = "tapsss_from_settings";
    /** 本局已判定为新纪录：官方成绩弹窗放行（由 $b.d 打标记，$c.z 消费） */
    private static final String KEY_RESULT_ALLOWED = "tapsss_result_allowed";
    /** $b.d 时同步抓到的本局成绩与归属：$c.z 可能因校时请求而晚到，不能再读现场 */
    private static final String KEY_LAST_TIME = "tapsss_last_result_time";
    private static final String KEY_LAST_KEY = "tapsss_last_result_key";
    /** 本局已安排过「静默自动下一把」，避免同局的第二次 z() 重复重开 */
    private static final String KEY_RESTART_PENDING = "tapsss_skip_restart_pending";
    /** 本局已判定为非新纪录：成绩弹窗（Dialog）兜底拦截用 */
    private static final String KEY_SILENT = "tapsss_silent_result";
    /** onResume 是否真的挂上了：显示在模式弹窗里，方便确认「设置返回生效」有没有装 */
    static volatile boolean resumeHooked;
    /** 官方结算拦截（$b.d / $c.z）是否都已挂上：模式弹窗里显示，便于确认补装结果 */
    static volatile boolean officialHooked;
    private static volatile boolean cbHooked;
    private static volatile boolean resultHooked;
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

    /**
     * 官方模式「非新纪录不展示成绩」的第 1 层：完成回调 $b.d 时记账。
     *
     * 是新纪录 → 写入本地最佳并打上放行标记（官方成绩弹窗照常显示）；
     * 不是新纪录 → 只记日志，真正的拦截在 $c.z（见 officialResultGuard）。
     * 之前只在 $b.d 里 setResult(null) 会因版本/时序差异失效，故改成两层。
     */
    private static void officialAutoNext(XC_MethodHook.MethodHookParam param) {
        Object listener = param.thisObject;
        Object act = XposedHelpers.getObjectField(listener, "a");
        if (!(act instanceof Activity)) return;
        Activity a = (Activity) act;
        // 每次完成都清掉上一局的标记
        XposedHelpers.removeAdditionalInstanceField(a, KEY_RESULT_ALLOWED);
        XposedHelpers.removeAdditionalInstanceField(a, KEY_RESTART_PENDING);
        XposedHelpers.removeAdditionalInstanceField(a, KEY_SILENT);
        if (!Prefs.moduleEnabled(a) || !Prefs.autoNextNoRecord(a)) return;
        if (AdvancedSchulte.advCompleting) return;

        long time = officialTime(a);
        int[] parts = officialKeyParts(a);
        if (time <= 0 || parts == null) return;
        String key = "official_best_" + parts[0] + "_" + parts[1] + "_" + parts[2];

        // 成绩弹窗监听器 $c 是 App 懒加载的：首次结算时可能还没装上，
        // 这里立即补一次，再延迟补一次（此时 $c 必定已加载），保证从下一把起能静默。
        if (!resultHooked) {
            final ClassLoader cl2 = a.getClassLoader();
            ensureOfficialHooks(cl2);
            if (!resultHooked) {
                Ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        ensureOfficialHooks(cl2);
                    }
                }, 1200);
            }
        }

        // 关键：在这里（官方刚算完成绩、计时器还没被下一局重置时）同步缓存本局归属。
        // $c.z 可能被校时网络请求推迟到下一局开始后才回调，那时现场的计时器/模式已经变了。
        XposedHelpers.setAdditionalInstanceField(a, KEY_LAST_TIME, Long.valueOf(time));
        XposedHelpers.setAdditionalInstanceField(a, KEY_LAST_KEY, key);
        long best = Prefs.getLong(a, key, 0L);
        if (best <= 0 || time < best) {
            Prefs.setLong(a, key, time);
            XposedHelpers.setAdditionalInstanceField(a, KEY_RESULT_ALLOWED, Boolean.TRUE);
            if (best <= 0) {
                // 该模式/阶数还没有本地最佳基线：这一把只能先按官方流程展示。
                // 排行数据是服务器异步返回的，结算时拿不到，所以这里如实提示正在获取。
                toastThrottled(a, "已开启【非新纪录不展示成绩，自动下一把】功能，正在获取顺序下的记录数据");
            }
            MainHook.log("官方模式新纪录(" + time + ")，按官方流程展示");
            return;
        }
        MainHook.log("官方模式非新纪录(" + time + ">=" + best + ")，成绩弹窗将静默");
        // $c.z 的 Hook 可能因 App 懒加载没装上（切一次模式重建后才会好）；
        // 这里再打一个标记，由 Dialog.show 兜底，保证同一 Activity 内从这一把起就静默。
        XposedHelpers.setAdditionalInstanceField(a, KEY_SILENT, Boolean.TRUE);
    }

    /**
     * 官方模式「非新纪录不展示成绩」的第 2 层：$c.z 是官方成绩弹窗 / 记录入库 / 上传的唯一入口。
     * 这里拦掉就不会弹成绩、也不会写库上传，随后直接自动下一把。
     */
    private static void officialResultGuard(XC_MethodHook.MethodHookParam param) {
        if (param.args.length != 1 || !(param.args[0] instanceof Boolean)) return;
        if (!((Boolean) param.args[0])) return;   // win=false 时官方什么都不做
        Object act = XposedHelpers.getObjectField(param.thisObject, "q");
        if (!(act instanceof Activity)) return;
        final Activity a = (Activity) act;
        if (!Prefs.moduleEnabled(a) || !Prefs.autoNextNoRecord(a)) return;
        if (AdvancedSchulte.advCompleting) return;
        if (Prefs.advMode(a) != AdvancedSchulte.MODE_OFF) return; // 高级模式走自己的结算

        Object allowed = XposedHelpers.getAdditionalInstanceField(a, KEY_RESULT_ALLOWED);
        // 优先用 $b.d 缓存的「这一局」的成绩；没有缓存（$b.d 未装上）时再读现场
        long time;
        String key;
        Object cachedTime = XposedHelpers.getAdditionalInstanceField(a, KEY_LAST_TIME);
        Object cachedKey = XposedHelpers.getAdditionalInstanceField(a, KEY_LAST_KEY);
        if (cachedTime instanceof Long && cachedKey instanceof String) {
            time = (Long) cachedTime;
            key = (String) cachedKey;
        } else {
            time = officialTime(a);
            int[] parts = officialKeyParts(a);
            if (time <= 0 || parts == null) return;
            key = "official_best_" + parts[0] + "_" + parts[1] + "_" + parts[2];
        }
        long best = Prefs.getLong(a, key, 0L);
        if (allowed != null || best <= 0 || time < best) {
            // $b.d 没装上的兜底：这里补记最佳并放行
            if (best <= 0) Prefs.setLong(a, key, time);
            MainHook.log("官方模式新纪录(" + time + ")，成绩弹窗放行");
            return;
        }

        param.setResult(null);
        MainHook.log("官方模式非新纪录(" + time + ">=" + best + ")，不展示成绩，自动下一把");
        final Activity target = a;
        if (XposedHelpers.getAdditionalInstanceField(a, KEY_RESTART_PENDING) != null) return;
        XposedHelpers.setAdditionalInstanceField(a, KEY_RESTART_PENDING, Boolean.TRUE);
        Ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                nextOfficialRound(target);
            }
        }, 150);
    }

    /**
     * 与官方 $b.d 完全一致：优先取回放里最后一次有效操作的用时，
     * 没有回放数据时才用计时器当前值（计时器在校时/停表后可能已被下一局重置）。
     */
    private static long officialTime(Activity a) {
        try {
            Object rec = XposedHelpers.getObjectField(a, "i");
            if (rec != null) {
                Object last = XposedHelpers.callMethod(rec, "h");
                if (last != null) {
                    Object t = XposedHelpers.callMethod(last, "getTime");
                    if (t instanceof Long && (Long) t > 0) return (Long) t;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object timer = XposedHelpers.getObjectField(a, "e");
            return (Long) XposedHelpers.callMethod(timer, "e");
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** {type, blind(0/1), level} */
    private static int[] officialKeyParts(Activity a) {
        try {
            Object game = XposedHelpers.getObjectField(a, "g");
            int level = (Integer) XposedHelpers.callMethod(game, "f");
            int type = (Integer) XposedHelpers.callMethod(game, "l");
            boolean blind = (Boolean) XposedHelpers.callMethod(game, "m");
            if (level <= 0) return null;
            return new int[]{type, blind ? 1 : 0, level};
        } catch (Throwable t) {
            return null;
        }
    }

    private static void nextOfficialRound(Activity a) {
        if (!Ui.isAlive(a)) return;
        try {
            XposedHelpers.setAdditionalInstanceField(a, KEY_BYPASS_BUSY, Boolean.TRUE);
            try {
                XposedHelpers.callMethod(a, "V");
            } finally {
                XposedHelpers.removeAdditionalInstanceField(a, KEY_BYPASS_BUSY);
            }
        } catch (Throwable t) {
            MainHook.log("官方模式自动下一把失败: " + t);
        }
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
