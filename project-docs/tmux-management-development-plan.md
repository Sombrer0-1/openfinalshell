# Tmux 会话管理开发方案

## 1. 交付目标

在 OpenFinalShell 中管理远端服务器的 tmux 会话，用户可以：

1. 连接树右键服务器（连接配置）→「Tmux 会话」→ 打开 Tmux 面板标签页。该配置尚无就绪连接时自动建立 SSH 连接（走完整认证流程），已有就绪连接时复用。
2. 终端悬浮工具条点 Tmux 按钮 → 打开同一个面板，复用当前标签的连接。
3. 面板列表点「附着」→ 新开一个终端标签，直接进入对应 tmux 会话的画面，中间不经过 shell。
4. 点「新建」→ 输入名称创建会话，默认勾选「创建并进入」。
5. 点「关闭」→ 确认后 kill-session；正附着该会话的标签自动断开并显示已结束。
6. 远端未安装 tmux → 面板显示未安装提示；已安装但没有任何会话 → 显示空列表加新建入口；采集出错（权限、socket、配置损坏）→ 显示错误原因加重试。三种状态各自独立。
7. 面板检测到 `tmux show-options -gv mouse` 为 off 且存在会话时，显示提示条，提供一键开启，并可关闭不再提示。
8. 附着标签在 SSH 断线重连后自动重新附着（标签记住目标会话）。prefix+d detach 后标签显示已退出，提供「重新附着」和「关闭标签」两个动作；关闭标签不影响远端会话的存活。

## 2. 开发边界

- 不实现 `tmux -C` 控制模式。列表用 2 秒探针轮询，操作后立即补一次刷新。
- 不支持自定义 socket（`-L`/`-S`）、其他用户或 root 的 tmux server、窗口/窗格级管理。只管理当前登录用户默认 socket 上的会话。
- 连接树不在服务器节点常驻显示会话数量徽标。
- 附着标签使用自己的 SSH 连接（内部 `session:open`），与面板连接相互独立，延续「一个终端标签一条连接」的现有模型。服务器要求二次认证时，每次附着都会再走一次认证，产品文案说明这一点。
- 不通过向 shell 写入命令实现任何操作。附着走独立 exec 通道，管理命令走采集通道或 execOnce，不进入 shell 命令历史、不触发命令采集。
- RDP 类型的连接配置不显示「Tmux 会话」入口。

## 3. 架构依据

与进程管理面板（PR #4）同构，逐项复用其模式：

| 已有设施 | 本功能对应物 |
| --- | --- |
| `ProcessPanelManager`（instanceResource、五条停止路径、reattach） | `TmuxManager` |
| `ProcessPanelCollector`（常驻 sh 通道 + 哨兵帧 + 2s 轮询 + 单飞 + 失败计数） | `TmuxCollector` |
| `processPanel:data/state` 事件与 `useProcessPanelStore` | `tmux:sessions/state` 与 `useTmuxStore` |
| `openProcessPanelTab` 复用终端会话、关标签级联停止 | `openTmuxPanelTab`（含连接复用规则） |
| zod 入参校验 + `shQuote` + kill 确认弹窗 + `unsupported` 降级 | 面板操作同款处理 |
| `SshConnection.openShell`（ShellSession 包装 + shells/terms 注册） | 附着通道复用同一套包装与注册 |

管理面按 SessionId 建模。同一台服务器开多条连接时各自轮询同一份 tmux 列表，一侧 kill 后另一侧最多 2 秒同步。

**连接复用规则**（渲染层执行）：打开面板时，在 tabs 里查找 profileId 相同、`state === 'ready'`、有 sessionId 的终端标签，优先当前活动标签，其次第一个就绪的；命中则复用其连接，面板成为该终端的子面板（源终端关闭时面板级联关闭，同 processPanel/portTraffic 规则）；未命中则 `session:open` 新建连接，面板自有连接，随面板关闭而断开。

## 4. 远端脚本与协议

全部脚本遵循 ExecRunner 契约：不写提前 `exit`，自定义状态用 `(exit N)` 留在 `$?` 里；动态参数一律 `shQuote`；错误信息不丢弃。

**探测帧**（首次）：

```sh
if command -v tmux >/dev/null 2>&1; then echo yes; else echo no; fi   # no → unsupported
tmux -V
```

**列表帧**（每 2 秒）。退出码 1 不等于「没有 server」，必须按 stderr 区分：no-server 是合法空态，其余错误进 failed：

```sh
out=$(tmux list-sessions -F '#{session_id}\t#{session_windows}\t#{session_attached}\t#{session_created}\t#{session_name}' 2>&1)
rc=$?
if [ "$rc" -eq 0 ]; then
  printf '%s\n' "$out"
else
  case "$out" in
    *"no server running"*|"error connecting to "**"No such file or directory"*) : ;;
    *) echo "SECTION:ERROR"; printf '%s\n' "$out" | head -n 3 ;;
  esac
fi
tmux show-options -gv mouse 2>/dev/null
```

`SECTION:ERROR` 段出现时，采集器把面板置为 failed 并透出前三行错误；无会话与无 server 均输出空正文，面板显示空列表。字段顺序把 `session_name` 放最后：名称含空格、中文、前导 `-` 时整行仍可按前四个定长字段可靠切分；tmux 对名称内的 `\t`/`\n` 做转义，解析时校验 `session_id` 匹配 `^\$\d+$`，不匹配的行丢弃。

**新建**（execOnce）：

```sh
tmux new-session -d -P -F '#{session_id}' -s <shQuote(name)>
```

stdout 第一行即新会话 id。名称入参 zod 约束：非空、长度 ≤ 64、不含控制字符、不以 `-` 开头。`duplicate session` 错误映射为「同名会话已存在」文案，表单就地报错不关闭。

**关闭**（execOnce）：`tmux kill-session -t <id>`。

**开启鼠标**（execOnce）：`tmux set-option -g mouse on`。提示条只在有会话且值为 off 时出现。

**附着前检查**（execOnce）：`tmux has-session -t <id>`。存在竞态窗口，命中失败映射为 `err.net.sessionGone`；检查通过但 attach 通道仍异步失败时（进程启动后才消失），终端内显示 tmux 原始报错，面板列表在下一帧移除该会话。异步失败不伪装成同步成功。

**寻址规则**：所有操作目标用 `#{session_id}`（形如 `$0`），名称只作显示与列表排序（按名称稳定排序，刷新不跳行）。

## 5. 附着通道

附着通道是对 `openShell` 的复刻，仅通道来源不同：

```ts
client.exec(`tmux attach -t ${shQuote(target)}`, {
  pty: {
    term: this.profile.terminal.termType || 'xterm-256color',
    cols, rows
  }
})
```

`pty` 必须传对象。传 `true` 时 ssh2 使用默认值（TERM=vt100、80×24），tmux 会按 80×24 协商窗口并可能因 vt100 降级 terminfo。

- **包装与注册**：通道直接包进现有 `ShellSession`（构造参数同 openShell，跳过 startupCommand），注册进 `SshConnection.shells` 与 `SshConnectionManager.terms`。term:input、term:resize、term:close、流控、outputReady 门控全部复用，不新造通道类。
- **就绪握手**：渲染层拿到 `tmux:attach` 返回的 termId 后，与 term:open 流程一致调用 `term:ready`，否则 `ShellSession.outputReady` 为 false，输出一直缓冲，终端空白。
- **标签模型**：`SessionTabKind` 增加 `'tmuxPanel' | 'tmuxAttach'`（`useSessionStore.ts` 内的联合类型），tmuxAttach 标签携带 `targetId`、`profileId`、自有 `sessionId`。建连走既有 `session:open` 全流程，就绪后不开 shell，改调 `tmux:attach`。
- **断线重连**：tmuxAttach 标签凭 `targetId` 重新执行附着（先 has-session 检查），不重开 shell。
- **detach / kill**：tmux 客户端退出 → 通道关闭 → `shell-exit` → 标签进入已退出态。「重新附着」走一个专用 store 动作：清 termId、恢复 `state: 'ready'`、递增附着 epoch，让 TerminalPane 的附着 effect 重跑；不调用 session:reconnect（连接仍在时无需重连，连接已断时该动作内部先走正常重连）。
- **resize**：经现有 term:resize 管道透传到通道的 `setWindow`。

## 6. 文件级开发清单

### 6.1 主进程（新增 4，修改 4）

| 文件 | 内容 |
| --- | --- |
| `src/main/monitor/tmuxScript.ts`（新） | 探测/列表/新建/关闭/开鼠标/附着/has-session 命令构造，shQuote 与哨兵拼装，纯函数供单测 |
| `src/main/monitor/TmuxCollector.ts`（新） | 常驻 sh 通道采集器：探测 → unsupported/正常；2s 列表帧；SECTION:ERROR → failed；单飞、帧超时、失败计数（阈值 3）、通道死亡 5 秒重建与 open() 在途守卫（照抄修复后的 ProcessPanelCollector） |
| `src/main/monitor/TmuxManager.ts`（新） | instanceResource：start/stop/stopAll/reattach/newSession/killSession/setMouse/attach/hasSession；操作后触发立即刷新 |
| `src/main/ipc/tmux.ipc.ts`（新） | `tmux:start/stop/new/kill/setMouse/attach` 六个 handler，zod 入参，assertTrustedSender 同款鉴权 |
| `src/main/ssh/SshConnection.ts` | 新增 `openTmuxAttach`（exec + pty 对象 + ShellSession 包装 + shells 注册）；附着通道纳入连接关闭清理 |
| `src/main/ssh/SshConnectionManager.ts` | close 路径停 TmuxManager；reestablished 钩子 reattach；attach 通道注册进 terms |
| `src/main/index.ts` | 注册 `registerTmuxIpc`；quit 时 stopAll |
| `src/shared/ipc.ts` | 六个 invoke 通道 + `tmux:sessions/state` 事件契约；CHANNEL_PREFIXES 等四处注册表同步 |

### 6.2 共享类型与常量（修改 2）

| 文件 | 内容 |
| --- | --- |
| `src/renderer/src/stores/useSessionStore.ts` | `SessionTabKind` 增加 `'tmuxPanel' | 'tmuxAttach'`；`SessionTab` 增加 `targetId?`、`tmuxName?` 字段 |
| `src/shared/types.ts` | `TmuxSessionInfo`（id/name/windows/attached/created）、`TmuxPanelState`（collecting/running/failed/unsupported/stopped） |
| `src/shared/constants.ts` | `TMUX_POLL_INTERVAL_MS = 2000`、`TMUX_SESSION_NAME_MAX = 64`、帧字节数上限 |

### 6.3 渲染层（新增 4，修改 6）

| 文件 | 内容 |
| --- | --- |
| `src/renderer/src/features/tmux/TmuxPanelTab.tsx`（新） | 面板：状态区（连接中/未安装/空态+新建/失败+重试）、会话列表（名称、窗口数、attached 徽标悬停说明、创建时间、行内附着/关闭）、新建表单（默认勾选创建并进入，重名就地报错）、「打开终端」入口、鼠标提示条（含 Shift 拖拽/右键副作用说明，可关闭） |
| `src/renderer/src/features/tmux/TmuxPanelTab.module.css`（新） | 面板样式 |
| `src/renderer/src/stores/useTmuxStore.ts`（新） | sessions/state/error 按 SessionId 键控；start/stop/new/kill/setMouse/attach 动作；`wireTmuxEvents` |
| `src/renderer/src/features/sessions/SessionView.tsx` | tmuxPanel → TmuxPanelTab、tmuxAttach → TerminalPane（附着模式）路由 |
| `src/renderer/src/features/terminal/TerminalPane.tsx` | 附着模式：ready 后调 `tmux:attach` + `term:ready`；shellEpoch 重连分支带 targetId 重附着；已退出态渲染「重新附着/关闭标签」；悬浮工具条加 Tmux 按钮 |
| `src/renderer/src/stores/useSessionStore.ts` | `openTmuxPanelTab(profileId)`（按 §3 复用规则）、`openTmuxAttachTab(profileId, targetId, name)`（内部 session:open）；closeTab/closeOthers 对两种新 kind 的级联；duplicateTab 对 tmuxAttach 重新附着 |
| `src/renderer/src/features/connections/ConnectionTreePanel.tsx` | profile 右键菜单加「Tmux 会话」项（RDP 配置不显示） |
| `src/renderer/src/features/layout/TitleBar.tsx` | 两种新 kind 的菜单与标题规则（tmuxAttach 标题 `A · tmux a`） |
| `src/renderer/src/App.tsx` | 调用 `wireTmuxEvents()` |
| `src/renderer/src/ipc/mock.ts` | 六个通道 + 两个事件的演示实现 |

### 6.4 i18n（修改 11）

新增 `tmux` 文案块约 34 个 key（面板标题、状态、列头、新建、附着、关闭确认、鼠标提示、`err.net.sessionGone/duplicateSession` 等），10 个语言文件铺齐；非英文中文语言沿用「先机翻、`_meta.machineTranslated` 标记、后续批次校对」流程。`check:i18n` 通过为验收线。

### 6.5 测试（新增 4，修改 1）

| 文件 | 内容 |
| --- | --- |
| `test/unit/tmux.test.ts`（新） | 脚本构造（哨兵、shQuote、无提前 exit、pty 不可在此层测，校验命令串）、列表解析（多行、名称含空格/中文/前导 `-`、空正文=空列表、SECTION:ERROR → failed、非法 id 行丢弃）、new 的 id 提取、has-session 语义、collector 过期帧与失败计数 |
| `test/renderer/tmuxStore.test.ts`（新） | 事件 → store 迁移、操作后即刷、清理路径 |
| `test/renderer/sessionStore.test.ts` | 新 kind 生命周期：面板复用连接不重发 session:open、借用型面板随源终端关闭、附着标签关闭只停自有连接、kill 后附着标签状态迁移、duplicateTab 重新附着 |
| `test/component/tmuxPanel.test.tsx`（新） | 面板五态渲染、新建交互与重名报错、关闭确认（attached > 0 警告文案）、鼠标提示条显隐与关闭 |
| `test/component/terminalTmuxAttach.test.tsx`（新） | TerminalPane 附着模式：ready → tmux:attach → term:ready 调用序列、重连 → 重附着、term:exit → 已退出态两按钮、关标签不触发 kill |

## 7. 产品行为

**心智模型**：面板是管理台（看列表、附着、新建、关闭），附着标签是会话的窗口。关闭附着标签是 detach，远端会话继续；只有面板里的「关闭会话」是 kill。UI 文案严格区分「关闭会话」与「关闭标签」两个词。

**面板布局**：

```
A · Tmux                  [刷新] [新建] [打开终端]
────────────────────────────────────────────────
名称        窗口   已连接   创建时间      操作
a            2      1      2 小时前      附着  关闭
b            1      0      10 分钟前     附着  关闭
────────────────────────────────────────────────
⚠ tmux 未开启鼠标：滚轮会被当成方向键   [开启] [×]
```

attached 徽标悬停提示「已有 N 个客户端连接，附着后将共享该会话」。

**关闭会话确认**：attached > 0 时弹窗写明「该会话有 N 个客户端连接，关闭后这些连接会断开，tmux 会话将被结束」。

**附着**：新标签标题 `A · tmux a`，直接出现 tmux 画面；面板留在标签栏。二次认证服务器会再弹一次认证。断线重连自动重新附着；期间会话被外部杀掉时提示「会话已不存在」。

**状态迁移**（验收用）：

| 动作 | 面板 | 附着标签 | 远端会话 |
| --- | --- | --- | --- |
| 附着 | 保留 | 新增，直接进 tmux | 不变（attached +1） |
| 新建（仅创建 / 并进入） | 新行立即出现 / 保留 | — / 新增 | +1 |
| detach（prefix+d 或关附着标签） | 不变 | 已退出态两按钮 / 消失 | 存活 |
| 重新附着 | 不变 | 回到 tmux 画面 | 不变 |
| 关闭会话（kill） | 行移除 | 自动结束 | 结束 |
| 关闭面板（自有连接） | 消失，连接断开 | 不受影响 | 不变 |
| 关闭源终端（借用型面板） | 面板一起关 | 不受影响 | 不变 |
| SSH 断线重连 | 采集恢复 | 自动重新附着 | 不变 |

## 8. 验收

- `npm run typecheck`、`npm test`、`npm run check:i18n` 全绿，覆盖 §6.5 清单。
- 真机验收（对一台 Linux）：右键进面板 → 新建 b（创建并进入）→ 在 b 里跑 `top` → 关电脑 → 重连后面板仍列 b → 重附着看到 top 还在 → 面板关闭 b → 附着标签自动显示已结束。全程 shell 内无命令输入痕迹。
- 权限错误路径：对 socket 无权限的用户打开面板，显示错误原因与重试，不显示空列表。
- 无 tmux 的主机（Windows OpenSSH 或极简容器）面板显示未安装提示。
- `npm run package` 出包，打包后人工冒烟附着与滚动。

## 9. 工作量

| 部分 | 内容 | 人日 |
| --- | --- | --- |
| 主进程 | 四个新文件 + 三处接线（通道、生命周期、IPC 注册） | 1.0 |
| 附着通道 | openTmuxAttach（pty 对象 + ShellSession 包装注册）、term:ready 握手、重附着 epoch 机制、已退出态 | 0.75 |
| 渲染层 | 面板、两个 store 动作集与连接复用规则、标签路由与级联、TitleBar/duplicateTab 规则、工具条与树入口、mock | 1.75 |
| i18n | 34 key × 10 语言铺齐 + 机翻标记 | 0.5 |
| 测试 | 四个新测试文件 + sessionStore 扩展 | 0.6 |
| 联调验收 | 真机链路（断线重连、kill 联动、权限错误路径）+ 打包冒烟 | 0.4 |
| 合计 | | **5.0** |
