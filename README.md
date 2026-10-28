# 扫雷联萌增强（LSPosed 模块）

针对「扫雷联萌」（`com.star.minesweeping`，官网 <http://tapsss.com/>）的 LSPosed 模块。
适配版本：**3.6.10**（3.6.x 结构一致）。

## 下载

已构建的 APK：[v1.0.0 release](https://github.com/fuoins/tapsss-lsposed/releases/latest)

## 功能

### 舒尔特方格

- **开始倒计时**：可关闭；开启时倒计时时长可调，范围 **0–60000 毫秒**（填 0 表示不等待，默认 3000ms）。
- **倒计时期间点击不开始**：倒计时进行中点击棋盘不会跳过倒计时直接开局，并弹提示。
- **阶数弹窗加入模式切换**：点游戏右上角「阶数」按钮，弹窗内可同时切换 **模式**（简单 / 简单·盲打 / 普通 / 普通·盲打 / 混合·简单 / 混合）和 **阶数**（3–10）。

### 账号与登录

- **多设备掉线自动重登（默认开启）**：当同一账号在另一台设备登录、本机被踢回登录页时，自动填入上次保存的账号密码并提交登录。
- 登录提交时会自动保存账号密码；可在模块设置中清除。

## 安装

1. 安装模块 APK；
2. 在 LSPosed 管理器中启用「扫雷联萌增强」；
3. 作用域勾选 **扫雷联萌**（`com.star.minesweeping`）；
4. 强制停止并重新打开扫雷联萌。

## 使用

进入扫雷联萌 → **设置** → 「通用」下方新增的同级 **模块设置** 区块：

- 舒尔特方格 / 账号与登录两个入口，右侧「…」打开完整菜单（含关于）；
- 舒尔特改动需重新进入游戏生效。

## 构建

本地需要 Android SDK（platform 33 + build-tools 34）与 JDK 17：

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions 会自动构建并上传 APK（见 `.github/workflows/build.yml`）。

## 实现说明（适配 3.6.10，类名/字段名为反编译后的混淆名）

| 功能 | Hook 点 |
| --- | --- |
| 设置页入口 | `SettingActivity.init()` 后，在 `common_layout`（通用）下方插入同级区块：标题「模块设置」+ 舒尔特方格 / 账号与登录 / 「…」，视图结构与 `activity_setting.xml` 里其它区块一致（`MenuTitleText` + `layout_selector_background_radius_4`） |
| 倒计时开关/时长 | `SchulteView` 构造后改写字段 `y`(启用) / `z`(毫秒) |
| 倒计时点击拦截 | `SchulteView.onTouchEvent` before：倒计时截止前吞掉 DOWN/POINTER_DOWN；同时拦 `j(null)` 兜底 |
| 阶数弹窗 | `ActionBar.c(style, iconRes, listener)` after：`ic_game_level` 按钮换成 App 原生 `SquareSizeDialog`（`k.b.z3`），标题下插入一行模式选择 |
| 快速开始入口 | 进程启动（`MinesweeperApplication.onCreate`）时把 `GameMenu("ic_setting","模块设置")` 写进快速开始列表（KV `utils.h.i`），并在「全部游戏」网格里注入同款格子；点格子可像其它游戏一样增删（`$b.p` 拦截 → 改 KV 列表 + 刷新）；点快速开始里的图标打开面板（`$a.p` 拦截 + `ActionRouter.go` 兜底） |
| 模式切换 | 改 `Intent` 的 `type`/`blind`/`level` extras 后 `recreate()`，让 ARouter 重新注入字段并重建模式控制器 `i.c.d.c.a`；只改阶数时仍调原 `W(int)`（用一次性标记绕过 `M()` 的「游戏进行中」判断） |
| 自动重登 | `LoginActivity.L()` before 保存手机号/密码；`LoginActivity.init()` after 自动填入并调用 `L()` |

配置保存在目标应用私有目录 `shared_prefs/tapsss_module.xml`（普通 `SharedPreferences`，Hook 与被 Hook 同进程）。

## 注意

- 目前只做了**舒尔特方格**一个游戏的选项，其他游戏入口后续可扩展。
- 模块基于 3.6.10 的混淆结构；若 App 大版本更新导致类名/字段变化，Hook 会静默失效（日志 TAG：`TapsssModule`），需要重新适配。
- 保存的密码以明文存放在扫雷联萌的私有 SharedPreferences 中，仅供自动重登使用；介意的话请在模块设置里清除。
- 仅供学习交流，请遵守目标软件的服务条款。
