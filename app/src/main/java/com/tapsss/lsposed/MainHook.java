package com.tapsss.lsposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。只作用于扫雷联萌（com.star.minesweeping）。
 * 三组 Hook 各自 try/catch，避免某一组因目标版本差异失效而影响其余功能。
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "TapsssModule";
    public static final String TARGET_PKG = "com.star.minesweeping";
    /** 共存版只改了包名（dex 类名不变），按前缀同样接管 */
    private static final String PKG_PREFIX = "com.star.minesweep";
    /** 与 build.gradle 的 versionName 保持一致，显示在模式弹窗里 */
    public static final String VERSION = "1.0.19";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null || !lpparam.packageName.startsWith(PKG_PREFIX)) return;

        log("load " + lpparam.packageName + " process=" + lpparam.processName);

        try {
            SettingsHooks.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("SettingsHooks 安装失败: " + t);
        }
        try {
            SchulteHooks.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("SchulteHooks 安装失败: " + t);
        }
        try {
            AdvancedPages.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("AdvancedPages 安装失败: " + t);
        }
        try {
            SchulteRestart.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("SchulteRestart 安装失败: " + t);
        }
        try {
            LoginHooks.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("LoginHooks 安装失败: " + t);
        }
    }

    public static void log(String msg) {
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }
}
