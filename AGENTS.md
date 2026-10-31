# AGENTS.md

本文件供在此仓库工作的 AI 编码助手（Trae / Cursor / Copilot 等）阅读。

## 提交前必做：设置正确的 Git 身份

云端容器和新会话里的 `git config` 通常是助手自己的默认值。直接提交会把作者写成
`traeagent`、`tapsss` 之类的账号，GitHub 会把这些身份计入贡献者统计。

每次 `git commit` 前先执行：

```bash
git config user.name  "fuoins"
git config user.email "127714490+fuoins@users.noreply.github.com"
```

提交后用 `git log -1 --format='%an <%ae>'` 确认一遍，输出必须是上面这个身份。

## 提交信息

格式：`<type>: <中文描述>`

`type` 取值：`feat` / `fix` / `docs` / `refactor` / `perf` / `style` / `chore`

- 一句话说清改了什么，控制在 50 字以内
- 一个功能一个 commit，不要混入无关改动
- 避免「update」「修改」这类没有信息量的描述

示例：

```text
fix: 2048 撤销标记按尺寸分档，切走再切回仍不上传
docs: 重构 README 版式与信息结构，新增 SVG banner
```

## 不要做的事

- 不要修改 `versionCode` / `versionName`，发版由维护者手动处理
- 不要提交 `local.properties`、签名文件、`.idea/`
- 不要改写或强推已发布的 tag
