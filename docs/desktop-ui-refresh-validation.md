# 桌面 UI 改造实施与验收记录

日期：2026-09-20。基线：`fd3dc48`（0.30.30）。参考：用户提供的《WebUI-前端技术栈自述》。

## 实施结果

按“主题基础 → 桌面外壳 → 公共控件 → 回归与文档”的顺序实施，保留紧凑运维工作台布局。沿用 Electron、React、TypeScript、Zustand 和 IPC，采用参考文档中的设计 token、分层 CSS、轻量原生控件及渐进迁移方式。

| 阶段 | 完成内容 | 提交 |
| --- | --- | --- |
| 主题基础 | 五层表面、三级边界、文字/语义色、间距/字阶/动效 token；CSS 与 Ant Design 共用主题输出；八个既有强调色种子的可读色派生 | `c12d42a` |
| 桌面外壳 | 标题栏、活动栏、侧栏、状态栏、欢迎卡片、设置及编辑器表面统一；收敛玻璃效果；静态背景 | `87c4715` |
| 公共控件 | 原生按钮、图标按钮、面板标题、卡片、空/错误/加载状态；迁移终端、SFTP、传输、监控和编辑器的简单控件；修复高缩放下弹窗和抽屉的可用空间 | `19ff6f5` |
| 回归与文档 | 生命周期回归、真实 Electron 截图与交互脚本、打包冒烟适配、设计规范及本记录 | 随本文提交 |

Ant Design 继续管理复杂表单、树、虚拟表格、菜单、Tooltip、Modal/Drawer 的交互。xterm、CodeMirror、RDP 渲染及主进程协议保持原有体系。主窗口和独立编辑器通过同一套窗口主题逻辑同步。无新增运行时依赖、网络字体或设置存储方案。

设计与后续开发约束见 [desktop-ui-design.md](desktop-ui-design.md)。

## 自动检查

| 检查 | 结果 |
| --- | --- |
| `npm run typecheck` | 通过，覆盖主进程与渲染进程 |
| `npm run check:i18n` | 通过，10 种语言、1023 个键 |
| 全量 Vitest | 127 个文件通过、5 个跳过；1734 项通过、37 项按原配置跳过 |
| `npm run build` | 通过 |
| `npm run check:bundle` | 原预算内通过，未提高阈值 |

基线测试为 1727 项通过，本轮新增 7 项检查。新增覆盖包括：深浅主题与八个强调色在五层表面的对比度、原生控件的 ref/事件透传和加载防重复操作、20 次主题/标签/SFTP 切换后终端实例与订阅数量稳定、CodeMirror 草稿和撤销历史保留。

终端生命周期测试使用真实 xterm 解析器及 SessionViewHost。曾临时引入随主题/SFTP 状态变化的组件 key，确认测试失败后恢复源码，证明回归能检出重挂载。日志保存在本地 `.local/ui-refresh/lifecycle-negative-control.log`。

复现命令：

```powershell
npm run typecheck
npm run check:i18n
npm test -- --no-file-parallelism --maxWorkers=1 --testTimeout=120000 --reporter=dot
npm run build
npm run check:bundle
```

## 构建体积

在同一依赖目录、同一构建环境中对基线与当前源码构建，按原预算脚本口径统计，单位为字节。

| 产物 | 基线 | 改造后 | 增量 | 预算上限 |
| --- | ---: | ---: | ---: | ---: |
| 渲染 JS | 2,677,869 | 2,683,053 | 5,184 | 3,300,000 |
| 渲染 JS gzip | 841,269 | 843,379 | 2,110 | 950,000 |
| 渲染 CSS | 52,959 | 59,833 | 6,874 | 80,000 |

## 真实应用验证

验证使用 Windows 10 x64、Electron 43.2.0 的本地目录包，独立临时用户数据目录及仅监听本机回环地址的 SSH/SFTP 测试服务。业务请求均针对测试服务。

`scripts/uiSmoke.mjs` 使用真实 preload 与生产 CSP，记录深浅主题、八个强调色、940×600 / 1440×900 / 1920×1080 视口以及 90% / 100% / 125% / 150% UI 缩放；覆盖十种语言的设置和连接编辑界面、AI 配置入口、减少动效/透明度、高对比度/强制颜色、SSH/SFTP/监控与独立编辑器。视口由 CDP 模拟，不能替代各操作系统的原生 DPI 验收。

打包冒烟保留原有业务断言，适配新控件的语义选择器，并在重新加载窗口后关闭首次启动引导，避免引导遮罩拦截 Tooltip 的真实鼠标测量。Windows 编辑器检查切换到独立窗口的 CDP target，保留输入法、坐标、保存与撤销的完整检查。

打包业务冒烟最终结果：`ALL PASS`。实际覆盖 SSH 中文/emoji 回显、SFTP 与监控、6 个终端工具栏及 4 个查找条 Tooltip 的原生窗口按钮避让、命令历史、拖拽上传、80 文件批量上传/跳过/取消、虚拟列表在 150% 缩放下的对齐、独立编辑器着色/搜索/中文输入法/保存/撤销、远端冲突与非原子替换分别确认、导入及代理/私钥引用保护。日志中的预期拒绝由反向用例触发。

UI 专项验证通过：64 张截图、89 项断言，未记录到渲染异常或 Chromium 错误日志。设置关闭后焦点回到入口按钮，终端工具栏接受键盘 Enter；连续 20 次主题/SFTP 切换后保留同一 xterm DOM 且没有收到 `term:exit`；独立编辑器响应主窗口主题更改并保留文件内容。

本地证据入口：

- [改造前后截图对比页](../.local/ui-refresh/index.html)
- [UI 专项报告](../.local/ui-refresh/after/report.json)
- [打包业务冒烟日志](../.local/ui-refresh/packaged-smoke.log)
- [深色工作区](../.local/ui-refresh/after/ssh-sftp-monitor-dark.png) / [浅色工作区](../.local/ui-refresh/after/ssh-sftp-monitor-light.png)
- [独立编辑器](../.local/ui-refresh/after/editor-light.png)
- [最小窗口 150% 设置](../.local/ui-refresh/after/settings-minimum-150.png) / [连接抽屉](../.local/ui-refresh/after/connection-minimum-150.png)

已人工核对工作区、编辑器、长文本语言、AI 面板及强制颜色截图。截图矩阵是代表性组合，并非所有页面、语言、主题、视口和缩放的完整笛卡尔积；键对齐检查也不能替代翻译的母语校对。

最终运行在欢迎页记录了 3 个各 1 秒的静置样本：渲染线程任务耗时分别为 0.249 / 0.266 / 0.094 ms，布局耗时均为 0，JS 堆约 13.46 MB。基线样本保存在 `.local/ui-refresh/baseline-perf/report.json`。这些是启动后的短时观测，且基线使用源码启动、当前使用目录包；不能据此推断整机 CPU/GPU 占用、长期内存稳定性或性能提升。

复现命令（先构建，两个终端分别运行）：

```powershell
# 终端 A：打包业务冒烟使用的回环服务
node test/fixtures/testSshServer.mjs 22677

# 终端 B：可替换成正式目录包的路径
node test/fixtures/packagedSmoke.mjs .local/ui-refresh/package/win-unpacked/OpenFinalShell.exe 22677 19271
npm run smoke:ui -- after .local/ui-refresh/package/win-unpacked/OpenFinalShell.exe
```

UI 专项脚本自行创建并停止另一套临时 SSH/SFTP 服务，无需复用终端 A。未提供可执行文件参数时，使用本机 Electron 启动当前项目构建。截图、报告和日志位于被忽略的 `.local/ui-refresh/`，需在本机查看或重新生成。

## 本地目录包与验收边界

本机代理 `127.0.0.1:7897` 不可用，常规打包所需下载未能完成。使用已安装 Electron 与现有依赖生成未签名的本地评审目录包，命令如下；项目的正式发布配置没有变更。

```powershell
npx electron-builder --win --x64 --dir --publish never `
  --config.directories.output=.local/ui-refresh/package `
  --config.npmRebuild=false `
  --config.electronDist=node_modules/electron/dist `
  --config.win.signExecutable=false `
  --config.win.signAndEditExecutable=false
```

目录包位于 `.local/ui-refresh/package/win-unpacked/OpenFinalShell.exe`。此次本地打包跳过依赖重建、可执行文件图标/元数据资源编辑和签名，不是发布安装器。自动更新在该目录包中缺少 `app-update.yml`，对应日志不构成更新链路验收。

尚需外部环境验收：真实 RDP 主机互操作、macOS/Linux 原生窗口与缩放、正式签名/安装/更新流程。现有协议与平台单测通过不能替代这些实机验证。
