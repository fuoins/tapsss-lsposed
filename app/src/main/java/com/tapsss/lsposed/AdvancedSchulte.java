package com.tapsss.lsposed;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 舒尔特高级模式。
 *
 * 完全接管 SchulteView.e(row, col)（App 的唯一点击入口），因此不会走到
 * 官方的 listener.d(...) 结算流程 —— 高级模式的成绩只在模块本地记录，不上传。
 * 每局完成/失败都会在主界面弹一层独立效果，随后自动调用 Activity 的 V() 下一把。
 */
public final class AdvancedSchulte {

    static final int MODE_OFF = 0;
    static final int SPEED = 1;      // 爆手速：1..x，错就重开，到 x 自动重开
    static final int REVERSE = 2;    // 倒序：n²..1
    static final int MID = 3;        // 归中：1,n²,2,n²-1...
    static final int MAXN = 4;       // max≤N：相邻两数间隔 ≤ N 秒
    static final int START1 = 5;     // 1start：开局点 1 的反应 ≤ 0.45s

    static final String[] NAMES = {
            "顺序模式", "爆手速训练", "倒序训练", "归中训练", "max≤N训练", "1start训练"};

    /** 成绩面板上「简单-顺序」这种后缀用的短名 */
    static final String[] SHORT = {
            "顺序", "爆手速", "倒序", "归中", "max≤N", "1start"};

    static final String[] DESCS = {
            "从头点到尾（1→n²），默认模式，即官方的正常模式。",
            "一眼能看到的数字（比如 1-9）要在 2 秒内点完。五阶 1-25 刷成绩的话，"
                    + "可以后程全猜，但是手速一定要跟上。这种训练方法有助于刷 PB，或者提升视幅阈值"
                    + "（预判时间减少，考验视野幅度），但不助于提升平均实力。\n\n"
                    + "可设置目标 x（x ≤ 阶数²）：从 1 点到 x，点到了就自动重新来。点错不重开，会提示应该点哪个。",
            "从 n² 点到 1（n²→1）。可以让人快速进入状态，提升对大数的识别能力和应变能力。"
                    + "如果要打比赛的话可以着重练习这个，并适当增加打乱练习。",
            "归中训练：1→n²→2→n²-1→3→… 与倒序同组，同样可以快速进入状态，"
                    + "提升对大数的识别能力和应变能力。要打比赛的话可以着重练习。",
            "定义：除反应外，全程最大的两数间隔时间 ≤ N 秒。\n\n"
                    + "N 参考值：3 阶 0.1 / 4 阶 0.2 / 5 阶 0.25 / 6 阶 0.4 / 7 阶 0.75 / "
                    + "8 阶 1.0 / 9 阶 1.75 / 10 阶 3.0。\n\n长期练习可以减少卡顿。",
            "仅低阶（3-5 阶）。强制减少开局点 1 的反应时间（默认应 ≤ 0.45 秒，"
                    + "可在弹窗里自定义毫秒），超过则重开。"};

    private static final String VIEW = "com.star.minesweeping.module.game.schulte.core.SchulteView";
    private static final String ACTIVITY =
            "com.star.minesweeping.ui.activity.game.schulte.SchulteGameActivity";
    private static final String RESTART_HELPER = "com.star.minesweeping.i.c.d.c.a";
    private static final String SUCCESS =
            "com.star.minesweeping.data.api.game.schulte.SchulteSuccess";
    private static final String RESULT_DIALOG = "com.star.minesweeping.k.b.n4.o";
    private static final String RESULT_LISTENER = "com.star.minesweeping.k.b.n4.o$c";
    private static final String AXIS_FORMATTER = "com.star.minesweeping.k.b.n4.o$a";
    private static final String RESULT_MARKER =
            "com.star.minesweeping.ui.view.chart.SchulteResultMarkerView";
    private static final String DETAIL_ADAPTER =
            "com.star.minesweeping.ui.activity.game.schulte.SchulteResultDetailActivity$c";
    /** 成绩详情列表 / 曲线提示里的序号列（res id index_tv） */
    private static final int ID_INDEX_TV = 0x7f090391;

    private static final String KEY_PREV_STATUS = "tapsss_adv_prev_status";
    private static final String KEY_ROUND = "tapsss_adv_round";
    static final String KEY_OVERLAY_TAG = "tapsss_adv_overlay";

    /** 安装结果：在模式弹窗里显示，方便确认当前运行的是哪版代码、Hook 是否真的生效 */
    static volatile boolean eHooked;
    static volatile boolean jHooked;
    static volatile boolean iHooked;
    static volatile boolean restartBlocked;
    /** 成绩面板曲线的「序号/数字」按当前高级模式改写：0 = 不处理 */
    private static volatile int chartMode;
    private static volatile int chartN;
    /** 曲线实际的点数（爆手速时等于目标 x），用来收敛 X 轴刻度 */
    private static volatile int chartCount;
    private static volatile boolean chartHooked;
    private static volatile boolean chartAxisHooked;
    private static volatile boolean detailHooked;
    private static long lastEnsureAt;
    /** 正在走官方结算：避免官方完成回调被「非新纪录不展示」二次拦截 */
    static volatile boolean advCompleting;
    private static volatile String installError = "";

    private static final float[] MAX_GAP = {
            0f, 0f, 0f, 0.1f, 0.2f, 0.25f, 0.4f, 0.75f, 1.0f, 1.75f, 3.0f};
    private static final long START1_LIMIT_MS = 450L;   // 1start 的内置默认反应上限

    private static final int ACCENT_SPEED = 0xFFFF7A45;
    private static final int ACCENT_REVERSE = 0xFF4C8DFF;
    private static final int ACCENT_MID = 0xFFA46BFF;
    private static final int ACCENT_MAXN = 0xFF2ED47A;
    private static final int ACCENT_START1 = 0xFFFFC53D;

    private AdvancedSchulte() {
    }

    /** 每局状态。挂在 SchulteView 的附加字段上，随 Activity 重建自然清空。 */
    static final class Round {
        int mode;
        int n;
        int x;
        /** 官方 type：1/2（简单、简单打乱）点过的格子会消失，0/3（普通、普通打乱）不会 */
        int type;
        boolean blind;
        boolean hides;
        long startAt;
        long lastCorrectAt;
        int idx;
        int taps;
        int errors;
        long reaction;
        long maxGap;
        boolean done;
        /** 简单/简单打乱会把点过的格子值置 0，这里留一份原始棋盘用于结算后还原展示 */
        int[][] orig;
    }

    /**
     * 页面每次进入时补装一次：早期安装若因类还没加载而失败，这里会按需重试。
     * 已经在 install() 里做了幂等保护，重复调用不会叠加 Hook。
     */
    static void ensureInstalled(Activity act) {
        if (act == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastEnsureAt < 3000L) return;
        lastEnsureAt = now;
        try {
            ClassLoader cl = act.getClassLoader();
            if (!eHooked || !jHooked || !iHooked || !restartBlocked || !chartHooked || !detailHooked) {
                install(cl);
            }
            installChartHooks(cl);
            AdvancedPages.install(cl);
            SchulteRestart.install(cl);
            SchulteHooks.ensureOfficialHooks(cl);
            MainHook.log("高级模式补装检查: e=" + eHooked + " j=" + jHooked + " i=" + iHooked
                    + " restart=" + restartBlocked);
        } catch (Throwable t) {
            MainHook.log("高级模式补装失败: " + t);
        }
    }

    static void install(ClassLoader cl) {
        Class<?> viewCls = XposedHelpers.findClassIfExists(VIEW, cl);
        if (viewCls == null) {
            installError = "找不到 SchulteView";
            MainHook.log("高级模式：未找到 SchulteView");
            return;
        }

        XC_MethodHook tap = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    onTap(param);
                } catch (Throwable t) {
                    MainHook.log("高级模式点击失败: " + t);
                }
            }
        };
        if (!eHooked) {
            try {
                XposedHelpers.findAndHookMethod(viewCls, "e", int.class, int.class, tap);
                eHooked = true;
            } catch (Throwable t) {
                // 版本差异导致签名不一致时，退化为按方法名接管
                try {
                    XposedBridge.hookAllMethods(viewCls, "e", tap);
                    eHooked = true;
                    MainHook.log("高级模式 e() 按名兜底接管（签名不符）");
                } catch (Throwable t2) {
                    installError = "e() 接管失败";
                    MainHook.log("高级模式 e() Hook 失败: " + t);
                }
            }
        }

        if (!jHooked) {
            try {
                XposedBridge.hookAllMethods(viewCls, "j", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object view = param.thisObject;
                            XposedHelpers.setAdditionalInstanceField(view, KEY_PREV_STATUS,
                                    statusName(game(view)));
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object view = param.thisObject;
                            Object prev = XposedHelpers.getAdditionalInstanceField(view, KEY_PREV_STATUS);
                            XposedHelpers.removeAdditionalInstanceField(view, KEY_PREV_STATUS);
                            // 注意：SchulteStatus 的枚举常量名是 Normal/Ready/Gaming/Finished，
                            // 不是混淆后的字段名 b/c。这里必须用 name()，否则永远不会开局。
                            if (!"Ready".equals(prev)) return;           // 之前不是 Ready，本局早已开始
                            if (!"Gaming".equals(statusName(game(view)))) return; // 这次 j 被拦下或未开局
                            startRound(view);
                        } catch (Throwable t) {
                            MainHook.log("高级模式开局失败: " + t);
                        }
                    }
                });
                jHooked = true;
            } catch (Throwable t) {
                MainHook.log("高级模式 j() Hook 失败: " + t);
            }
        }

        if (!iHooked) {
            try {
                XposedBridge.hookAllMethods(viewCls, "i", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length != 1 || param.args[0] != null) return;
                            Object view = param.thisObject;
                            XposedHelpers.removeAdditionalInstanceField(view, KEY_ROUND);
                            // 新的一局开始：成绩面板的序号改写只对刚结束那一把有效
                            chartMode = MODE_OFF;
                            chartN = 0;
                            chartCount = 0;
                            hideOverlay(Ui.findActivity(((View) view).getContext()));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                iHooked = true;
            } catch (Throwable t) {
                MainHook.log("高级模式 i() Hook 失败: " + t);
            }
        }

        // 高级模式下屏蔽 App 自带「重开预设」的时间/错误阈值，重开节奏完全由本模块控制
        if (!restartBlocked) {
            Class<?> helperCls = XposedHelpers.findClassIfExists(RESTART_HELPER, cl);
            if (helperCls != null) {
                XC_MethodHook block = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (advancedActivity(param.thisObject)) param.setResult(null);
                        } catch (Throwable ignored) {
                        }
                    }
                };
                boolean ok = true;
                try {
                    XposedHelpers.findAndHookMethod(helperCls, "d", long.class, block);
                } catch (Throwable t) {
                    ok = false;
                    MainHook.log("高级模式屏蔽时间重开失败: " + t);
                }
                try {
                    XposedHelpers.findAndHookMethod(helperCls, "b", int.class, block);
                } catch (Throwable t) {
                    ok = false;
                    MainHook.log("高级模式屏蔽错误重开失败: " + t);
                }
                restartBlocked = ok;
            }
        }

        installChartHooks(cl);
    }

    /** 成绩面板：曲线 x 轴、提示框、详情列表的「序号」都改成实际点击的数字 */
    private static void installChartHooks(ClassLoader cl) {
        if (!chartHooked) {
            Class<?> axisCls = XposedHelpers.findClassIfExists(AXIS_FORMATTER, cl);
            if (axisCls != null) {
                try {
                    XposedHelpers.findAndHookMethod(axisCls, "getFormattedValue", float.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        decorateAxis(param);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                } catch (Throwable t) {
                    MainHook.log("成绩曲线坐标轴改写失败: " + t);
                }
            }
            Class<?> markerCls = XposedHelpers.findClassIfExists(RESULT_MARKER, cl);
            if (markerCls != null) {
                try {
                    XposedBridge.hookAllMethods(markerCls, "refreshContent", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                decorateMarker(param);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable t) {
                    MainHook.log("成绩曲线提示改写失败: " + t);
                }
            }
            // 两个类都在（且没报错）才算装好；否则下次 onResume 再试
            chartHooked = axisCls != null && markerCls != null;
        }

        // 曲线点数很少时（爆手速 x 很小），MPAndroidChart 默认会画出很多重复/无意义刻度
        if (!chartAxisHooked) {
            Class<?> dlgCls = XposedHelpers.findClassIfExists(RESULT_DIALOG, cl);
            if (dlgCls != null) {
                try {
                    XposedBridge.hookAllMethods(dlgCls, "t", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                fixChartXAxis(param);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    chartAxisHooked = true;
                } catch (Throwable t) {
                    MainHook.log("成绩曲线 X 轴收敛失败: " + t);
                }
            }
        }

        // 「点击查看详细数据」列表：首列序号同样是 1..n²，倒序/归中下要和曲线一致
        if (!detailHooked) {
            Class<?> detailCls = XposedHelpers.findClassIfExists(DETAIL_ADAPTER, cl);
            if (detailCls != null) {
                try {
                    java.util.Set<XC_MethodHook.Unhook> unhooks =
                            XposedBridge.hookAllMethods(detailCls, "Y1", new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        decorateDetailRow(param);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                    if (unhooks != null && !unhooks.isEmpty()) {
                        detailHooked = true;
                    } else {
                        MainHook.log("成绩详情列表地址未找到（Y1 不存在）");
                    }
                } catch (Throwable t) {
                    MainHook.log("成绩详情列表改写失败: " + t);
                }
            }
        }
    }

    private static void decorateDetailRow(XC_MethodHook.MethodHookParam param) {
        int mode = chartMode;
        int n = chartN;
        if ((mode != REVERSE && mode != MID) || n <= 0) return;
        if (param.args.length < 1 || param.args[0] == null) return;
        Object holder = param.args[0];
        try {
            Object posObj = XposedHelpers.callMethod(holder, "getAdapterPosition");
            if (!(posObj instanceof Integer)) return;
            int pos = (Integer) posObj;
            if (pos < 0) return;
            View row = (View) XposedHelpers.getObjectField(holder, "itemView");
            if (row == null) return;
            TextView tv = (TextView) row.findViewById(ID_INDEX_TV);
            if (tv != null) tv.setText(String.valueOf(numberAt(mode, n, pos + 1)));
        } catch (Throwable ignored) {
        }
    }

    private static void decorateAxis(XC_MethodHook.MethodHookParam param) {
        int mode = chartMode;
        int n = chartN;
        if (n <= 0) return;
        if (mode == SPEED) {
            // 爆手速：刻度值就是第几次点击，只保留落在真实点数范围内的整数刻度，
            // 其余（默认图表在小数据量下产生的半刻度/越界刻度）留空
            float v = (param.args.length > 0 && param.args[0] instanceof Float)
                    ? (Float) param.args[0] : 0f;
            int k = Math.round(v) + 1;
            int count = Math.max(1, chartCount);
            if (Math.abs(v - Math.round(v)) > 0.01f || k > count) {
                param.setResult("");
            } else {
                param.setResult(String.valueOf(Math.max(1, k)));
            }
            return;
        }
        if (mode != REVERSE && mode != MID) return;
        Object res = param.getResult();
        if (!(res instanceof String)) return;
        int k = parseInt((String) res);
        if (k <= 0) return;
        param.setResult(String.valueOf(numberAt(mode, n, k)));
    }

    /**
     * 爆手速目标 x 很小（例如 x=1）时，X 轴默认会按整张图的宽度划出很多刻度，
     * 出现大量重复/无意义数字。这里按实际点数把 X 轴收敛到 1..count。
     */
    private static void fixChartXAxis(XC_MethodHook.MethodHookParam param) {
        if (chartMode != SPEED) return;
        Object binding = XposedHelpers.getObjectField(param.thisObject, "b");
        if (binding == null) return;
        Object chart = XposedHelpers.getObjectField(binding, "R");
        if (chart == null) return;

        int count = 0;
        Object data = XposedHelpers.callMethod(chart, "getLineData");
        if (data != null) {
            Object sc = XposedHelpers.callMethod(data, "getDataSetCount");
            int setCount = sc instanceof Integer ? (Integer) sc : 0;
            for (int i = 0; i < setCount; i++) {
                Object ds = XposedHelpers.callMethod(data, "getDataSetByIndex", i);
                if (ds == null) continue;
                Object ec = XposedHelpers.callMethod(ds, "getEntryCount");
                int c = ec instanceof Integer ? (Integer) ec : 0;
                if (c > count) count = c;
            }
        }
        if (count <= 0) count = Math.max(1, chartCount);
        chartCount = count;

        Object xAxis = XposedHelpers.callMethod(chart, "getXAxis");
        if (xAxis == null) return;
        XposedHelpers.callMethod(xAxis, "setGranularity", 1f);
        XposedHelpers.callMethod(xAxis, "setGranularityEnabled", Boolean.TRUE);
        XposedHelpers.callMethod(xAxis, "setAxisMinimum", 0f);
        if (count <= 1) {
            XposedHelpers.callMethod(xAxis, "setAxisMaximum", 1f);
            XposedHelpers.callMethod(xAxis, "setLabelCount", 2, Boolean.TRUE);
        } else {
            // 强制刻度模式下 MPAndroidChart 用 (max-min)/(labelCount-1) 均分，
            // 只有区间能被整除时刻度才是整数，否则 formatter 会把小数刻度全置空、
            // 只剩首尾两个数字。这里选一个能整除点数的刻度数。
            int steps = count - 1;
            int labels = 2;
            for (int d = Math.min(count, 6); d >= 2; d--) {
                if (steps % (d - 1) == 0) {
                    labels = d;
                    break;
                }
            }
            XposedHelpers.callMethod(xAxis, "setAxisMaximum", (float) steps);
            XposedHelpers.callMethod(xAxis, "setLabelCount", labels, Boolean.TRUE);
        }
        XposedHelpers.callMethod(chart, "invalidate");
        MainHook.log("成绩曲线 X 轴收敛: points=" + count + " mode=" + chartMode);
    }

    private static void decorateMarker(XC_MethodHook.MethodHookParam param) {
        int mode = chartMode;
        int n = chartN;
        if ((mode != REVERSE && mode != MID) || n <= 0) return;
        if (param.args.length < 1 || param.args[0] == null) return;
        try {
            // 用 Entry.x 反推序号（不要解析现有文本，避免重复刷新时把自己改过的数字当序号）
            float x = (Float) XposedHelpers.callMethod(param.args[0], "getX");
            int k = (int) x + 1;
            if (k <= 0) return;
            TextView first = (TextView) XposedHelpers.getObjectField(param.thisObject, "f");
            // 曲线 x 轴已经按「实际点击的数字」显示（倒序 98765…），
            // 提示框的序号也要用同一个数字，否则第一点会显示成 1。
            first.setText("序号: " + numberAt(mode, n, k));
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String s) {
        if (s == null) return -1;
        int end = s.length();
        int start = end;
        while (start > 0 && Character.isDigit(s.charAt(start - 1))) start--;
        if (start == end) return -1;
        try {
            return Integer.parseInt(s.substring(start, end));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** k = 第几个（1 开始）→ 该模式下实际应该点击的数字 */
    static int numberAt(int mode, int n, int k) {
        int total = n * n;
        switch (mode) {
            case REVERSE:
                return total - (k - 1);
            case MID:
                return (k % 2 == 1) ? (k + 1) / 2 : total - (k - 1) / 2;
            default:
                return k;
        }
    }


    /** 模式弹窗里展示的一行状态：能直接确认运行版本与接管是否成功 */
    static String statusText() {
        if (eHooked && jHooked) {
            return "已接管·重读" + (SchulteHooks.resumeHooked ? "✓" : "✗")
                    + (iHooked ? "" : "·重置未装")
                    + (SchulteHooks.officialHooked ? "" : "·官方拦截未装")
                    + (restartBlocked ? "" : "·重开拦截未装");
        }
        StringBuilder sb = new StringBuilder("未接管");
        if (!eHooked) sb.append("·e");
        if (!jHooked) sb.append("·j");
        if (installError.length() > 0) sb.append("：").append(installError);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 点击接管

    private static void onTap(XC_MethodHook.MethodHookParam param) {
        if (param.args.length != 2
                || !(param.args[0] instanceof Integer) || !(param.args[1] instanceof Integer)) {
            return; // 签名不符（按名兜底接管时）：交回 App 处理
        }
        Object view = param.thisObject;
        Context c = ((View) view).getContext();
        if (!Prefs.moduleEnabled(c)) return;
        int mode = Prefs.advMode(c);
        if (mode == MODE_OFF) return;

        Round r = round(view);
        if (r != null && r.done) {           // 刚结算完，等自动下一把：吞掉多余点击
            param.setResult(null);
            return;
        }
        if (r != null && r.mode != mode) {   // 切换过模式但本局还是旧模式
            XposedHelpers.removeAdditionalInstanceField(view, KEY_ROUND);
            r = null;
        }
        if (r == null) {
            // 关键：不再「重开一局」，而是就用当前棋盘现场起一局高级模式，
            // 彻底去掉「切换模式后点一下就重开」的表现
            r = startRound(view);
            if (r == null) return;           // 未接管（如 1start 不支持当前阶数）：交回 App
        }
        param.setResult(null); // 跳过 App 原始处理（不会触发官方结算/上传）
        handleTap(view, r, (Integer) param.args[0], (Integer) param.args[1]);
    }

    /**
     * 在切到高级模式的瞬间就重置本局（回到「准备」），
     * 让新模式下从干净的一局开始。即使这里失败，onTap 也会用当前棋盘现场起局，不会再重开。
     */
    static void resetRound(Activity act) {
        if (act == null) return;
        Object view = null;
        try {
            Object binding = XposedHelpers.getObjectField(act, "view");
            view = XposedHelpers.getObjectField(binding, "T");
        } catch (Throwable t) {
            MainHook.log("高级模式取棋盘失败: " + t);
        }
        if (view != null) {
            restartNow(view);
            return;
        }
        try {
            XposedHelpers.setAdditionalInstanceField(act, SchulteHooks.KEY_BYPASS_BUSY, Boolean.TRUE);
            try {
                XposedHelpers.callMethod(act, "V");
            } finally {
                XposedHelpers.removeAdditionalInstanceField(act, SchulteHooks.KEY_BYPASS_BUSY);
            }
        } catch (Throwable t) {
            MainHook.log("高级模式重置本局失败: " + t);
        }
    }

    /** 在棋盘上直接重开：停表、清空对局状态后调用 Activity.V() 重建本局。 */
    private static void restartNow(Object view) {
        Context c = ((View) view).getContext();
        Activity act = Ui.findActivity(c);
        stopTimer(act);
        hideOverlay(act);
        XposedHelpers.removeAdditionalInstanceField(view, KEY_ROUND);
        if (act == null) return;
        try {
            XposedHelpers.setAdditionalInstanceField(act, SchulteHooks.KEY_BYPASS_BUSY, Boolean.TRUE);
            try {
                XposedHelpers.callMethod(act, "V");
            } finally {
                XposedHelpers.removeAdditionalInstanceField(act, SchulteHooks.KEY_BYPASS_BUSY);
            }
        } catch (Throwable t) {
            MainHook.log("高级模式切换模式重开失败: " + t);
        }
    }

    private static void handleTap(Object view, Round r, int row, int col) {
        if (r == null || r.done) return;
        Context c = ((View) view).getContext();
        if (!Prefs.moduleEnabled(c)) return;

        Object g = game(view);
        if (g == null) return;
        Object cells = XposedHelpers.callMethod(g, "c");
        if (cells == null) return;

        int value;
        try {
            Object[] line = (Object[]) java.lang.reflect.Array.get(cells, row);
            if (line == null || col < 0 || col >= line.length || line[col] == null) return;
            value = (Integer) XposedHelpers.callMethod(line[col], "a");
        } catch (Throwable t) {
            return;
        }
        // 简单/简单打乱：官方会把点过的格子直接抹掉（模块置 0），再点已消失的格子不再计数
        if (r.hides && value <= 0) return;

        long now = SystemClock.elapsedRealtime();
        r.taps++;

        int want = expected(r);
        if (value != want) {
            r.errors++;
            // 计数先同步给 game，官方回调里读的是 game 的统计
            syncGame(g, r);
            officialWrong(g, value, want);   // 官方错误音效 / 震动 / TTS / 记录，全部跟随 App 设置
            updateInfo(c, r);
            // 点错不重开，只提示当前应该点哪个数（爆手速也不再因点错重开）
            SchulteHooks.toastThrottled(c, "点错了，应该点 " + want);
            return;
        }

        r.idx++;
        int found = r.idx;
        long gap = found > 1 ? now - r.lastCorrectAt : 0L;
        if (found == 1) r.reaction = now - r.startAt;
        if (gap > r.maxGap) r.maxGap = gap;

        if (r.hides) {
            // 官方 onDraw 用「格子值 <= progress」决定隐藏；倒序/归中下 progress 与已点数字不对应，
            // 因此把点过的格子值置 0、progress 固定 0 —— 消失的永远是刚点的那一个
            setCellValue(cells, row, col, 0);
            clearPress(view);
        } else {
            try {
                XposedHelpers.setIntField(view, "r", value);
                ((View) view).invalidate();
            } catch (Throwable ignored) {
            }
        }
        syncGame(g, r);
        officialRight(g, r, cells);      // 官方正确音效 + 进度显示，跟随 App 设置
        updateInfo(c, r);

        if (r.mode == MAXN && found > 1) {
            double limit = limitFor(c, r.n);
            if (gap > limit * 1000.0) {
                fail(view, r, String.format(Locale.US,
                        "间隔 %.2fs > %s，重开", gap / 1000.0, limitText(c, r.n)), 850);
                return;
            }
        }
        if (r.mode == START1 && found == 1 && r.reaction > start1LimitMs(c)) {
            fail(view, r, String.format(Locale.US,
                    "反应 %.2fs > %s，重开", r.reaction / 1000.0, start1LimitText(c)), 850);
            return;
        }

        r.lastCorrectAt = now;
        if (r.mode == SPEED) {
            // 目标 x 可能刚在弹窗里改过：以最新设置为准，保证「到 x 就结束」
            int latest = target(SPEED, r.n, Prefs.advX(c));
            if (latest != r.x) {
                r.x = latest;
                updateInfo(c, r);
            }
        }
        if (found >= r.x) complete(view, r, now);
    }

    private static Round startRound(Object view) {
        Context c = ((View) view).getContext();
        if (!Prefs.moduleEnabled(c)) return null;
        int mode = Prefs.advMode(c);
        if (mode == MODE_OFF) return null;

        Object g = game(view);
        if (g == null) return null;
        int n = (Integer) XposedHelpers.callMethod(g, "f");

        if (mode == START1 && (n < 3 || n > 5)) {
            Prefs.setAdvMode(c, MODE_OFF);
            Ui.toast(c, "1start 训练仅支持 3-5 阶，已切回顺序模式");
            return null;
        }

        Round r = new Round();
        r.mode = mode;
        r.n = n;
        r.x = target(mode, n, Prefs.advX(c));
        try {
            r.type = (Integer) XposedHelpers.callMethod(g, "l");
        } catch (Throwable ignored) {
            r.type = 0;
        }
        try {
            r.blind = (Boolean) XposedHelpers.callMethod(g, "m");
        } catch (Throwable ignored) {
            r.blind = false;
        }
        r.hides = r.type == 1 || r.type == 2;
        if (r.hides) r.orig = snapshotCells(g);
        r.startAt = SystemClock.elapsedRealtime();
        r.lastCorrectAt = r.startAt;
        // 新的一局开始：上一把成绩面板的序号改写不再生效
        chartMode = MODE_OFF;
        chartN = 0;
        chartCount = 0;
        XposedHelpers.setAdditionalInstanceField(view, KEY_ROUND, r);

        // 高级模式是在「当前官方模式」的基础上叠加出题顺序规则，
        // 不强制改成普通棋盘：简单/普通/打乱/盲玩都保持用户当前选择。
        syncGame(g, r);
        ((View) view).invalidate();
        updateInfo(c, r);
        return r;
    }

    // ------------------------------------------------------------------ 结算 / 失败

    private static void complete(Object view, Round r, long now) {
        r.done = true;
        Context c = ((View) view).getContext();
        Activity act = Ui.findActivity(c);
        long elapsed = Math.max(0L, now - r.startAt);
        // 与官方 SchulteRecorder.l() 完全一致：每点错一次成绩 +1s
        long shown = elapsed + (long) (r.taps - r.idx) * 1000L;
        String key = bestKey(r);
        long best = Prefs.getLong(c, key, 0L);
        boolean record = best <= 0 || shown < best;
        if (record) Prefs.setLong(c, key, shown);
        // 本地历史（今日统计 / 本地页 / 高级模式排名共用），只存本机不上传
        AdvStore.add(c, r.mode, r.type, r.blind, r.n, r.x, shown, r.reaction, r.errors);
        MainHook.log("高级模式完成: " + NAMES[r.mode] + " n=" + r.n + " x=" + r.x
                + " type=" + r.type + " blind=" + r.blind
                + " elapsed=" + elapsed + " shown=" + shown + " best=" + best + " record=" + record);

        clearPress(view);
        stopTimerAt(act, elapsed);
        playCompleteSound(act);
        restoreBar(act);
        showDrawerMenu(act);
        // 简单 / 简单打乱：模块把点过的格子值置 0 来隐藏，结算后（弹窗还在时）
        // 把棋盘还原成最初的样子，点空白关闭弹窗后仍能看到完整的块（与官方顺序简单一致）
        restoreCells(view, r);

        // 非新纪录：与官方模式一致，静默直接下一把，不弹成绩面板
        if (!record && Prefs.autoNextNoRecord(c)) {
            MainHook.log("高级模式非新纪录，静默自动下一把");
            restart(view, r, 220);
            return;
        }

        // 成绩显示完全交给官方弹窗：用模块回放助手的数据构造官方 SchulteRecord，
        // 再塞一个本地 SchulteSuccess（不上传、不写官方数据库），新纪录判定也走官方逻辑。
        if (!showOfficialResult(act, view, r, best, record)) {
            showScoreFallback(act, view, r, shown, best, record);
        }
    }

    /** 官方标签：按 type/blind 取官方叫法，再拼高级模式的短名，如「简单-顺序」 */
    private static String modeLabel(Round r) {
        String base;
        if (r.type == 2) base = "简单打乱";
        else if (r.type == 3) base = "打乱";
        else if (r.blind) base = (r.type == 1 ? "简单盲玩" : "盲玩");
        else base = (r.type == 1 ? "简单" : "普通");
        int mode = (r.mode >= 0 && r.mode < SHORT.length) ? r.mode : MODE_OFF;
        if (mode == MODE_OFF) return base;
        return base + "-" + SHORT[mode];
    }

    /**
     * 成绩弹窗里的「今日统计」原本只统计官方 SchulteRecordDB，
     * 高级模式不写那个库，所以永远是空的。这里改成统计模块本地的今日记录。
     */
    private static void overrideTodayStats(Object binding, Round r, Context c) {
        if (binding == null || c == null) return;
        java.util.List<AdvStore.Rec> list = AdvStore.today(c, r.type, r.blind, r.mode, r.n,
                r.mode == SPEED ? r.x : 0);
        long best = Long.MAX_VALUE;
        long sum = 0L;
        for (AdvStore.Rec rec : list) {
            if (rec.time < best) best = rec.time;
            sum += rec.time;
        }
        TextView count = (TextView) XposedHelpers.getObjectField(binding, "f0");
        TextView bestTv = (TextView) XposedHelpers.getObjectField(binding, "e0");
        TextView avg = (TextView) XposedHelpers.getObjectField(binding, "d0");
        if (count != null) count.setText("次数: " + list.size());
        if (bestTv != null) {
            bestTv.setText("最佳: " + (list.isEmpty() ? "--" : fmtApp(best, c)));
        }
        if (avg != null) {
            avg.setText("平均: " + (list.isEmpty() ? "--" : fmtApp(sum / list.size(), c)));
        }
        Object container = XposedHelpers.getObjectField(binding, "g0");
        if (container instanceof View) ((View) container).setVisibility(View.VISIBLE);
    }

    /** 用 App 自己的时间格式（utils.m.m），保持和官方统计完全一致 */
    private static String fmtApp(long ms, Context c) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.star.minesweeping.utils.m", c.getClassLoader());
            if (cls != null) {
                Object s = XposedHelpers.callStaticMethod(cls, "m", Math.max(0L, ms));
                if (s instanceof String) return (String) s;
            }
        } catch (Throwable ignored) {
        }
        return fmt(ms);
    }

    /** 官方成绩面板不可用时的兜底：自绘一层同样的成绩信息，随后自动下一把 */
    private static void showScoreFallback(Activity act, Object view, Round r,
                                          long shown, long best, boolean record) {
        if (act == null) return;
        String sub = String.format(Locale.US, "反应 %.2fs · 错误 %d · 最佳 %s%s",
                Math.max(0L, r.reaction) / 1000.0, r.errors,
                best <= 0 ? "--" : fmt(record ? shown : best),
                record ? " · 新纪录" : "");
        showOverlay(act, r.mode, modeLabel(r), fmt(shown), sub, accent(r.mode), false);
        restart(view, r, 1500);
    }

    /**
     * 用官方成绩弹窗（k.b.n4.o）展示本局。
     * 数据来自 App 自己的 SchulteRecorder（点击时已经通过 $b.a/$b.c 写入），
     * 因此时间/误点/回放曲线与官方完全一致；只是不上传服务器、不写官方记录库。
     * 返回 false 表示官方入口不可用（版本不符），由调用方兜底自动下一把。
     */
    private static boolean showOfficialResult(final Activity act, final Object view, final Round r,
                                              long bestBefore, boolean isRecord) {
        if (act == null) return false;
        try {
            ClassLoader cl = act.getClassLoader();
            Object rec = XposedHelpers.getObjectField(act, "i");
            if (rec == null) return false;
            Object timer = XposedHelpers.getObjectField(act, "e");
            if (timer == null) return false;
            // 与官方 $b.d 一致：先冻结表、把成绩写进回放助手，再 l() 生成官方 SchulteRecord
            long time = (Long) XposedHelpers.callMethod(timer, "e");
            if (time <= 0) time = 1L;
            XposedHelpers.callMethod(rec, "t", time);
            XposedHelpers.callMethod(rec, "u", r.taps);
            XposedHelpers.callMethod(rec, "p", r.idx);
            // 反应时间用本局点 1 的实测值（官方是在第 2 个正确点击时取表，短局会显示 0）
            try {
                XposedHelpers.callMethod(rec, "s", (int) Math.max(1L, r.reaction));
            } catch (Throwable ignored) {
            }
            Object record = XposedHelpers.callMethod(rec, "l");
            if (record == null) return false;
            // l() 里的时间是「用时 + 误点次数 × 1s」，与实际展示保持一致
            time = (Long) XposedHelpers.callMethod(record, "getTime");

            Class<?> successCls = XposedHelpers.findClass(SUCCESS, cl);
            Object success = XposedHelpers.newInstance(successCls);
            XposedHelpers.callMethod(success, "setBestTime", bestBefore);
            XposedHelpers.callMethod(success, "setTime", time);
            XposedHelpers.callMethod(success, "setTap", r.taps);
            XposedHelpers.callMethod(success, "setTapCorrect", r.idx);
            XposedHelpers.callMethod(success, "setLevel", r.n);
            XposedHelpers.callMethod(success, "setRankPercent", 0f);
            XposedHelpers.callMethod(success, "setOldRank", 0);
            XposedHelpers.callMethod(success, "setNewRank", 0);
            XposedHelpers.callMethod(success, "setCoin", 0);
            XposedHelpers.callMethod(success, "setRecordId", 0);

            Class<?> dlgCls = XposedHelpers.findClass(RESULT_DIALOG, cl);
            Class<?> callbackCls = XposedHelpers.findClass(RESULT_LISTENER, cl);
            // 必须在构造弹窗之前设置：官方 t() 在构造里就要按模式收敛曲线 X 轴
            chartMode = r.mode;
            chartN = r.n;
            chartCount = Math.max(1, r.x);
            Object dlg = XposedHelpers.newInstance(dlgCls,
                    new Class<?>[]{record.getClass(), successCls, boolean.class},
                    record, success, Boolean.FALSE);
            // 标题改成「简单-顺序」这类高级模式标签（官方只认 type/blind，不会带高级模式信息）
            try {
                Object binding = XposedHelpers.getObjectField(dlg, "b");
                TextView modeTv = (TextView) XposedHelpers.getObjectField(binding, "T");
                modeTv.setText(modeLabel(r));
                overrideTodayStats(binding, r, act);
            } catch (Throwable t) {
                MainHook.log("高级模式成绩标题改写失败: " + t);
            }
            XposedHelpers.callMethod(dlg, "u", Proxy.newProxyInstance(cl,
                    new Class<?>[]{callbackCls}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            // 只有面板里的「重开」会重开一局；点空白处只是关闭面板，停留在上一把
                            if ("a".equals(method.getName())) restart(view, r, 0);
                            return null;
                        }
                    }));
            ((Dialog) dlg).show();
            if (!((Dialog) dlg).isShowing()) {
                // 官方 f4.c.show() 会静默吞异常，这里必须自己确认窗口真的出来了
                chartMode = MODE_OFF;
                chartN = 0;
                chartCount = 0;
                MainHook.log("高级模式官方成绩面板未显示: " + modeLabel(r));
                return false;
            }
            // 与官方 $c.z 一致：左下角出现成绩图标，关闭面板后点它可以重新打开
            showResultIcon(act, (Dialog) dlg);
            MainHook.log("高级模式完成，已按官方成绩显示: " + modeLabel(r) + " " + time + "ms"
                    + " 新纪录=" + isRecord);
            return true;
        } catch (Throwable t) {
            chartMode = MODE_OFF;
            chartN = 0;
            chartCount = 0;
            MainHook.log("高级模式官方结算失败: " + t);
            return false;
        }
    }

    /** 官方 $c.z 同款：把「成绩」图标显示在左下角，点了再弹一次成绩面板 */
    private static void showResultIcon(Activity act, final Dialog dlg) {
        if (act == null) return;
        try {
            Object binding = XposedHelpers.getObjectField(act, "view");
            Object bar = XposedHelpers.getObjectField(binding, "S");
            if (bar == null) return;
            XposedHelpers.callMethod(bar, "o", Boolean.TRUE);
            XposedHelpers.callMethod(bar, "setResultListener", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        dlg.show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            MainHook.log("高级模式成绩图标显示失败: " + t);
        }
    }

    private static void fail(Object view, Round r, String why, long restartDelay) {
        r.done = true;
        Context c = ((View) view).getContext();
        Activity act = Ui.findActivity(c);
        stopTimer(act);
        clearPress(view);
        // 规则失败提示（1start 反应超时 / max≤N 间隔超时）是提示而不是成绩，
        // 不受「非新纪录不展示成绩」影响，始终显示
        MainHook.log("高级模式规则失败: " + NAMES[r.mode] + " " + why);
        showFail(act, r, why);
        restart(view, r, restartDelay);
    }

    private static void restart(final Object view, final Round r, long delay) {
        final Activity act = Ui.findActivity(((View) view).getContext());
        if (act == null) return;
        Ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (round(view) != r || !r.done) return; // 期间被别的重开/切模式取代
                if (!Ui.isAlive(act)) return;
                hideOverlay(act);
                try {
                    XposedHelpers.setAdditionalInstanceField(
                            act, SchulteHooks.KEY_BYPASS_BUSY, Boolean.TRUE);
                    try {
                        XposedHelpers.callMethod(act, "V");
                    } finally {
                        XposedHelpers.removeAdditionalInstanceField(act, SchulteHooks.KEY_BYPASS_BUSY);
                    }
                } catch (Throwable t) {
                    MainHook.log("高级模式自动下一把失败: " + t);
                    try {
                        XposedHelpers.callMethod(view, "h");
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, Math.max(60L, delay));
    }

    // ------------------------------------------------------------------ 失败提示

    private static void showFail(Activity act, Round r, String why) {
        if (act == null) return;
        showOverlay(act, r.mode, NAMES[r.mode], why,
                r.n + "×" + r.n + "  ·  本地记录，自动下一把", accent(r.mode), true);
    }

    private static void showOverlay(Activity act, int mode, String title, String big,
                                    String sub, int accent, boolean fail) {
        try {
            View content = act.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) content;
            hideOverlay(act);

            FrameLayout layer = new FrameLayout(act);
            layer.setTag(KEY_OVERLAY_TAG);
            layer.setClickable(false);
            layer.setFocusable(false);

            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setClickable(false);
            int padH = Ui.dp(act, 22);
            int padV = Ui.dp(act, 16);
            card.setPadding(padH, padV, padH, padV);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE171A20);
            bg.setCornerRadius(Ui.dp(act, 16));
            bg.setStroke(Ui.dp(act, 2), accent);
            card.setBackground(bg);

            TextView titleView = new TextView(act);
            titleView.setText(title);
            titleView.setTextSize(14);
            titleView.setTextColor(accent);
            titleView.getPaint().setFakeBoldText(true);
            titleView.setGravity(Gravity.CENTER);
            card.addView(titleView);

            TextView bigView = new TextView(act);
            bigView.setText(big);
            bigView.setTextSize(fail ? 24 : 38);
            bigView.setTextColor(0xFFFFFFFF);
            bigView.getPaint().setFakeBoldText(true);
            bigView.setGravity(Gravity.CENTER);
            bigView.setPadding(0, Ui.dp(act, 6), 0, 0);
            card.addView(bigView);

            TextView subView = new TextView(act);
            subView.setText(sub);
            subView.setTextSize(11);
            subView.setTextColor(0xB3FFFFFF);
            subView.setGravity(Gravity.CENTER);
            subView.setPadding(0, Ui.dp(act, 7), 0, 0);
            card.addView(subView);

            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.gravity = Gravity.CENTER;
            cardLp.leftMargin = Ui.dp(act, 30);
            cardLp.rightMargin = Ui.dp(act, 30);
            layer.addView(card, cardLp);
            root.addView(layer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            animateIn(card, mode, fail);
        } catch (Throwable t) {
            MainHook.log("高级模式完成效果失败: " + t);
        }
    }

    /** 每个模式一套入场效果（位移动画/弹性缩放/弹出速度不同）。 */
    private static void animateIn(View card, int mode, boolean fail) {
        card.setAlpha(0f);
        if (fail) {
            card.setScaleX(0.85f);
            card.setScaleY(0.85f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(140).start();
        } else {
            switch (mode) {
                case SPEED:
                    card.setScaleX(1.5f);
                    card.setScaleY(1.5f);
                    card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case REVERSE:
                    card.setTranslationY(-Ui.dp(card.getContext(), 90));
                    card.animate().alpha(1f).translationY(0f)
                            .setDuration(230).setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MID:
                    card.setScaleX(0.65f);
                    card.setScaleY(0.65f);
                    card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(260).setInterpolator(new OvershootInterpolator(1.6f)).start();
                    break;
                case MAXN:
                    card.setTranslationY(Ui.dp(card.getContext(), 90));
                    card.animate().alpha(1f).translationY(0f)
                            .setDuration(230).setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case START1:
                    card.setScaleX(0.5f);
                    card.setScaleY(0.5f);
                    card.animate().alpha(1f).scaleX(1.08f).scaleY(1.08f)
                            .setDuration(220).setInterpolator(new OvershootInterpolator(2f)).start();
                    Ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            card.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        }
                    }, 230);
                    break;
                default:
                    card.animate().alpha(1f).setDuration(160).start();
            }
        }
        // 淡出放在入场动画结束后再启动，避免同一次 animate() 链把 alpha 目标改成 0 而打断入场
        Ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                card.animate().alpha(0f).setDuration(200).start();
            }
        }, fail ? 480 : 1050);
    }

    private static void hideOverlay(Activity act) {
        if (act == null) return;
        try {
            View content = act.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            View old = ((ViewGroup) content).findViewWithTag(KEY_OVERLAY_TAG);
            if (old != null) ((ViewGroup) content).removeView(old);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Round round(Object view) {
        Object o = XposedHelpers.getAdditionalInstanceField(view, KEY_ROUND);
        return o instanceof Round ? (Round) o : null;
    }

    private static Object game(Object view) {
        try {
            return XposedHelpers.getObjectField(view, "a");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String statusName(Object game) {
        if (game == null) return "";
        try {
            Object s = XposedHelpers.callMethod(game, "h");
            return s instanceof Enum ? ((Enum<?>) s).name() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static int expected(Round r) {
        int total = r.n * r.n;
        switch (r.mode) {
            case REVERSE:
                return total - r.idx;
            case MID:
                return (r.idx % 2 == 0) ? r.idx / 2 + 1 : total - r.idx / 2;
            default:
                return r.idx + 1;
        }
    }

    private static int target(int mode, int n, int xPref) {
        int total = n * n;
        if (mode == SPEED) {
            if (xPref <= 0) return total;
            return Math.min(xPref, total);
        }
        return total;
    }

    /** 1start 开局点 1 的反应上限（ms）：优先用模块设置里的自定义值，未设置时用内置 450ms */
    private static long start1LimitMs(Context c) {
        int ms = Prefs.advStart1Ms(c);
        return ms > 0 ? ms : START1_LIMIT_MS;
    }

    /** 1start 的内置反应上限（ms）：弹窗里给「留空 = 内置 …」的提示用 */
    static long builtinStart1Ms() {
        return START1_LIMIT_MS;
    }

    private static String start1LimitText(Context c) {
        return String.format(Locale.US, "%.2fs%s",
                start1LimitMs(c) / 1000.0, Prefs.advStart1Ms(c) > 0 ? "（自定义）" : "");
    }

    /** max≤N 的内置间隔参考值（ms）：弹窗里给「留空 = 内置 …」的提示用 */
    static long builtinMaxGapMs(int n) {
        if (n >= 3 && n <= 10) return Math.round(MAX_GAP[n] * 1000f);
        return 1000L;
    }

    /** max≤N 的间隔上限（秒）：优先用模块设置里的自定义 ms，未设置时用内置参考表 */
    private static double limitFor(Context c, int n) {
        int ms = Prefs.advMaxGapMs(c);
        if (ms > 0) return ms / 1000.0;
        if (n >= 3 && n <= 10) return MAX_GAP[n];
        return 1.0;
    }

    private static String limitText(Context c, int n) {
        double limit = limitFor(c, n);
        return String.format(Locale.US, "%.2fs%s",
                limit, Prefs.advMaxGapMs(c) > 0 ? "（自定义）" : "");
    }

    private static void syncGame(Object game, Round r) {
        try {
            XposedHelpers.callMethod(game, "x", r.taps);
            XposedHelpers.callMethod(game, "v", r.idx);
            XposedHelpers.callMethod(game, "w", r.errors);
            // 简单/简单打乱下官方 onDraw 靠 progress 隐藏格子；这里固定 0，让「消失」只由置 0 的格子决定
            XposedHelpers.callMethod(game, "r", r.hides ? 0 : r.idx);
        } catch (Throwable ignored) {
        }
    }

    /** 复制一份当前棋盘数字，供简单模式结算后还原（0 表示隐藏的格子） */
    private static int[][] snapshotCells(Object g) {
        try {
            Object cells = XposedHelpers.callMethod(g, "c");
            if (cells == null) return null;
            int rows = java.lang.reflect.Array.getLength(cells);
            int[][] copy = new int[rows][];
            for (int i = 0; i < rows; i++) {
                Object line = java.lang.reflect.Array.get(cells, i);
                if (line == null) continue;
                int cols = java.lang.reflect.Array.getLength(line);
                copy[i] = new int[cols];
                for (int j = 0; j < cols; j++) {
                    Object cell = java.lang.reflect.Array.get(line, j);
                    if (cell == null) continue;
                    Object v = XposedHelpers.callMethod(cell, "a");
                    copy[i][j] = v instanceof Integer ? (Integer) v : 0;
                }
            }
            return copy;
        } catch (Throwable t) {
            MainHook.log("高级模式棋盘快照失败: " + t);
            return null;
        }
    }

    /** 把点掉的格子还原成最初的样子（只影响棋盘显示，不影响已写入的成绩/回放） */
    private static void restoreCells(Object view, Round r) {
        if (r == null || r.orig == null) return;
        try {
            Object g = game(view);
            if (g == null) return;
            Object cells = XposedHelpers.callMethod(g, "c");
            if (cells == null) return;
            int rows = Math.min(r.orig.length, java.lang.reflect.Array.getLength(cells));
            for (int i = 0; i < rows; i++) {
                int[] origLine = r.orig[i];
                if (origLine == null) continue;
                for (int j = 0; j < origLine.length; j++) {
                    if (origLine[j] > 0) setCellValue(cells, i, j, origLine[j]);
                }
            }
            // 与官方点满一局后的状态一致：Finished + 进度 -1 + 显示数字，
            // 这样官方 onDraw 的隐藏条件（type 1/2 且 值<=进度 且 非 Finished）不再成立
            try {
                Class<?> stCls = XposedHelpers.findClass(
                        "com.star.minesweeping.module.game.schulte.core.d",
                        g.getClass().getClassLoader());
                Object finished = XposedHelpers.getStaticObjectField(stCls, "d");
                XposedHelpers.callMethod(g, "u", finished);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.setBooleanField(view, "w", false);
            } catch (Throwable ignored) {
            }
            XposedHelpers.setIntField(view, "r", -1);
            ((View) view).invalidate();
        } catch (Throwable t) {
            MainHook.log("高级模式棋盘还原失败: " + t);
        }
    }

    /** 把点过的格子值改为 0：官方 onDraw 在 type 1/2 下会跳过 value <= progress(0) 的格子 */
    private static void setCellValue(Object cells, int row, int col, int value) {
        try {
            Object[] line = (Object[]) java.lang.reflect.Array.get(cells, row);
            if (line == null || col < 0 || col >= line.length || line[col] == null) return;
            XposedHelpers.callMethod(line[col], "b", value);
        } catch (Throwable ignored) {
        }
    }

    private static Object tapListener(Object game) {
        try {
            return XposedHelpers.callMethod(game, "g");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 复用官方 $b.a：正确音效、进度显示、回放记录全部跟随 App 的设置 */
    private static void officialRight(Object game, Round r, Object cells) {
        Object listener = tapListener(game);
        if (listener == null) return;
        try {
            XposedHelpers.callMethod(listener, "a", r.idx + 1, r.n * r.n, cells);
        } catch (Throwable t) {
            MainHook.log("高级模式正确点击官方回调失败: " + t);
        }
    }

    /** 复用官方 $b.c：错误音效、震动、TTS、误点计数全部跟随 App 的设置 */
    private static void officialWrong(Object game, int value, int want) {
        Object listener = tapListener(game);
        if (listener == null) return;
        try {
            XposedHelpers.callMethod(listener, "c", value, want);
        } catch (Throwable t) {
            MainHook.log("高级模式错误点击官方回调失败: " + t);
        }
    }

    private static void updateInfo(Context c, Round r) {
        try {
            Activity act = Ui.findActivity(c);
            if (act == null) return;
            Object info = XposedHelpers.getObjectField(act, "d");
            if (info == null) return;
            XposedHelpers.callMethod(info, "setProgress", r.idx + 1);
            XposedHelpers.callMethod(info, "setError", r.errors);
        } catch (Throwable ignored) {
        }
    }

    private static void stopTimer(Activity act) {
        if (act == null) return;
        try {
            Object timer = XposedHelpers.getObjectField(act, "e");
            if (timer != null) XposedHelpers.callMethod(timer, "l");
        } catch (Throwable ignored) {
        }
    }

    /** 冻结计时器在 elapsed，官方弹窗/记录读到的用时就是本局用时（不触发重置） */
    private static void stopTimerAt(Activity act, long elapsed) {
        if (act == null) return;
        try {
            Object timer = XposedHelpers.getObjectField(act, "e");
            if (timer != null) XposedHelpers.callMethod(timer, "p", elapsed);
        } catch (Throwable ignored) {
        }
    }

    private static void playCompleteSound(Activity act) {
        if (act == null) return;
        try {
            Object sound = XposedHelpers.getObjectField(act, "h");
            if (sound != null) XposedHelpers.callMethod(sound, "a", 0x7f0f000b);
        } catch (Throwable ignored) {
        }
    }

    private static void restoreBar(Activity act) {
        if (act == null) return;
        try {
            Object binding = XposedHelpers.getObjectField(act, "view");
            Object bar = XposedHelpers.getObjectField(binding, "S");
            if (bar != null) XposedHelpers.callMethod(bar, "n", Boolean.FALSE);
        } catch (Throwable ignored) {
        }
    }

    /** 与官方完成回调一致：先把侧边菜单调出来，成绩弹窗再盖在上面 */
    private static void showDrawerMenu(Activity act) {
        if (act == null) return;
        try {
            XposedHelpers.callMethod(act, "showDrawerMenu");
        } catch (Throwable ignored) {
        }
    }

    private static void clearPress(Object view) {
        try {
            XposedHelpers.setIntField(view, "r", -1);
            ((View) view).invalidate();
        } catch (Throwable ignored) {
        }
    }

    private static boolean advancedActivity(Object helper) {
        try {
            Object callback = XposedHelpers.getObjectField(helper, "e");
            Object act = XposedHelpers.getObjectField(callback, "a");
            return act instanceof Activity
                    && Prefs.moduleEnabled((Activity) act)
                    && Prefs.advMode((Activity) act) != MODE_OFF;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最佳记录按 官方模式(type/blind) × 高级模式 × 阶数 分开，爆手速再带上目标 x */
    private static String bestKey(Round r) {
        return "adv_best_" + r.type + "_" + (r.blind ? 1 : 0) + "_" + r.mode + "_" + r.n
                + (r.mode == SPEED ? "_" + r.x : "");
    }

    static String fmt(long ms) {
        return String.format(Locale.US, "%.2fs", Math.max(0L, ms) / 1000.0);
    }

    static int accent(int mode) {
        switch (mode) {
            case SPEED:
                return ACCENT_SPEED;
            case REVERSE:
                return ACCENT_REVERSE;
            case MID:
                return ACCENT_MID;
            case MAXN:
                return ACCENT_MAXN;
            case START1:
                return ACCENT_START1;
            default:
                return 0xFF8C8C8C;
        }
    }
}
