package com.tapsss.lsposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 2048（tzfe）增强：
 * 1. 棋盘尺寸 3×3 – 10×10 + 自定义 (3–10)×(3–10)，目标分数 = 2^(3×短边−1)；
 * 2. 难度弹窗改为方格样式（目标分数写在按钮底部），底部加「2048设置」「模块设置」；
 * 3. 游戏页添加「撤销」按钮；用过撤销的这局成绩只留本地、不上传；
 * 4. 成绩弹窗按短边目标分数适配；
 * 5. 排行榜改为「尺寸主标签 + 时间/分数子标签」。
 */
public final class TZFEHooks {

    private static final String CLS_ACT =
            "com.star.minesweeping.ui.activity.game.tzfe.TZFEGameActivity";
    private static final String CLS_GAME = "com.star.minesweeping.i.c.f.d.a.d";
    private static final String CLS_CELL = "com.star.minesweeping.i.c.f.d.a.b";
    private static final String CLS_UTIL = "com.star.minesweeping.i.c.f.d.b.a";
    private static final String CLS_VIEW =
            "com.star.minesweeping.module.game.tzfe.core.view.TZFEView";
    private static final String CLS_HELPER = "com.star.minesweeping.i.c.f.a";
    private static final String CLS_LEVEL_DIALOG = "com.star.minesweeping.k.b.p4.l";
    private static final String CLS_RESULT_DIALOG = "com.star.minesweeping.k.b.p4.m";
    private static final String CLS_RESULT_BASE = "com.star.minesweeping.k.b.f4.e";
    private static final String CLS_SAVE =
            "com.star.minesweeping.data.bean.game.other.TZFESave";
    private static final String CLS_RECORD =
            "com.star.minesweeping.data.api.game.tzfe.TZFERecord";
    private static final String CLS_LOGIN = "com.star.minesweeping.utils.r.n";
    private static final String CLS_API = "com.star.api.d.a";
    private static final String CLS_RANK_ACT =
            "com.star.minesweeping.ui.activity.rank.Rank2048Activity";
    private static final String CLS_RANK_FRAG = "com.star.minesweeping.k.c.h.g.f";
    private static final String CLS_RANK_COUNT = "com.star.minesweeping.utils.n.s.g.b";
    private static final String CLS_SETTING_TZFE =
            "com.star.minesweeping.ui.activity.setting.game.SettingTZFEActivity";

    private static final String KEY_NO_UPLOAD = "tapsss_tzfe_no_upload";
    private static final String KEY_UNDO_BTN = "tapsss_tzfe_undo_btn";
    private static final String KEY_UNDO_STACK = "tapsss_tzfe_undo_stack";
    private static final String KEY_RANK_CHIPS = "tapsss_tzfe_rank_chips";
    private static final String KEY_FROM_SETTINGS = "tapsss_tzfe_from_settings";
    private static final String KEY_RELOAD_POSTED = "tapsss_tzfe_reload_posted";
    /** 进程被杀后「本局不上传」也要活下来，跟存档一起跨启动 */
    private static final String SP_NAME = "tapsss_tzfe_state";
    private static final String SP_NO_UPLOAD = "no_upload";

    private static final int ACCENT = 0xFF4C8DFF;
    private static final int[] SIZES = {3, 4, 5, 6, 7, 8, 9, 10};

    /** 当前正在显示的 2048 游戏页（难度弹窗需要读 Activity.j 的真实尺寸） */
    private static volatile WeakReference<Activity> current =
            new WeakReference<>(null);

    /**
     * 本局用过撤销。放在静态字段里而不是 Activity 上：
     * 从 2048 设置返回会整页重建、退出后重进会从存档恢复，Activity 附加字段都会丢，
     * 只要这局棋还没真正重开（d0 读到空存档 / 切换尺寸），就一直保持「不上传」。
     */
    private static volatile boolean sessionNoUpload;

    /** 一次调用链内的上下文：修正 w0 的数组越界、目标分数与上传开关 */
    private static final ThreadLocal<Ctx> CTX = new ThreadLocal<>();

    private static final class Ctx {
        int row;
        int col;
        int target;
        boolean clamp;
        boolean noUpload;
    }

    /** 一步棋之前的完整状态，用于撤销 */
    private static final class Snap {
        Object cells;
        long score;
        long seed;
        int seedCount;
    }

    private static final class RankChips {
        View[] sizes;
        View[] types;
        int sizeIdx;
        int typeIdx;
    }

    private TZFEHooks() {
    }

    static void install(ClassLoader cl) {
        guard("棋盘", () -> installGame(cl));
        guard("游戏页", () -> installActivity(cl));
        guard("存档", () -> installSaves(cl));
        guard("弹窗", () -> installDialogs(cl));
        guard("设置页", () -> installSetting(cl));
        guard("排行榜", () -> installRank(cl));
    }

    /** 每个 Hook 单独兜底：任何一个失败都不能拖累后面的（尤其是不上传开关） */
    private static void guard(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            MainHook.log("TZFE " + what + " Hook 失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 棋盘/分数

    private static void installGame(ClassLoader cl) {
        Class<?> gameCls = XposedHelpers.findClass(CLS_GAME, cl);

        // 自定义尺寸编码：code = row*100+col（>=300）。方形 3..10 直接传 n。
        guard("x(int)", () -> XposedHelpers.findAndHookMethod(gameCls, "x", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int code = (Integer) param.args[0];
                        if (code >= 300) param.args[0] = code / 100;
                    }
                }));
        guard("t(int)", () -> XposedHelpers.findAndHookMethod(gameCls, "t", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int code = (Integer) param.args[0];
                        if (code >= 300) param.args[0] = code % 100;
                    }
                }));

        // 新开/恢复棋局时目标分数按短边重算
        guard("r(cells,score)", () -> XposedBridge.hookAllMethods(gameCls, "r",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length != 2 || !(param.args[1] instanceof Long)) return;
                        Object game = param.thisObject;
                        int row = (Integer) XposedHelpers.callMethod(game, "g");
                        int col = (Integer) XposedHelpers.callMethod(game, "c");
                        if (row < 3 || col < 3) return;
                        Ctx prev = CTX.get();
                        Ctx c = new Ctx();
                        c.row = row;
                        c.col = col;
                        c.target = targetOf(Math.min(row, col));
                        c.clamp = false;
                        param.setObjectExtra("prev", prev);
                        param.setObjectExtra("applied", Boolean.TRUE);
                        CTX.set(c);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 只有 before 真的改过上下文才恢复，避免把外层的 ctx 清掉
                        if (param.getObjectExtra("applied") == null) return;
                        CTX.set((Ctx) param.getObjectExtra("prev"));
                    }
                }));

        // w0 里按 g()-3 取数组，row>5 会越界：只在 w0 期间夹到 5
        guard("g()", () -> XposedHelpers.findAndHookMethod(gameCls, "g",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Ctx c = CTX.get();
                        if (c != null && c.clamp) {
                            param.setResult(Math.min(c.row, 5));
                        }
                    }
                }));

        // 目标分数：ctx 优先；否则 3..10 → 3n−1
        guard("TZFEUtil.b(int)", () -> XposedHelpers.findAndHookMethod(CLS_UTIL, cl,
                "b", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Ctx c = CTX.get();
                        if (c != null) {
                            param.setResult(c.target);
                            return;
                        }
                        int size = (Integer) param.args[0];
                        if (size >= 3 && size <= 10) param.setResult(3 * size - 1);
                    }
                }));

        // 上传接口的 row/column 用的是被夹过的 g()，这里改回真实值
        guard("api.j", () -> XposedHelpers.findAndHookMethod(CLS_API, cl, "j", int.class,
                String.class, int.class, int.class, long.class, long.class, int.class,
                String.class, long.class, String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Ctx c = CTX.get();
                        if (c == null) return;
                        param.args[2] = c.row;
                        param.args[3] = c.col;
                    }
                }));

        // TZFERecord 也走被夹过的 g()，恢复真实行
        guard("TZFERecord.setRow", () -> XposedHelpers.findAndHookMethod(CLS_RECORD, cl,
                "setRow", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Ctx c = CTX.get();
                        if (c == null || !c.clamp) return;
                        XposedHelpers.setIntField(param.thisObject, "row", c.row);
                        param.setResult(null);
                    }
                }));

        // 撤销过的这局：强制走本地成绩分支，不上传
        guard("登录态 e()", () -> XposedHelpers.findAndHookMethod(CLS_LOGIN, cl, "e",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Ctx c = CTX.get();
                        if (c != null && c.noUpload) {
                            MainHook.log("TZFE 本局用过撤销，跳过上传");
                            param.setResult(Boolean.FALSE);
                        }
                    }
                }));
    }

    // ------------------------------------------------------------------ 游戏页/撤销

    private static void installActivity(ClassLoader cl) {
        Class<?> actCls = XposedHelpers.findClass(CLS_ACT, cl);
        Class<?> saveCls = XposedHelpers.findClass(CLS_SAVE, cl);
        Class<?> viewCls = XposedHelpers.findClass(CLS_VIEW, cl);

        Class<?> cellCls = XposedHelpers.findClass(CLS_CELL, cl);

        // 每次落子前保存快照，落子后若棋盘真的变了才入撤销栈
        guard("TZFEView.a(I)", () -> XposedBridge.hookAllMethods(viewCls, "a",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length != 1 || !(param.args[0] instanceof Integer)) return;
                        Object view = param.thisObject;
                        Object game = XposedHelpers.callMethod(view, "getGame");
                        Snap snap = capture(cellCls, game);
                        if (snap != null) param.setObjectExtra("snap", snap);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object extra = param.getObjectExtra("snap");
                        if (!(extra instanceof Snap)) return;
                        Object view = param.thisObject;
                        Object game = XposedHelpers.callMethod(view, "getGame");
                        Snap before = (Snap) extra;
                        Object nowCells = cellsOf(game);
                        if (sameGrid(cellCls, before.cells, nowCells)) return;
                        List<Snap> stack = stackOf(view);
                        if (stack == null) {
                            stack = new ArrayList<>();
                            XposedHelpers.setAdditionalInstanceField(view, KEY_UNDO_STACK, stack);
                        }
                        if (stack.size() >= 200) stack.remove(0);
                        stack.add(before);
                        Activity act = activityOf((View) view);
                        if (act != null) refreshUndo(act);
                    }
                }));

        // 新开一局 → 清空撤销栈；同时重开意味着上一局已结束，不上传标记也一起清
        guard("s0()", () -> XposedHelpers.findAndHookMethod(actCls, "s0",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        clearUndo((Activity) param.thisObject);
                    }
                }));

        // 恢复存档：读到空存档说明是真正的新局，这时才清除「不上传」；
        // 读到有效存档说明这一局还在继续（退出重进 / 设置返回重建），标记必须保留
        guard("d0(save)", () -> XposedHelpers.findAndHookMethod(actCls, "d0", saveCls,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        clearUndo(act);
                        try {
                            Object save = param.args[0];
                            int row = (Integer) XposedHelpers.callMethod(save, "getRow");
                            if (row <= 0) {
                                // 没有存档 = 这局真的结束了/没保存，标记作废
                                sessionNoUpload = false;
                                setPersistNoUpload(act, false);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }));

        // 切换棋盘尺寸 = 开新局，撤销标记失效
        guard("l0(int)", () -> XposedHelpers.findAndHookMethod(actCls, "l0", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sessionNoUpload = false;
                        setPersistNoUpload((Activity) param.thisObject, false);
                    }
                }));

        // 结算（含达到目标时）：修正 w0 的数组下标、目标分数与上传
        guard("w0", () -> XposedHelpers.findAndHookMethod(actCls, "w0",
                long.class, int.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        Object game = XposedHelpers.getObjectField(act, "b");
                        Ctx prev = CTX.get();
                        Ctx c = new Ctx();
                        c.row = (Integer) XposedHelpers.callMethod(game, "g");
                        c.col = (Integer) XposedHelpers.callMethod(game, "c");
                        c.target = targetOf(Math.min(c.row, c.col));
                        c.clamp = true;
                        c.noUpload = sessionNoUpload
                                || getAdd(act, KEY_NO_UPLOAD) != null;
                        param.setObjectExtra("prev", prev);
                        param.setObjectExtra("ctx", c);
                        CTX.set(c);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getObjectExtra("ctx") == null) return;
                        CTX.set((Ctx) param.getObjectExtra("prev"));
                    }
                }));

        guard("init", () -> XposedHelpers.findAndHookMethod(actCls, "init",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        current = new WeakReference<>(act);
                        // 进程重启后先看持久化标记：这局是从「用过撤销」的存档恢复的
                        if (getPersistNoUpload(act)) sessionNoUpload = true;
                        clearUndo(act);
                        addUndoButton(act);
                    }
                }));

        guard("onDestroy", () -> XposedHelpers.findAndHookMethod(actCls, "onDestroy",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        Activity shown = current.get();
                        if (shown == act) current = new WeakReference<>(null);
                    }
                }));

        // 从「2048 设置」返回：整页重建，让显示/触控/摇杆等设置立即生效
        final XC_MethodHook reload = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object self = param.thisObject;
                if (!CLS_ACT.equals(self.getClass().getName())) return;
                Activity act = (Activity) self;
                if (getAdd(act, KEY_FROM_SETTINGS) == null) return;
                XposedHelpers.removeAdditionalInstanceField(act, KEY_FROM_SETTINGS);
                reloadFromSettings(act);
            }
        };
        for (Class<?> c = actCls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                XposedHelpers.findAndHookMethod(c, "onResume", reload);
                break;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 重建前先把当前棋局写进官方存档，重建后由 s0 → r0 → d0 自动恢复 */
    private static void reloadFromSettings(final Activity act) {
        if (getAdd(act, KEY_RELOAD_POSTED) != null) return;
        XposedHelpers.setAdditionalInstanceField(act, KEY_RELOAD_POSTED, Boolean.TRUE);
        syncLevelExtra(act);
        boolean saved = true;
        try {
            Object gaming = XposedHelpers.callMethod(act, "isGaming");
            if (Boolean.TRUE.equals(gaming)) {
                // h0 与退出时的自动存档同一个实现（g0），但不弹「存档成功」
                Object ok = XposedHelpers.callMethod(act, "h0");
                saved = Boolean.TRUE.equals(ok);
            }
        } catch (Throwable t) {
            saved = false;
            MainHook.log("重建前保存 2048 棋局失败: " + t);
        }
        if (!saved) {
            // 存档没写成时不能重建，否则这局棋就丢了
            XposedHelpers.removeAdditionalInstanceField(act, KEY_RELOAD_POSTED);
            Ui.toast(act, "保存棋局失败，设置将在本局结束后生效");
            return;
        }
        MainHook.log("从 2048 设置返回，整页重建");
        Ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Ui.isAlive(act)) act.recreate();
                } catch (Throwable t) {
                    MainHook.log("2048 整页重建失败: " + t);
                }
            }
        });
    }

    /**
     * recreate 会复用原 Intent，而 ARouter 的 level 是从 Intent 里注入的：
     * 不写回当前尺寸的话，从排行榜按旧尺寸进来、中途换过尺寸时重建会读错存档。
     */
    private static void syncLevelExtra(Activity act) {
        try {
            Object game = XposedHelpers.getObjectField(act, "b");
            if (game == null) return;
            int row = (Integer) XposedHelpers.callMethod(game, "g");
            int col = (Integer) XposedHelpers.callMethod(game, "c");
            if (row < 3 || row > 10 || col < 3 || col > 10) return;
            Intent it = act.getIntent();
            if (it != null) it.putExtra("level", row == col ? row : row * 100 + col);
        } catch (Throwable t) {
            MainHook.log("2048 重建前同步棋盘尺寸失败: " + t);
        }
    }

    private static void addUndoButton(final Activity act) {
        try {
            Object binding = XposedHelpers.getObjectField(act, "view");
            if (binding == null) return;
            if (getAdd(act, KEY_UNDO_BTN) != null) return;
            Object gaming = XposedHelpers.getObjectField(binding, "T");
            if (!(gaming instanceof ViewGroup)) return;

            TextView tv = new TextView(act);
            tv.setText("撤销");
            tv.setTextSize(14);
            tv.getPaint().setFakeBoldText(true);
            tv.setTextColor(0xFFFFFFFF);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(Ui.dp(act, 14), Ui.dp(act, 6),
                    Ui.dp(act, 14), Ui.dp(act, 6));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x40000000);
            bg.setCornerRadius(Ui.dp(act, 10));
            bg.setStroke(Ui.dp(act, 1), 0x55FFFFFF);
            tv.setBackground(bg);
            tv.setClickable(true);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    undo(act);
                }
            });

            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.addRule(RelativeLayout.ALIGN_PARENT_END);
            lp.addRule(RelativeLayout.CENTER_VERTICAL);
            lp.setMargins(0, 0, Ui.dp(act, 20), 0);
            ((ViewGroup) gaming).addView(tv, lp);
            XposedHelpers.setAdditionalInstanceField(act, KEY_UNDO_BTN, tv);
            refreshUndo(act);
        } catch (Throwable t) {
            MainHook.log("添加撤销按钮失败: " + t);
        }
    }

    private static void undo(Activity act) {
        try {
            Object binding = XposedHelpers.getObjectField(act, "view");
            Object view = binding == null ? null : XposedHelpers.getObjectField(binding, "a0");
            if (view == null) return;
            Object game = XposedHelpers.callMethod(view, "getGame");
            if (game == null) return;

            Object status = XposedHelpers.callMethod(game, "k");
            if (status != null && "GameOver".equals(status.toString())) {
                Ui.toast(act, "本局已结束，无法撤销");
                return;
            }

            List<Snap> stack = stackOf(view);
            if (stack == null || stack.isEmpty()) {
                Ui.toast(act, "没有可撤销的步骤");
                refreshUndo(act);
                return;
            }
            Snap snap = stack.remove(stack.size() - 1);

            // 先打「不上传」标记再改棋盘：后续任何一步抛异常，这局也不会被当作正常成绩
            boolean first = !sessionNoUpload;
            sessionNoUpload = true;
            XposedHelpers.setAdditionalInstanceField(act, KEY_NO_UPLOAD, Boolean.TRUE);
            setPersistNoUpload(act, true);
            if (first) {
                Ui.toast(act, "已撤销，本局成绩将不上传");
            }

            XposedHelpers.callMethod(view, "n", snap.cells, snap.seed,
                    snap.seedCount, snap.score);
            Object info = XposedHelpers.getObjectField(act, "a");
            if (info != null) {
                XposedHelpers.callMethod(info, "setScore", snap.score);
            }
            // 撤回后恢复到可继续游戏的状态
            Object in = XposedHelpers.getObjectField(binding, "U");
            Object endItem = in == null ? null : XposedHelpers.getObjectField(in, "Q");
            if (endItem != null) XposedHelpers.callMethod(endItem, "setEnabled", Boolean.TRUE);
            refreshUndo(act);
        } catch (Throwable t) {
            MainHook.log("撤销失败: " + t);
            Ui.toast(act, "撤销失败");
        }
    }

    private static void clearUndo(Activity act) {
        try {
            Object binding = XposedHelpers.getObjectField(act, "view");
            Object view = binding == null ? null : XposedHelpers.getObjectField(binding, "a0");
            if (view != null) {
                XposedHelpers.removeAdditionalInstanceField(view, KEY_UNDO_STACK);
            }
            XposedHelpers.removeAdditionalInstanceField(act, KEY_NO_UPLOAD);
            refreshUndo(act);
        } catch (Throwable ignored) {
        }
    }

    private static void refreshUndo(Activity act) {
        try {
            Object btn = getAdd(act, KEY_UNDO_BTN);
            if (!(btn instanceof TextView)) return;
            Object binding = XposedHelpers.getObjectField(act, "view");
            Object view = binding == null ? null : XposedHelpers.getObjectField(binding, "a0");
            List<Snap> stack = view == null ? null : stackOf(view);
            boolean has = stack != null && !stack.isEmpty();
            ((TextView) btn).setAlpha(has ? 1f : 0.4f);
        } catch (Throwable ignored) {
        }
    }

    private static List<Snap> stackOf(Object view) {
        Object o = getAdd(view, KEY_UNDO_STACK);
        if (o instanceof List) {
            //noinspection unchecked
            return (List<Snap>) o;
        }
        return null;
    }

    private static Activity activityOf(View view) {
        return Ui.findActivity(view.getContext());
    }

    private static Object cellsOf(Object game) {
        try {
            return XposedHelpers.callMethod(game, "b");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Snap capture(Class<?> cellCls, Object game) {
        try {
            if (game == null) return null;
            Object cells = cellsOf(game);
            if (cells == null) return null;
            int rows = Array.getLength(cells);
            Class<?> rowCls = cells.getClass().getComponentType();
            Object clone = Array.newInstance(rowCls, rows);
            for (int r = 0; r < rows; r++) {
                Object row = Array.get(cells, r);
                if (row == null) continue;
                int cols = Array.getLength(row);
                Object newRow = Array.newInstance(row.getClass().getComponentType(), cols);
                for (int c = 0; c < cols; c++) {
                    Object cell = Array.get(row, c);
                    if (cell == null) continue;
                    Object nc = XposedHelpers.newInstance(cellCls);
                    XposedHelpers.setIntField(nc, "a",
                            (Integer) XposedHelpers.callMethod(cell, "g"));
                    XposedHelpers.setIntField(nc, "b",
                            (Integer) XposedHelpers.callMethod(cell, "e"));
                    XposedHelpers.setIntField(nc, "c",
                            (Integer) XposedHelpers.callMethod(cell, "a"));
                    Array.set(newRow, c, nc);
                }
                Array.set(clone, r, newRow);
            }

            Snap snap = new Snap();
            snap.cells = clone;
            Object scorer = XposedHelpers.callMethod(game, "h");
            snap.score = (Long) XposedHelpers.callMethod(scorer, "b");
            snap.seed = (Long) XposedHelpers.callMethod(game, "i");
            snap.seedCount = (Integer) XposedHelpers.callMethod(game, "j");
            return snap;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean sameGrid(Class<?> cellCls, Object a, Object b) {
        try {
            if (a == null || b == null) return false;
            int rows = Array.getLength(a);
            if (rows != Array.getLength(b)) return false;
            for (int r = 0; r < rows; r++) {
                Object ra = Array.get(a, r);
                Object rb = Array.get(b, r);
                if (ra == null || rb == null) return false;
                int cols = Array.getLength(ra);
                if (cols != Array.getLength(rb)) return false;
                for (int c = 0; c < cols; c++) {
                    Object ca = Array.get(ra, c);
                    Object cb = Array.get(rb, c);
                    if (ca == null || cb == null) return false;
                    if ((Integer) XposedHelpers.callMethod(ca, "g")
                            != (Integer) XposedHelpers.callMethod(cb, "g")) return false;
                    if ((Integer) XposedHelpers.callMethod(ca, "e")
                            != (Integer) XposedHelpers.callMethod(cb, "e")) return false;
                    if ((Integer) XposedHelpers.callMethod(ca, "a")
                            != (Integer) XposedHelpers.callMethod(cb, "a")) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 存档 key

    private static void installSaves(ClassLoader cl) {
        Class<?> saveCls = XposedHelpers.findClass(CLS_SAVE, cl);
        Class<?> helperCls = XposedHelpers.findClass(CLS_HELPER, cl);

        // 存档 key 只有 row：矩形棋盘会互相覆盖，改存编码 row*100+col
        XposedHelpers.findAndHookMethod(helperCls, "g", saveCls, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object save = param.args[0];
                if (save == null) return;
                int row = (Integer) XposedHelpers.callMethod(save, "getRow");
                int col = (Integer) XposedHelpers.callMethod(save, "getColumn");
                if (row >= 3 && row <= 10 && row != col) {
                    XposedHelpers.callMethod(save, "setRow", row * 100 + col);
                }
            }
        });

        // 读取后把编码行还原（d0 里也会再解码一次，双保险）
        XposedHelpers.findAndHookMethod(helperCls, "f", int.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object save = param.getResult();
                        if (save == null) return;
                        try {
                            int row = (Integer) XposedHelpers.callMethod(save, "getRow");
                            if (row >= 300) {
                                XposedHelpers.callMethod(save, "setRow", row / 100);
                                XposedHelpers.callMethod(save, "setColumn", row % 100);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    // ------------------------------------------------------------------ 弹窗

    private static void installDialogs(ClassLoader cl) {
        try {
            Class<?> listenerCls = XposedHelpers.findClass(CLS_LEVEL_DIALOG + "$a", cl);
            XposedHelpers.findAndHookConstructor(CLS_LEVEL_DIALOG, cl,
                    int.class, listenerCls, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                decorateLevelDialog(param.thisObject,
                                        (Integer) param.args[0]);
                            } catch (Throwable t) {
                                MainHook.log("难度弹窗改造失败: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("难度弹窗 Hook 失败: " + t);
        }

        try {
            Class<?> resultCls = XposedHelpers.findClass(CLS_RESULT_DIALOG, cl);
            XposedBridge.hookAllConstructors(resultCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (CTX.get() != null) return;
                    if (param.args.length < 1 || param.args[0] == null) return;
                    Object record = param.args[0];
                    try {
                        int row = (Integer) XposedHelpers.callMethod(record, "getRow");
                        int col = (Integer) XposedHelpers.callMethod(record, "getColumn");
                        if (row < 3 || col < 3) return;
                        Ctx c = new Ctx();
                        c.row = row;
                        c.col = col;
                        c.target = targetOf(Math.min(row, col));
                        c.clamp = false;
                        param.setObjectExtra("ctx", c);
                        CTX.set(c);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getObjectExtra("ctx") != null) CTX.set(null);
                }
            });
        } catch (Throwable t) {
            MainHook.log("成绩弹窗 Hook 失败: " + t);
        }

        // 用过撤销的这局：成绩弹窗不显示登录入口（l()），也不接受登录后的二次上传（k(Runnable)）
        try {
            XposedHelpers.findAndHookMethod(CLS_RESULT_BASE, cl, "l",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (noUploadNow()) param.setResult(null);
                        }
                    });
            XposedHelpers.findAndHookMethod(CLS_RESULT_BASE, cl, "k", Runnable.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (noUploadNow()) param.setResult(null);
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("成绩弹窗登录入口 Hook 失败: " + t);
        }
    }

    /** 最近一次结算/成绩弹窗回调是否发生在「本局用过撤销」的上下文里 */
    private static boolean noUploadNow() {
        Ctx c = CTX.get();
        return c != null && c.noUpload;
    }

    /** 官方 2048 设置页 → 给游戏页打标记，返回时整页重建以立即生效 */
    private static void installSetting(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_SETTING_TZFE, cl, "init",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity game = current.get();
                        if (game == null || !Ui.isAlive(game)) return;
                        if (!CLS_ACT.equals(game.getClass().getName())) return;
                        XposedHelpers.setAdditionalInstanceField(
                                game, KEY_FROM_SETTINGS, Boolean.TRUE);
                    }
                });
    }

    private static void decorateLevelDialog(Object dlg, int argLevel) {
        Object binding = XposedHelpers.getObjectField(dlg, "a");
        if (binding == null) return;
        Object rootObj = XposedHelpers.callMethod(binding, "getRoot");
        if (!(rootObj instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) rootObj;
        Activity found = Ui.findActivity(root.getContext());
        if (found == null) found = current.get();
        if (found == null || !Ui.isAlive(found)) return;
        final Activity act = found;

        // 隐藏官方 3x3/4x4/5x5 行（以及勾选图标）
        for (String f : new String[]{"Q", "R", "S", "T", "U", "V"}) {
            try {
                View v = (View) XposedHelpers.getObjectField(binding, f);
                if (v != null) v.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        }
        TextView title = null;
        try {
            title = (TextView) XposedHelpers.getObjectField(binding, "Z");
        } catch (Throwable ignored) {
        }
        if (title != null) title.setText("选择棋盘");

        int cur = currentLevel();
        if (cur <= 0) cur = argLevel;
        int[] rc = decode(cur);
        int curRow = rc[0];
        int curCol = rc[1];
        boolean custom = cur >= 300;

        int textColor = SchulteModeDialog.dialogTextColor(act);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(act, 14), Ui.dp(act, 2), Ui.dp(act, 14), Ui.dp(act, 8));

        TextView hint = new TextView(act);
        hint.setText("目标分数 = 2^(3×短边−1)");
        hint.setTextSize(11);
        hint.setTextColor(textColor);
        hint.setPadding(0, 0, 0, Ui.dp(act, 6));
        box.addView(hint);

        for (int line = 0; line < 2; line++) {
            LinearLayout rowView = new LinearLayout(act);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 4; i++) {
                final int size = 3 + line * 4 + i;
                boolean selected = !custom && cur == size;
                LinearLayout cell = new LinearLayout(act);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setBackground(cellBg(act, selected));
                cell.setClickable(true);

                TextView main = new TextView(act);
                main.setText(size + "×" + size);
                main.setTextSize(14);
                main.getPaint().setFakeBoldText(true);
                main.setTextColor(selected ? 0xFFFFFFFF : textColor);
                main.setGravity(Gravity.CENTER);
                cell.addView(main);

                TextView target = new TextView(act);
                target.setText("目标 " + targetValue(size));
                target.setTextSize(10);
                target.setTextColor(selected ? 0xFFE3EDFF : textColor);
                target.setGravity(Gravity.CENTER);
                cell.addView(target);

                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        beginLevel(dlg, size);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, Ui.dp(act, 54), 1f);
                lp.setMargins(Ui.dp(act, 3), Ui.dp(act, 3),
                        Ui.dp(act, 3), Ui.dp(act, 3));
                rowView.addView(cell, lp);
            }
            box.addView(rowView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView customCell = new TextView(act);
        String customText = custom
                ? "自定义  " + curRow + " × " + curCol + " · 目标 "
                + targetValue(Math.min(curRow, curCol))
                : "自定义  支持 (3–10) × (3–10)";
        customCell.setText(customText);
        customCell.setTextSize(13);
        customCell.setGravity(Gravity.CENTER);
        customCell.getPaint().setFakeBoldText(true);
        customCell.setTextColor(custom ? 0xFFFFFFFF : textColor);
        customCell.setBackground(cellBg(act, custom));
        customCell.setClickable(true);
        customCell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCustomDialog(act, dlg, curRow, curCol);
            }
        });
        LinearLayout.LayoutParams customLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 44));
        customLp.setMargins(Ui.dp(act, 3), Ui.dp(act, 8),
                Ui.dp(act, 3), Ui.dp(act, 2));
        box.addView(customCell, customLp);

        // 底部两个胶囊按钮
        LinearLayout footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 2));

        TextView setting = capsule(act, "2048设置");
        setting.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((Dialog) dlg).dismiss();
                openOfficialSetting(act);
            }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                0, Ui.dp(act, 38), 1f);
        lp1.setMargins(Ui.dp(act, 3), 0, Ui.dp(act, 3), 0);
        footer.addView(setting, lp1);

        TextView module = capsule(act, "模块设置");
        module.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((Dialog) dlg).dismiss();
                ModuleSettings.show(act);
            }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                0, Ui.dp(act, 38), 1f);
        lp2.setMargins(Ui.dp(act, 3), 0, Ui.dp(act, 3), 0);
        footer.addView(module, lp2);
        box.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                Ui.dp(act, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(box, Math.min(1, root.getChildCount()), boxLp);
    }

    private static void showCustomDialog(final Activity act, final Object dlg,
                                         int curRow, int curCol) {
        int textColor = SchulteModeDialog.dialogTextColor(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), Ui.dp(act, 4));

        TextView hint = new TextView(act);
        hint.setText("行、列均为 3–10，可以不同；目标分数按较短边计算");
        hint.setTextSize(12);
        hint.setTextColor(textColor);
        hint.setPadding(0, 0, 0, Ui.dp(act, 8));
        box.addView(hint);

        final EditText rowInput = numberInput(act, textColor, curRow);
        box.addView(labeledRow(act, "行数", rowInput, textColor));
        final EditText colInput = numberInput(act, textColor, curCol);
        box.addView(labeledRow(act, "列数", colInput, textColor));

        new AlertDialog.Builder(act)
                .setTitle("自定义棋盘")
                .setView(box)
                .setPositiveButton("开始", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int row = parse(rowInput, 0);
                        int col = parse(colInput, 0);
                        if (row < 3 || row > 10 || col < 3 || col > 10) {
                            Ui.toast(act, "行、列都需要在 3–10 之间");
                            return;
                        }
                        beginLevel(dlg, row == col ? row : row * 100 + col);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static TextView capsule(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(ACCENT);
        tv.getPaint().setFakeBoldText(true);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1A4C8DFF);
        bg.setCornerRadius(Ui.dp(act, 19));
        bg.setStroke(Ui.dp(act, 1), 0x554C8DFF);
        tv.setBackground(bg);
        tv.setClickable(true);
        return tv;
    }

    private static GradientDrawable cellBg(Activity act, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(act, 8));
        if (selected) {
            bg.setColor(ACCENT);
        } else {
            bg.setColor(0x0A4C8DFF);
            bg.setStroke(Ui.dp(act, 1), 0x554C8DFF);
        }
        return bg;
    }

    private static EditText numberInput(Activity act, int textColor, int value) {
        EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setTextSize(15);
        et.setTextColor(textColor);
        et.setGravity(Gravity.CENTER);
        et.setText(String.valueOf(value));
        return et;
    }

    private static View labeledRow(Activity act, String label, EditText input,
                                   int textColor) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(textColor);
        row.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(input, new LinearLayout.LayoutParams(
                Ui.dp(act, 90), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static int parse(EditText et, int fallback) {
        try {
            String s = et.getText().toString().trim();
            return s.isEmpty() ? fallback : Integer.parseInt(s);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 调用私有 k(int)：dismiss + 回调 Activity 切尺寸 */
    private static void beginLevel(Object dlg, int level) {
        try {
            Method m = XposedHelpers.findMethodExact(dlg.getClass(), "k", int.class);
            m.setAccessible(true);
            m.invoke(dlg, level);
        } catch (Throwable t) {
            MainHook.log("切换 2048 尺寸失败: " + t);
            Ui.toast(current.get(), "切换尺寸失败");
        }
    }

    private static int currentLevel() {
        try {
            Activity act = current.get();
            if (act == null) return 0;
            // 以棋盘实际行列为准：恢复存档后 Activity.j 可能还是上一次选的尺寸
            Object binding = XposedHelpers.getObjectField(act, "view");
            Object view = binding == null ? null : XposedHelpers.getObjectField(binding, "a0");
            Object game = view == null ? null : XposedHelpers.callMethod(view, "getGame");
            if (game != null) {
                int row = (Integer) XposedHelpers.callMethod(game, "g");
                int col = (Integer) XposedHelpers.callMethod(game, "c");
                if (row >= 3 && row <= 10 && col >= 3 && col <= 10) {
                    return row == col ? row : row * 100 + col;
                }
            }
            return XposedHelpers.getIntField(act, "j");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int[] decode(int level) {
        if (level >= 300) return new int[]{level / 100, level % 100};
        if (level >= 3 && level <= 10) return new int[]{level, level};
        return new int[]{4, 4};
    }

    // ------------------------------------------------------------------ 排行榜

    private static void installRank(ClassLoader cl) {
        Class<?> actCls = XposedHelpers.findClass(CLS_RANK_ACT, cl);
        Class<?> fragCls = XposedHelpers.findClass(CLS_RANK_FRAG, cl);

        // 官方按 tab 下标回填人数，超出 tab 数会 NPE
        try {
            Class<?> countCls = XposedHelpers.findClass(CLS_RANK_COUNT, cl);
            XposedBridge.hookAllMethods(countCls, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2 && param.args[0] == null) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        XposedHelpers.findAndHookMethod(actCls, "init", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    decorateRank((Activity) param.thisObject, fragCls);
                } catch (Throwable t) {
                    MainHook.log("2048 排行榜改造失败: " + t);
                }
            }
        });
    }

    private static void decorateRank(Activity act, Class<?> fragCls) {
        if (getAdd(act, KEY_RANK_CHIPS) != null) return;
        Object binding = XposedHelpers.getObjectField(act, "view");
        if (binding == null) return;
        Object helper = XposedHelpers.getObjectField(act, "a");
        if (helper == null) return;
        Object adapter = XposedHelpers.callMethod(helper, "f");
        if (adapter == null) return;
        Object pager = XposedHelpers.getObjectField(binding, "T");
        Object tab = XposedHelpers.getObjectField(binding, "S");
        if (pager == null || tab == null) return;

        Object fragsObj = XposedHelpers.getObjectField(adapter, "j");
        if (!(fragsObj instanceof List)) return;
        List<?> frags = (List<?>) fragsObj;
        if (frags.size() < 16) {
            // 官方只有 3..5，每尺寸「时间、分数」两页；追加 6..10
            for (int size = 6; size <= 10; size++) {
                for (int type : new int[]{1, 0}) {
                    Object frag = XposedHelpers.callStaticMethod(fragCls, "w",
                            type, size);
                    String title = size + "x" + size
                            + (type == 1 ? "时间" : "分数");
                    XposedHelpers.callMethod(adapter, "f", frag, title);
                }
            }
            XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
            // 官方 SlidingTabLayout 的 tab 数必须跟页数一致，否则 x(List) 会 NPE
            XposedHelpers.callMethod(tab, "setViewPager", pager);
        }

        ViewGroup appBar = null;
        ViewParent parent = ((View) tab).getParent();
        if (parent instanceof ViewGroup) appBar = (ViewGroup) parent;
        ((View) tab).setVisibility(View.GONE);
        if (appBar == null) return;

        int textColor = SchulteModeDialog.dialogTextColor(act);

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 4));

        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sizeRow = new LinearLayout(act);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);

        final RankChips chips = new RankChips();
        chips.sizes = new View[SIZES.length];
        chips.types = new View[2];
        for (int i = 0; i < SIZES.length; i++) {
            final int idx = i;
            TextView chip = rankChip(act, SIZES[i] + "×" + SIZES[i], textColor);
            chips.sizes[i] = chip;
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRankPage(act, pager, idx * 2 + chips.typeIdx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(act, 34));
            lp.setMargins(Ui.dp(act, 3), 0, Ui.dp(act, 3), 0);
            sizeRow.addView(chip, lp);
        }
        scroll.addView(sizeRow);
        bar.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout typeRow = new LinearLayout(act);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        typeRow.setPadding(0, Ui.dp(act, 6), 0, 0);
        String[] typeNames = {"时间", "分数"};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView chip = rankChip(act, typeNames[i], textColor);
            chips.types[i] = chip;
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRankPage(act, pager, chips.sizeIdx * 2 + idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, Ui.dp(act, 32), 1f);
            lp.setMargins(Ui.dp(act, 3), 0, Ui.dp(act, 3), 0);
            typeRow.addView(chip, lp);
        }
        bar.addView(typeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        appBar.addView(bar, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        int pos = 0;
        try {
            pos = (Integer) XposedHelpers.callMethod(pager, "getCurrentItem");
        } catch (Throwable ignored) {
        }
        chips.sizeIdx = Math.max(0, Math.min(SIZES.length - 1, pos / 2));
        chips.typeIdx = pos % 2;
        paintRankChips(chips, textColor);
        XposedHelpers.setAdditionalInstanceField(act, KEY_RANK_CHIPS, chips);

        try {
            Class<?> listenerCls = null;
            for (String name : new String[]{
                    "androidx.viewpager.widget.ViewPager$OnPageChangeListener",
                    "androidx.viewpager.widget.ViewPager$j"}) {
                try {
                    listenerCls = XposedHelpers.findClass(name, act.getClassLoader());
                    if (listenerCls != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (listenerCls == null) throw new ClassNotFoundException("OnPageChangeListener");
            Object listener = Proxy.newProxyInstance(listenerCls.getClassLoader(),
                    new Class<?>[]{listenerCls}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getDeclaringClass() == Object.class) {
                                if ("equals".equals(method.getName())) {
                                    return proxy == (args == null ? null : args[0]);
                                }
                                if ("hashCode".equals(method.getName())) {
                                    return System.identityHashCode(proxy);
                                }
                                return "TapsssRankChips";
                            }
                            try {
                                if ("onPageSelected".equals(method.getName())
                                        && args != null && args.length == 1
                                        && args[0] instanceof Integer) {
                                    int p = (Integer) args[0];
                                    chips.sizeIdx = Math.max(0,
                                            Math.min(SIZES.length - 1, p / 2));
                                    chips.typeIdx = p % 2;
                                    paintRankChips(chips, textColor);
                                }
                            } catch (Throwable ignored) {
                            }
                            return null;
                        }
                    });
            XposedHelpers.callMethod(pager, "addOnPageChangeListener", listener);
        } catch (Throwable t) {
            MainHook.log("排行榜页码监听失败: " + t);
        }
    }

    private static void setRankPage(Activity act, Object pager, int page) {
        try {
            XposedHelpers.callMethod(pager, "setCurrentItem", page);
        } catch (Throwable t) {
            MainHook.log("切换排行榜页失败: " + t);
        }
    }

    private static TextView rankChip(Activity act, String text, int textColor) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(act, 14), 0, Ui.dp(act, 14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(act, 17));
        tv.setBackground(bg);
        tv.setTextColor(textColor);
        tv.setTag(bg);
        return tv;
    }

    private static void paintRankChips(RankChips chips, int textColor) {
        for (int i = 0; i < chips.sizes.length; i++) {
            styleRankChip(chips.sizes[i], i == chips.sizeIdx, textColor);
        }
        for (int i = 0; i < chips.types.length; i++) {
            styleRankChip(chips.types[i], i == chips.typeIdx, textColor);
        }
    }

    private static void styleRankChip(View view, boolean selected, int textColor) {
        if (!(view instanceof TextView)) return;
        TextView tv = (TextView) view;
        Object tag = tv.getTag();
        GradientDrawable bg = tag instanceof GradientDrawable
                ? (GradientDrawable) tag : null;
        if (bg != null) {
            if (selected) {
                bg.setColor(ACCENT);
                bg.setStroke(0, 0);
            } else {
                bg.setColor(0x00000000);
                Activity act = Ui.findActivity(tv.getContext());
                bg.setStroke(act == null ? 1 : Ui.dp(act, 1), 0x334C8DFF);
            }
        }
        tv.setTextColor(selected ? 0xFFFFFFFF : textColor);
        tv.getPaint().setFakeBoldText(selected);
    }

    // ------------------------------------------------------------------ 小工具

    /** 目标分数（指数）：3×3=256 … 10×10=536870912 */
    static int targetOf(int size) {
        return 3 * size - 1;
    }

    private static long targetValue(int size) {
        return 1L << targetOf(size);
    }

    private static Object getAdd(Object host, String key) {
        try {
            return XposedHelpers.getAdditionalInstanceField(host, key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setPersistNoUpload(Activity act, boolean on) {
        try {
            act.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(SP_NO_UPLOAD, on).apply();
        } catch (Throwable ignored) {
        }
    }

    private static boolean getPersistNoUpload(Activity act) {
        try {
            return act.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .getBoolean(SP_NO_UPLOAD, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 模块设置页打开官方 2048 设置 */
    static void openOfficialSetting(Activity act) {
        boolean mark = CLS_ACT.equals(act.getClass().getName());
        if (mark) {
            XposedHelpers.setAdditionalInstanceField(act, KEY_FROM_SETTINGS, Boolean.TRUE);
        }
        try {
            Intent it = new Intent();
            it.setClassName(act, CLS_SETTING_TZFE);
            act.startActivity(it);
        } catch (Throwable t) {
            if (mark) {
                try {
                    XposedHelpers.removeAdditionalInstanceField(act, KEY_FROM_SETTINGS);
                } catch (Throwable ignored) {
                }
            }
            Ui.toast(act, "打开 2048 设置失败");
        }
    }
}
