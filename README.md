# 扫雷联萌增强（LSPosed 模块）

针对「扫雷联萌」（`com.star.minesweeping`，官网 <http://tapsss.com/>）的 LSPosed 模块。
适配版本：**3.6.10**（3.6.x 结构一致）。

## 下载

已构建的 APK：[v1.0.0 release](https://github.com/fuoins/tapsss-lsposed/releases/latest)

## 功能

### 舒尔特方格

> 模块设置里列出的都是模块新增的功能；App 自带的「开始倒计时」等设置保持原样，模块不再重复提供。

- **倒计时期间点击不直接开始（可关闭）**：倒计时进行中点击棋盘不会跳过倒计时直接开局，并弹提示；关闭后恢复 App 原行为。
- **右上角阶数弹窗增强**：点游戏右上角「阶数」按钮，弹窗内可同时切换 **官方模式**（简单 / 简单·盲打 / 普通 / 普通·盲打 / 混合·简单 / 混合）、**高级模式** 和 **阶数**（3–10）；底部还有「舒尔特设置」「模块设置」两个文字入口。
- **高级模式**（成绩仅本地记录，不上传，每局结束有独立效果）：
  - **顺序模式**：1→n²，等同官方普通模式；
  - **爆手速训练**：1→x（x ≤ 阶数²，可在弹窗内设置），点错或点到 x 自动重开；
  - **倒序训练**：n²→1；
  - **归中训练**：1→n²→2→n²-1→…；
  - **max≤N 训练**：除开局反应外，相邻两个数的最大间隔 ≤ N 秒（3阶 0.1 / 4阶 0.2 / 5阶 0.25 / 6阶 0.4 / 7阶 0.75 / 8阶 1.0 / 9阶 1.75 / 10阶 3.0），超时重开；
  - **1start 训练**：仅 3–5 阶，开局点 1 的反应必须 ≤ 0.45 秒，否则重开。
  - 每个模式旁有 `i`，点击查看训练说明。
- **非新纪录不展示成绩，自动下一把（可关闭）**：高级模式每局结束若没刷新本模式/本阶数的本地纪录，不显示成绩直接开始下一局。
- **倒计时取消下限限制**：舒尔特倒计时时长可设为 0（不等待），不再受 App 自带的毫秒下限限制。
- **重开预设**：默认启用。

### 账号与登录

- **多设备掉线自动重登（默认开启）**：当同一账号在另一台设备登录、本机被踢回登录页时，自动填入上次保存的账号密码并提交登录。
- 登录提交时会自动保存账号密码；可在模块设置中清除。

## 安装

1. 安装模块 APK；
2. 在 LSPosed 管理器中启用「扫雷联萌增强」；
3. 作用域勾选 **扫雷联萌**（`com.star.minesweeping`）；
4. 强制停止并重新打开扫雷联萌。

## 使用

进入扫雷联萌 → **设置** → 最顶部的 **模块设置** 区块：

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
| 设置页入口 | `SettingActivity.init()` 后，在最顶部插入同级区块：标题「模块设置」+ 舒尔特方格 / 账号与登录 / 「…」，视图结构与 `activity_setting.xml` 里其它区块一致（`MenuTitleText` + `layout_selector_background_radius_4`） |
| 舒尔特功能页 | 设置页 `SettingSchulteStartActivity.init()` 后替换「倒计时时长」输入框的 NumberInterceptor 下限为 0（限舒尔特页生效）；功能开关/时长均跟随 App 自带设置 |
| 倒计时点击拦截 | `SchulteView.onTouchEvent` before：倒计时截止前吞掉 DOWN/POINTER_DOWN；同时拦 `j(null)` 兜底；开关来自模块设置 |
| 阶数弹窗 | `ActionBar.c(style, iconRes, listener)` after：`ic_game_level` 按钮换成 App 原生 `SquareSizeDialog`（`k.b.z3`），标题下插入官方模式 + 高级模式 + 目标 x，底部追加「舒尔特设置」「模块设置」文字按钮 |
| 高级模式 | `SchulteView.e(row,col)` before 完全接管点击（不触发官方 listener，因此不结算/不上传）；`SchulteView.j(cells=null)` after 记录开局（Ready→Gaming）并开新一局；`SchulteView.i(null)` after 清空对局；`i.c.d.c.a.d(long)/b(int)` 屏蔽官方重开预设；每局结束/失败向 `android.R.id.content` 叠加一层效果层后调 `SchulteGameActivity.V()`（用 `M()` bypass 标记绕过「游戏进行中」判断）自动下一把 |
| 快速开始入口 | 进程启动（`MinesweeperApplication.onCreate`）时把 `GameMenu("ic_setting","模块设置")` 写进快速开始列表（KV `utils.h.i`），并在「全部游戏」网格里注入同款格子；点格子可像其它游戏一样增删（`$b.p` 拦截 → 改 KV 列表 + 刷新）；点快速开始里的图标打开面板（`$a.p` 拦截 + `ActionRouter.go` 兜底） |
| 模式切换 | 改 `Intent` 的 `type`/`blind`/`level` extras 后 `recreate()`，让 ARouter 重新注入字段并重建模式控制器 `i.c.d.c.a`；只改阶数时仍调原 `W(int)`（用一次性标记绕过 `M()` 的「游戏进行中」判断） |
| 自动重登 | `LoginActivity.L()` before 保存手机号/密码；`LoginActivity.init()` after 自动填入并调用 `L()` |

配置保存在目标应用私有目录 `shared_prefs/tapsss_module.xml`（普通 `SharedPreferences`，Hook 与被 Hook 同进程）。

## 注意

- 目前只做了**舒尔特方格**一个游戏的选项，其他游戏入口后续可扩展。
- 模块基于 3.6.10 的混淆结构；若 App 大版本更新导致类名/字段变化，Hook 会静默失效（日志 TAG：`TapsssModule`），需要重新适配。
- 保存的密码以明文存放在扫雷联萌的私有 SharedPreferences 中，仅供自动重登使用；介意的话请在模块设置里清除。
- 仅供学习交流，请遵守目标软件的服务条款。
