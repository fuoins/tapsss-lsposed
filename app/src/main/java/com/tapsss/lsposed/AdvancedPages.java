package com.tapsss.lsposed;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 排名页 / 本地记录页的高级模式界面。
 *
 * 排名页：把「三个杠」里的模式收纳改成顶部两排横向标签
 * （第一排官方模式，第二排高级模式）；选到高级模式时用本地记录渲染榜单（只有自己）。
 * 本地记录页：顶部加「官方记录 / 高级模式」标签，选高级模式时列表切换成本地记录。
 */
public final class AdvancedPages {

    private static final String RANK_ACT =
            "com.star.minesweeping.ui.activity.rank.RankSchulteActivity";
    private static final String LOCAL_ACT =
            "com.star.minesweeping.ui.activity.user.career.CareerSchulteLocalActivity";

    private static final String TAG_MODE_ROW = "tapsss_adv_mode_row";
    private static final String TAG_ADV_ROW = "tapsss_adv_adv_row";
    private static final String TAG_PANEL = "tapsss_adv_rank_panel";
    private static final String TAG_PANEL_HEADER = "tapsss_adv_rank_header";
    private static final String TAG_PANEL_LIST = "tapsss_adv_rank_list";
    private static final String TAG_LOCAL_TABS = "tapsss_adv_local_tabs";
    private static final String TAG_LOCAL_PANEL = "tapsss_adv_local_panel";
    private static final String TAG_LOCAL_PANEL_HEADER = "tapsss_adv_local_header";
    private static final String TAG_LOCAL_PANEL_LIST = "tapsss_adv_local_list";

    private static final String KEY_ADV_SEL = "tapsss_adv_rank_sel";
    private static final String KEY_LOCAL_SEL = "tapsss_adv_local_sel";
    private static final String KEY_LOCAL_VIEWS = "tapsss_adv_local_views";

    /** 与「三个杠」弹窗里的顺序一致 */
    private static final String[] MODE_LABELS = {
            "简单", "简单盲玩", "普通", "普通盲玩", "简单打乱", "打乱"};
    private static final int[] MODE_TYPE = {1, 1, 0, 0, 2, 3};
    private static final boolean[] MODE_BLIND = {false, true, false, true, false, false};

    /** 第一位是「不使用高级模式」（官方榜单） */
    private static final String[] ADV_LABELS = {
            "无", "爆手速", "倒序", "归中", "max≤N", "1start", "顺序区间", "倒序区间"};

    private static final String[] LOCAL_LABELS = {"官方记录", "高级模式"};

    private static volatile boolean rankHooked;
    private static volatile boolean localHooked;

    private AdvancedPages() {
    }

    static void install(ClassLoader cl) {
        if (!rankHooked) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(RANK_ACT, cl);
                if (cls != null) {
                    XposedBridge.hookAllMethods(cls, "init", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                decorateRank((Activity) param.thisObject);
                            } catch (Throwable t) {
                                MainHook.log("排名页高级模式界面失败: " + t);
                            }
                        }
                    });
                    // 原「三个杠」是模式弹窗，现在模式已移到顶部标签，拦掉旧弹窗
                    XposedBridge.hookAllMethods(cls, "y", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(null);
                                if (param.args.length > 0 && param.args[0] instanceof View) {
                                    Ui.toast(((View) param.args[0]).getContext(),
                                            "模式选择已移到顶部标签栏");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    rankHooked = true;
                }
            } catch (Throwable t) {
                MainHook.log("排名页 Hook 失败: " + t);
            }
        }

        if (!localHooked) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(LOCAL_ACT, cl);
                if (cls != null) {
                    XposedBridge.hookAllMethods(cls, "init", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                decorateLocal((Activity) param.thisObject);
                            } catch (Throwable t) {
                                MainHook.log("本地页高级模式入口失败: " + t);
                            }
                        }
                    });
                    localHooked = true;
                }
            } catch (Throwable t) {
                MainHook.log("本地页 Hook 失败: " + t);
            }
        }
    }

    // ------------------------------------------------------------------ 排名页

    private static void decorateRank(Activity act) {
        Object binding = XposedHelpers.getObjectField(act, "view");
        if (binding == null) return;
        Object tabObj = XposedHelpers.getObjectField(binding, "S");
        Object pagerObj = XposedHelpers.getObjectField(binding, "T");
        if (!(tabObj instanceof View) || !(pagerObj instanceof View)) return;
        View tab = (View) tabObj;
        View pager = (View) pagerObj;
        ViewGroup appBar = tab.getParent() instanceof ViewGroup ? (ViewGroup) tab.getParent() : null;
        if (appBar == null) return;

        if (appBar.findViewWithTag(TAG_MODE_ROW) == null) {
            int type = XposedHelpers.getIntField(act, "a");
            boolean blind = XposedHelpers.getBooleanField(act, "b");

            final Activity activity = act;
            View modeRow = chipRow(act, MODE_LABELS, modeIndex(type, blind), new Pick() {
                @Override
                public void onPick(int index) {
                    pickRankMode(activity, index);
                }
            });
            ViewGroup modeInner = (ViewGroup) ((ViewGroup) modeRow).getChildAt(0);
            modeInner.setTag(TAG_MODE_ROW);
            appBar.addView(modeRow, 0,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            Ui.dp(act, 42)));

            View advRow = chipRow(act, ADV_LABELS, 0, new Pick() {
                @Override
                public void onPick(int index) {
                    XposedHelpers.setAdditionalInstanceField(activity, KEY_ADV_SEL, index);
                    refreshRank(activity);
                }
            });
            ViewGroup advInner = (ViewGroup) ((ViewGroup) advRow).getChildAt(0);
            advInner.setTag(TAG_ADV_ROW);
            appBar.addView(advRow, appBar.getChildCount(),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            Ui.dp(act, 42)));

            listenPageChange(act, pager);
        }
        refreshRank(act);
    }

    private static void pickRankMode(Activity act, int index) {
        try {
            ClassLoader cl = act.getClassLoader();
            Class<?> popupCls = XposedHelpers.findClass(
                    "com.star.minesweeping.data.bean.PopupItem", cl);
            Class<?> hostCls = XposedHelpers.findClass("com.star.minesweeping.k.a.c", cl);
            Method m = act.getClass().getDeclaredMethod("x", hostCls, View.class,
                    popupCls, int.class);
            m.setAccessible(true);
            m.invoke(act, null, null, null, index);
        } catch (Throwable t) {
            MainHook.log("排名页切换官方模式失败，改用直连: " + t);
            // 直连兜底：改字段 → 刷新标题/网络 → 通知各阶数 fragment
            try {
                XposedHelpers.setIntField(act, "a", MODE_TYPE[index]);
                XposedHelpers.setBooleanField(act, "b", MODE_BLIND[index]);
                XposedHelpers.callMethod(act, "C");
                Object adapter = XposedHelpers.getObjectField(act, "d");
                int count = (Integer) XposedHelpers.callMethod(adapter, "g");
                for (int i = 0; i < count; i++) {
                    Object f = XposedHelpers.callMethod(adapter, "e", i);
                    if (f == null) continue;
                    String name = f.getClass().getName();
                    if (name.endsWith("$j")) {
                        XposedHelpers.callMethod(f, "w", MODE_TYPE[index], MODE_BLIND[index]);
                    } else if (name.endsWith("$i")) {
                        XposedHelpers.callMethod(f, "x", MODE_TYPE[index], MODE_BLIND[index]);
                    }
                }
            } catch (Throwable t2) {
                MainHook.log("排名页切换官方模式兜底失败: " + t2);
            }
        }
        refreshRank(act);
    }

    private static void listenPageChange(Activity act, View pager) {
        try {
            // 接口类名被混淆过，不能按名字找；从 addOnPageChangeListener 的参数类型取
            Class<?> cls = null;
            for (Method m : pager.getClass().getMethods()) {
                if ("addOnPageChangeListener".equals(m.getName())
                        && m.getParameterTypes().length == 1) {
                    cls = m.getParameterTypes()[0];
                    break;
                }
            }
            if (cls == null || !cls.isInterface()) return;
            final Activity activity = act;
            Object listener = Proxy.newProxyInstance(act.getClassLoader(),
                    new Class<?>[]{cls}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                if ("onPageSelected".equals(method.getName())) {
                                    refreshRank(activity);
                                }
                            } catch (Throwable ignored) {
                            }
                            return null;
                        }
                    });
            XposedHelpers.callMethod(pager, "addOnPageChangeListener", listener);
        } catch (Throwable t) {
            MainHook.log("排名页翻页监听失败: " + t);
        }
    }

    private static void refreshRank(Activity act) {
        try {
            Object binding = XposedHelpers.getObjectField(act, "view");
            Object tabObj = XposedHelpers.getObjectField(binding, "S");
            Object pagerObj = XposedHelpers.getObjectField(binding, "T");
            View tab = (View) tabObj;
            View pager = (View) pagerObj;
            ViewGroup appBar = (ViewGroup) tab.getParent();
            ViewGroup root = (ViewGroup) appBar.getParent();

            int type = XposedHelpers.getIntField(act, "a");
            boolean blind = XposedHelpers.getBooleanField(act, "b");
            View modeRow = appBar.findViewWithTag(TAG_MODE_ROW);
            if (modeRow instanceof ViewGroup) {
                applyChipState((ViewGroup) modeRow, modeIndex(type, blind));
            }

            Object selObj = XposedHelpers.getAdditionalInstanceField(act, KEY_ADV_SEL);
            int adv = selObj instanceof Integer ? (Integer) selObj : 0;
            View advRow = appBar.findViewWithTag(TAG_ADV_ROW);
            if (advRow instanceof ViewGroup) applyChipState((ViewGroup) advRow, adv);

            View panel = ensurePanel(act, root, pager, appBar);
            boolean local = adv > 0;
            pager.setVisibility(local ? View.GONE : View.VISIBLE);
            panel.setVisibility(local ? View.VISIBLE : View.GONE);
            if (local) {
                // 面板必须和 ViewPager 占同一块位置（AppBarLayout 下方），
                // 否则会盖住顶部两排模式标签
                ViewGroup.LayoutParams lp = panel.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    int top = Math.max(0, appBar.getHeight());
                    ((ViewGroup.MarginLayoutParams) lp).topMargin = top;
                    if (root.getHeight() > 0) {
                        lp.height = Math.max(Ui.dp(act, 80), root.getHeight() - top);
                    }
                    panel.setLayoutParams(lp);
                }
                fillRankPanel(act, panel, pager, type, blind, adv);
            }
        } catch (Throwable t) {
            MainHook.log("排名页刷新失败: " + t);
        }
    }

    private static View ensurePanel(Activity act, ViewGroup root, View pager, View appBar) {
        View old = root.findViewWithTag(TAG_PANEL);
        if (old != null) return old;

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag(TAG_PANEL);
        panel.setBackgroundColor(color(act, "background", 0xFFF7F7F7));

        TextView header = new TextView(act);
        header.setTag(TAG_PANEL_HEADER);
        header.setTextSize(14);
        header.getPaint().setFakeBoldText(true);
        header.setTextColor(color(act, "dark", 0xFF333333));
        header.setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), Ui.dp(act, 8));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag(TAG_PANEL_LIST);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 直接复用 ViewPager 的 LayoutParams 类型（构造函数不接受类名字符串：
        // APK 里 androidx 的类名被 R8 混淆过，按名字找不到 CoordinatorLayout$LayoutParams）
        int top = appBar.getHeight() > 0 ? appBar.getHeight() : Ui.dp(act, 84);
        int height;
        if (root.getHeight() > 0) {
            height = Math.max(Ui.dp(act, 80), root.getHeight() - top);
        } else {
            height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        ViewGroup.LayoutParams lp = null;
        try {
            Class<?> lpCls = pager.getLayoutParams().getClass();
            lp = (ViewGroup.LayoutParams) lpCls.getConstructor(int.class, int.class)
                    .newInstance(ViewGroup.LayoutParams.MATCH_PARENT, height);
        } catch (Throwable t) {
            MainHook.log("排名页本地榜单布局失败: " + t);
        }
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).topMargin = top;
        }
        if (lp != null) {
            root.addView(panel, root.indexOfChild(pager) + 1, lp);
        } else {
            root.addView(panel);
        }
        panel.setVisibility(View.GONE);
        return panel;
    }

    private static void fillRankPanel(Activity act, View panel, View pager,
                                      int type, boolean blind, int adv) {
        TextView header = (TextView) panel.findViewWithTag(TAG_PANEL_HEADER);
        LinearLayout list = (LinearLayout) panel.findViewWithTag(TAG_PANEL_LIST);
        if (header == null || list == null) return;
        int level = 0;
        try {
            Object cur = XposedHelpers.callMethod(pager, "getCurrentItem");
            int idx = cur instanceof Integer ? (Integer) cur : 0;
            level = idx == 0 ? 0 : idx + 2;
        } catch (Throwable ignored) {
        }

        List<AdvStore.Rec> recs = AdvStore.query(act, type, blind, adv, level, 0);
        AdvStore.sortByTime(recs);
        header.setText("本地排名 · " + advLabel(type, blind, adv) + " · "
                + (level > 0 ? level + "×" + level : "全部阶数") + "（只有你自己）");

        list.removeAllViews();
        if (recs.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("还没有这个模式的本机记录，先去玩一局吧");
            empty.setTextSize(13);
            empty.setTextColor(0xFF999999);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(act, 40), 0, 0);
            list.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        int max = Math.min(recs.size(), 200);
        for (int i = 0; i < max; i++) {
            list.addView(rankRow(act, recs.get(i), i + 1, fmt));
        }
    }

    private static View rankRow(Activity act, AdvStore.Rec rec, int rank, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 10));

        TextView rankTv = new TextView(act);
        rankTv.setText(String.valueOf(rank));
        rankTv.setTextSize(16);
        rankTv.setGravity(Gravity.CENTER);
        rankTv.getPaint().setFakeBoldText(rank == 1);
        rankTv.setTextColor(rank == 1 ? 0xFFFF7A45 : 0xFF999999);
        row.addView(rankTv, new LinearLayout.LayoutParams(Ui.dp(act, 36),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(Ui.dp(act, 8), 0, Ui.dp(act, 8), 0);

        TextView title = new TextView(act);
        title.setText(rec.levelText() + " · " + rec.label() + rec.rangeText()
                + (rec.mode == AdvancedSchulte.SPEED ? " · x=" + rec.x : ""));
        title.setTextSize(14);
        title.setTextColor(color(act, "normal", 0xFF333333));
        mid.addView(title);

        TextView sub = new TextView(act);
        sub.setText("错误 " + rec.errors + " · " + fmt.format(new java.util.Date(rec.ts)));
        sub.setTextSize(11);
        sub.setTextColor(0xFF999999);
        mid.addView(sub);
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(act);
        time.setText(AdvancedSchulte.fmt(rec.time));
        time.setTextSize(16);
        time.getPaint().setFakeBoldText(true);
        time.setTextColor(color(act, "normal", 0xFF333333));
        row.addView(time);

        View line = new View(act);
        line.setBackgroundColor(0x14000000);
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(act, 0.5f))));
        return wrap;
    }

    private static int modeIndex(int type, boolean blind) {
        for (int i = 0; i < MODE_TYPE.length; i++) {
            if (MODE_TYPE[i] == type && MODE_BLIND[i] == blind) return i;
        }
        return 2;
    }

    private static String advLabel(int type, boolean blind, int mode) {
        String base;
        if (type == 2) base = "简单打乱";
        else if (type == 3) base = "打乱";
        else if (blind) base = (type == 1 ? "简单盲玩" : "盲玩");
        else base = (type == 1 ? "简单" : "普通");
        return mode > 0 && mode < AdvancedSchulte.SHORT.length
                ? base + "-" + AdvancedSchulte.SHORT[mode] : base;
    }

    // ------------------------------------------------------------------ 标签行

    interface Pick {
        void onPick(int index);
    }

    private static final class Chip {
        TextView text;
        View line;
        int accent;
    }

    static View chipRow(Context c, String[] labels, int selected, Pick pick) {
        // 与总成绩那排 SlidingTabLayout 一致：选中 @color/dark 加粗 + 15dp 下划线，
        // 未选中 @color/normal（之前用 theme 在部分主题下是白色，看着像没选中）
        final int accent = color(c, "dark", 0xFF333333);
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(color(c, "foreground", 0xFFFFFFFF));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
        scroll.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout chip = new LinearLayout(c);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);

            TextView tv = new TextView(c);
            tv.setText(labels[i]);
            tv.setTextSize(14);
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER);
            chip.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f));

            View line = new View(c);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    Ui.dp(c, 15), Ui.dp(c, 2.5f));
            llp.setMargins(Ui.dp(c, 8), 0, Ui.dp(c, 8), Ui.dp(c, 4));
            chip.addView(line, llp);

            Chip holder = new Chip();
            holder.text = tv;
            holder.line = line;
            holder.accent = accent;
            chip.setTag(holder);

            chip.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick.onPick(index);
                }
            });
            row.addView(chip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        applyChipState(row, selected);
        return scroll;
    }

    static void applyChipState(ViewGroup row, int selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            Object tag = row.getChildAt(i).getTag();
            if (!(tag instanceof Chip)) continue;
            Chip chip = (Chip) tag;
            boolean on = i == selected;
            chip.text.setTextColor(on ? chip.accent : color(row.getContext(), "normal", 0xFF888888));
            chip.text.getPaint().setFakeBoldText(on);
            chip.line.setBackgroundColor(on ? chip.accent : 0x00000000);
        }
    }

    // ------------------------------------------------------------------ 本地记录页

    private static void decorateLocal(Activity act) {
        try {
            Object binding = XposedHelpers.getObjectField(act, "view");
            if (binding == null) return;
            Object recyclerObj = XposedHelpers.getObjectField(binding, "b0");
            Object headerObj = XposedHelpers.getObjectField(binding, "U");
            if (!(recyclerObj instanceof View) || !(headerObj instanceof View)) return;
            View recycler = (View) recyclerObj;
            View header = (View) headerObj;
            ViewGroup content = recycler.getParent() instanceof ViewGroup
                    ? (ViewGroup) recycler.getParent() : null;
            if (content == null) return;

            if (content.findViewWithTag(TAG_LOCAL_TABS) == null) {
                Object editObj = XposedHelpers.getObjectField(binding, "W");
                View edit = editObj instanceof View ? (View) editObj : null;
                View divider = content.getChildAt(content.indexOfChild(header) + 1);

                final Activity activity = act;
                View tabs = chipRow(act, LOCAL_LABELS, 0, new Pick() {
                    @Override
                    public void onPick(int index) {
                        selectLocal(activity, index);
                    }
                });
                ((ViewGroup) tabs).getChildAt(0).setTag(TAG_LOCAL_TABS);
                content.addView(tabs, 0, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 42)));

                LinearLayout panel = buildLocalPanel(act);
                panel.setVisibility(View.GONE);
                content.addView(panel, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

                XposedHelpers.setAdditionalInstanceField(act, KEY_LOCAL_VIEWS,
                        new Object[]{header, divider, recycler, edit, panel});
                MainHook.log("本地记录页已加高级模式标签");
            }
            selectLocal(act, localSel(act));
        } catch (Throwable t) {
            MainHook.log("本地记录页高级模式标签失败: " + t);
        }
    }

    private static int localSel(Activity act) {
        Object sel = XposedHelpers.getAdditionalInstanceField(act, KEY_LOCAL_SEL);
        return sel instanceof Integer ? (Integer) sel : 0;
    }

    private static void selectLocal(Activity act, int index) {
        try {
            Object binding = XposedHelpers.getObjectField(act, "view");
            Object recyclerObj = XposedHelpers.getObjectField(binding, "b0");
            View recycler = recyclerObj instanceof View ? (View) recyclerObj : null;
            Object[] views = (Object[]) XposedHelpers.getAdditionalInstanceField(
                    act, KEY_LOCAL_VIEWS);
            if (recycler == null || views == null) return;
            ViewGroup content = recycler.getParent() instanceof ViewGroup
                    ? (ViewGroup) recycler.getParent() : null;
            if (content == null) return;

            XposedHelpers.setAdditionalInstanceField(act, KEY_LOCAL_SEL, index);
            View tabRow = content.findViewWithTag(TAG_LOCAL_TABS);
            if (tabRow instanceof ViewGroup) applyChipState((ViewGroup) tabRow, index);

            boolean adv = index > 0;
            setVisible(views[0], !adv);       // 表头
            setVisible(views[1], !adv);       // 分隔线
            setVisible(views[2], !adv);       // 官方记录列表
            if (views[3] != null) setVisible(views[3], false);
            View panel = (View) views[4];
            setVisible(panel, adv);
            if (adv) fillLocalPanel(act, panel);
        } catch (Throwable t) {
            MainHook.log("本地记录页切换失败: " + t);
        }
    }

    private static void setVisible(Object v, boolean visible) {
        if (v instanceof View) ((View) v).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static LinearLayout buildLocalPanel(Activity act) {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag(TAG_LOCAL_PANEL);

        TextView header = new TextView(act);
        header.setTag(TAG_LOCAL_PANEL_HEADER);
        header.setTextSize(13);
        header.setTextColor(color(act, "normal", 0xFF888888));
        header.setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 10));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag(TAG_LOCAL_PANEL_LIST);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    private static void fillLocalPanel(Activity act, View panel) {
        TextView header = (TextView) panel.findViewWithTag(TAG_LOCAL_PANEL_HEADER);
        LinearLayout list = (LinearLayout) panel.findViewWithTag(TAG_LOCAL_PANEL_LIST);
        if (header == null || list == null) return;
        List<AdvStore.Rec> recs = AdvStore.all(act);
        AdvStore.sortByNewest(recs);
        header.setText("高级模式记录（仅本机） · 共 " + recs.size() + " 条 · 不会上传服务器");

        list.removeAllViews();
        if (recs.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("还没有高级模式记录，先去玩一局吧");
            empty.setTextSize(13);
            empty.setTextColor(0xFF999999);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(act, 40), 0, 0);
            list.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        int max = Math.min(recs.size(), 300);
        for (int i = 0; i < max; i++) list.addView(recordRow(act, recs.get(i), fmt));
    }

    private static View recordRow(Activity act, AdvStore.Rec rec, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView left = new TextView(act);
        left.setText(rec.levelText() + " · " + rec.label() + rec.rangeText()
                + (rec.mode == AdvancedSchulte.SPEED ? " · x=" + rec.x : ""));
        left.setTextSize(14);
        left.setTextColor(color(act, "normal", 0xFF333333));
        top.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(act);
        time.setText(AdvancedSchulte.fmt(rec.time));
        time.setTextSize(14);
        time.getPaint().setFakeBoldText(true);
        time.setTextColor(color(act, "normal", 0xFF333333));
        top.addView(time);
        row.addView(top);

        TextView sub = new TextView(act);
        sub.setText(fmt.format(new java.util.Date(rec.ts)) + " · 错误 " + rec.errors
                + " · 反应 " + AdvancedSchulte.fmt(rec.reaction));
        sub.setTextSize(11);
        sub.setTextColor(0xFF999999);
        row.addView(sub);

        View line = new View(act);
        line.setBackgroundColor(0x14000000);
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(act, 0.5f))));
        return wrap;
    }

    private static int color(Context c, String name, int fallback) {
        try {
            int id = Ui.resId(c, name, "color");
            if (id != 0) return c.getResources().getColor(id);
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
