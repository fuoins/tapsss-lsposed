package com.tapsss.lsposed;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在「设置」页（SettingActivity，布局 activity_setting）的「通用」下面插入一个同级的
 * 「模块设置」区块：舒尔特方格 / 账号与登录 / 更多，样式照抄相邻区块。
 *
 * activity_setting 的区块结构：
 *   LinearLayout(app_layout / common_layout / schulte_layout ...)
 *     TextView(style=MenuTitleText) + LinearLayout(horizontal, padding 20/4/20/10)
 *        entry(weight 1, bg=layout_selector_background_radius_4) + entry + ImageView(more, weight .5)
 */
public final class SettingsHooks {

    private static final String SETTING = "com.star.minesweeping.ui.activity.setting.SettingActivity";
    private static final String HOME = "com.star.minesweeping.ui.activity.setting.SettingHomeActivity";
    private static final String APPLICATION = "com.star.minesweeping.MinesweeperApplication";
    private static final String ROUTER = "com.star.router.ActionRouter";
    private static final String GAME_MENU = "com.star.minesweeping.data.bean.item.GameMenu";
    private static final String H_UTILS = "com.star.minesweeping.utils.h";
    private static final String ROUTE = "/module/settings";
    private static final String TILE_TEXT = "模块设置";
    private static final String TAG = "tapsss_module_section";

    private SettingsHooks() {
    }

    static void install(ClassLoader cl) {
        Class<?> settingCls = XposedHelpers.findClassIfExists(SETTING, cl);
        if (settingCls != null) {
            XposedBridge.hookAllMethods(settingCls, "init", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        inject((Activity) param.thisObject);
                    } catch (Throwable t) {
                        MainHook.log("插入模块设置区块失败: " + t);
                    }
                }
            });
        } else {
            MainHook.log("未找到设置页 SettingActivity");
        }

        Class<?> homeCls = XposedHelpers.findClassIfExists(HOME, cl);
        if (homeCls == null) {
            MainHook.log("未找到 SettingHomeActivity");
            return;
        }
        XC_MethodHook tile = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    seedTile((Activity) param.thisObject);
                } catch (Throwable t) {
                    MainHook.log("注入快速开始入口失败: " + t);
                }
            }
        };
        XposedBridge.hookAllMethods(homeCls, "init", tile);
        XposedBridge.hookAllMethods(homeCls, "I", tile);
        hookGridClick(homeCls, "b", true);
        hookGridClick(homeCls, "a", false);

        // 进程启动时就写进快速开始列表，无需先进入一次设置里的快速开始页
        Class<?> appCls = XposedHelpers.findClassIfExists(APPLICATION, cl);
        if (appCls != null) {
            XposedBridge.hookAllMethods(appCls, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        seedStore((Context) param.thisObject);
                    } catch (Throwable t) {
                        MainHook.log("启动时注入快速开始入口失败: " + t);
                    }
                }
            });
        } else {
            MainHook.log("未找到 MinesweeperApplication");
        }

        // 兜底：点击模块格子时原版会走 ActionRouter.go("/module/settings")，此处直接拦下
        Class<?> routerCls = XposedHelpers.findClassIfExists(ROUTER, cl);
        if (routerCls != null) {
            try {
                XposedHelpers.findAndHookMethod(routerCls, "go", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length != 1 || !ROUTE.equals(param.args[0])) return;
                        param.setResult(null);
                        Activity act = currentActivity();
                        if (act != null) ModuleSettings.show(act);
                    }
                });
            } catch (Throwable t) {
                MainHook.log("ActionRouter.go Hook 失败: " + t);
            }
        }
    }

    /** 顶部网格项点击：b=全部游戏（可增删），a=快速开始（直接打开面板） */
    private static void hookGridClick(Class<?> homeCls, String innerName, final boolean toggle) {
        Class<?> cls = null;
        for (Class<?> c : homeCls.getDeclaredClasses()) {
            if (c.getName().endsWith("$" + innerName)) {
                cls = c;
                break;
            }
        }
        if (cls == null) {
            MainHook.log("未找到 " + HOME + "$" + innerName);
            return;
        }
        boolean hooked = false;
        // 3.6.x 的 xBaseAdapter 在不同版本里包名不同，按「(adapter, View, int)」签名动态匹配
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (!"p".equals(m.getName()) || ps.length != 3
                    || ps[1] != View.class || ps[2] != int.class) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = param.thisObject;
                        int pos = (Integer) param.args[2];
                        Object item = XposedHelpers.callMethod(adapter, "q0", pos);
                        if (!isOurTile(item)) return;
                        param.setResult(null);
                        Activity act = Ui.findActivity(((View) param.args[1]).getContext());
                        if (act == null) return;
                        if (toggle) {
                            toggleTile(act, adapter, item, pos);
                        } else {
                            ModuleSettings.show(act);
                        }
                    } catch (Throwable t) {
                        MainHook.log("模块入口点击失败: " + t);
                    }
                }
            });
            hooked = true;
        }
        if (!hooked) MainHook.log("未找到 " + cls.getName() + ".p");
    }

    /** 取当前前台 Activity（ActionRouter 回调里没有 Context，只能从 ActivityThread 拿） */
    private static Activity currentActivity() {
        try {
            Object thread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            Map<?, ?> records = (Map<?, ?>) XposedHelpers.getObjectField(thread, "mActivities");
            if (records == null) return null;
            Activity best = null;
            if (records.isEmpty()) return null;
            for (Object record : records.values()) {
                Activity a = (Activity) XposedHelpers.getObjectField(record, "activity");
                if (a == null || a.isFinishing()) continue;
                boolean paused = XposedHelpers.getBooleanField(record, "paused");
                if (best == null) {
                    best = a;
                } else if (!paused) {
                    best = a; // 优先取未暂停的
                }
            }
            return best;
        } catch (Throwable t) {
            MainHook.log("获取当前 Activity 失败: " + t);
            return null;
        }
    }

    /** 默认加进快速开始 + 在「全部游戏」网格里补一个可增删的格子 */
    private static void seedTile(Activity act) {
        if (!Prefs.moduleEnabled(act)) return;
        seedStore(act);

        ClassLoader cl = act.getClassLoader();
        Class<?> menuCls = XposedHelpers.findClassIfExists(GAME_MENU, cl);
        Class<?> hCls = XposedHelpers.findClassIfExists(H_UTILS, cl);
        if (menuCls == null || hCls == null) return;
        List list = (List) XposedHelpers.callStaticMethod(hCls, "c");
        if (list == null) return;
        boolean in = containsOurTile(list);

        Object adapter = XposedHelpers.getObjectField(act, "b");
        if (adapter == null) return;
        List data = (List) XposedHelpers.callMethod(adapter, "getData");
        if (data == null) return;
        for (Object o : data) {
            if (isOurTile(o)) {
                XposedHelpers.setBooleanField(o, "active", in);
                return;
            }
        }
        data.add(newMenu(act, menuCls, data, in));
        XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
    }

    /** 只维护 KV 里的快速开始列表（可在进程启动、页面还没创建时调用） */
    private static void seedStore(Context c) {
        if (c == null || !Prefs.moduleEnabled(c)) return;
        ClassLoader cl = c.getClassLoader();
        Class<?> menuCls = XposedHelpers.findClassIfExists(GAME_MENU, cl);
        Class<?> hCls = XposedHelpers.findClassIfExists(H_UTILS, cl);
        if (menuCls == null || hCls == null) return;

        List list = (List) XposedHelpers.callStaticMethod(hCls, "c");
        if (list == null) return; // KV 未初始化时不强写，避免把默认列表覆盖成空
        if (!containsOurTile(list) && !Prefs.tileSeeded(c)) {
            list.add(newMenu(c, menuCls, list, true));
            XposedHelpers.callStaticMethod(hCls, "i", list);
        }
        Prefs.setTileSeeded(c, true);
    }

    private static void toggleTile(Activity act, Object adapter, Object item, int pos) {
        ClassLoader cl = act.getClassLoader();
        Class<?> menuCls = XposedHelpers.findClass(GAME_MENU, cl);
        Class<?> hCls = XposedHelpers.findClass(H_UTILS, cl);
        List list = (List) XposedHelpers.callStaticMethod(hCls, "c");
        if (list == null) list = new ArrayList();
        boolean in = containsOurTile(list);
        if (in) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (isOurTile(list.get(i))) {
                    list.remove(i);
                }
            }
        } else {
            list.add(newMenu(act, menuCls, list, true));
        }
        XposedHelpers.callStaticMethod(hCls, "i", list);
        XposedHelpers.setBooleanField(item, "active", !in);
        XposedHelpers.callMethod(adapter, "notifyItemChanged", pos);
        try {
            XposedHelpers.callMethod(act, "I");
        } catch (Throwable t) {
            MainHook.log("刷新快速开始失败: " + t);
        }
    }

    private static boolean isOurTile(Object item) {
        if (item == null) return false;
        try {
            if (ROUTE.equals(XposedHelpers.callMethod(item, "getRoute"))) return true;
        } catch (Throwable ignored) {
        }
        // 万一 route 没被持久化，用格子文字兜底识别
        try {
            return TILE_TEXT.equals(XposedHelpers.callMethod(item, "getText"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsOurTile(List list) {
        for (Object o : list) {
            if (isOurTile(o)) return true;
        }
        return false;
    }

    private static Object newMenu(Context c, Class<?> menuCls, List sameList, boolean active) {
        String colorId = "";
        for (Object o : sameList) {
            try {
                Object id = XposedHelpers.callMethod(o, "getColorID");
                if (id instanceof String && ((String) id).length() > 0) {
                    colorId = (String) id;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        Object menu = XposedHelpers.newInstance(menuCls, "ic_setting", TILE_TEXT, colorId,
                Boolean.valueOf(active));
        XposedHelpers.callMethod(menu, "setRoute", ROUTE);
        return menu;
    }

    private static void inject(final Activity act) {
        if (!Prefs.moduleEnabled(act)) return;
        try {
            seedStore(act); // 兜底：即使进程启动时 KV 还没就绪，进入设置页也会补上
        } catch (Throwable t) {
            MainHook.log("设置页补种快速开始入口失败: " + t);
        }
        View common = act.findViewById(Ui.resId(act, "common_layout", "id"));
        if (common == null) return;
        final ViewGroup parent = (ViewGroup) common.getParent();
        if (parent == null || parent.findViewWithTag(TAG) != null) return;

        Resources res = act.getResources();
        int color = res.getColor(Ui.resId(act, "dark", "color"));
        int bg = Ui.resId(act, "layout_selector_background_radius_4", "drawable");

        LinearLayout section = new LinearLayout(act);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag(TAG);

        TextView title = new TextView(act);
        title.setText("模块设置");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                res.getDimensionPixelSize(Ui.resId(act, "font_medium", "dimen")));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color);
        title.setPadding(Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = Ui.dp(act, 14);
        titleLp.rightMargin = Ui.dp(act, 14);
        section.addView(title, titleLp);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(act, 20), Ui.dp(act, 4), Ui.dp(act, 20), Ui.dp(act, 10));

        LinearLayout.LayoutParams firstLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(entry(act, "ic_setting_schulte", "舒尔特方格", color, bg,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ModuleSettings.showSchulte(act);
                    }
                }), firstLp);

        LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        secondLp.leftMargin = Ui.dp(act, 14);
        row.addView(entry(act, "ic_user_profile", "账号与登录", color, bg,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ModuleSettings.showAccount(act);
                    }
                }), secondLp);

        ImageView more = new ImageView(act);
        more.setImageResource(Ui.resId(act, "ic_setting_more", "mipmap"));
        more.setColorFilter(color);
        more.setBackgroundResource(bg);
        more.setPadding(0, Ui.dp(act, 15), 0, Ui.dp(act, 15));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModuleSettings.show(act);
            }
        });
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f);
        moreLp.leftMargin = Ui.dp(act, 14);
        row.addView(more, moreLp);

        section.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 5);
        parent.addView(section, 0, lp); // 放在设置页最顶部
        MainHook.log("已插入模块设置区块");
    }

    private static View entry(Activity act, String icon, String text, int color, int bg,
                              View.OnClickListener listener) {
        Resources res = act.getResources();
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER);
        ll.setBackgroundResource(bg);
        ll.setPadding(0, Ui.dp(act, 15), 0, Ui.dp(act, 15));
        ll.setOnClickListener(listener);
        ll.setClickable(true);

        ImageView iv = new ImageView(act);
        iv.setImageResource(Ui.resId(act, icon, "mipmap"));
        iv.setColorFilter(color);
        ll.addView(iv, new LinearLayout.LayoutParams(Ui.dp(act, 16), Ui.dp(act, 16)));

        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                res.getDimensionPixelSize(Ui.resId(act, "font_normal", "dimen")));
        tv.setTextColor(color);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(act, 8);
        ll.addView(tv, tlp);
        return ll;
    }
}
