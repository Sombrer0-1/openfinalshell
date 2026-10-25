# AI 功能完整移除开发方案

## 1. 交付目标与开发边界

将 OpenFinalShell 作为首次上线的远程运维工作台交付，功能范围为连接管理、SSH 终端、SFTP 文件管理、嵌入式 RDP、远程编辑、服务器监控、端口转发、快捷命令、传输队列、多实例窗口和配置管理。

完整删除 AI 助手及相关实现：文字问答、图片提问、流式和非流式回答、服务配置、API Token 配置、模型发现、连接测试、图片能力测试、回答解析、命令复制和填入 SSH、请求取消及实例间配置刷新。

本次开发遵循以下边界：

- 删除功能代码、接口、类型、配置、存储定义、模拟实现、测试及产品内容，完成全部调用方清理。
- 不保留识别、读取、删除或迁移旧 AI 数据的代码，不新增废弃字段名单、兼容别名、迁移标记、空实现或返回“不支持”的处理器。
- 以新建用户数据目录和新建数据库作为首发验收环境。旧版数据库、旧 AI 配置及旧版安装升级不属于交付契约；程序不为此增加自动重置或清库逻辑。
- 产品界面和介绍直接描述现有运维能力，不出现“AI 已移除”“暂不支持 AI”“未来接入 AI”等残留说明。
- 不增加插件框架、新依赖或新的功能开关，不顺带重构 SSH、SFTP、RDP、配置存储及更新系统。

本文用于开发执行，保留待删除符号和文件名作为定位依据。产品文档及最终应用不保留这些功能内容。

## 2. 架构依据与预计工作量

项目采用 Electron、React、TypeScript、Zustand 和 SQLite。`src/shared/ipc.ts` 定义主进程、preload 和渲染层共用的调用及事件契约；preload 使用精确白名单开放接口。主进程负责连接、文件、监控、转发和存储，原生 RDP Worker 独立运行。

AI 的专用实现集中在 10 个文件，约 1,100 行（含 CSS）。网络请求由主进程通过 Electron `net.fetch` 发出，没有专用 AI SDK。SSH/SFTP/RDP 核心不依赖 AI 服务；终端中的连接点是入口按钮、选中文字和回答填入。

主要工作由整块删除和共享文件局部修改组成，难度中低。预计 2–3 人日完成实现、文案和测试调整及 Windows 主要回归；其他平台的发布构建按既有流水线验证。工作量主要来自共享接入点、多语言和混合冒烟脚本，无需数据库迁移开发。

### 2.1 必须保持的功能

| 功能 | 保持的行为 |
| --- | --- |
| 连接与凭据 | SSH/RDP 配置保存、密码和私钥认证、SSH Agent、主机指纹确认、代理与私钥引用 |
| 终端 | 输入输出、尺寸协商、搜索、复制粘贴、多行确认、重连、命令历史、快捷命令和命令编辑器 |
| 文件与编辑 | SFTP 列表、传输队列、冲突处理、远程编辑器、保存与重读、目录跟随 |
| 监控与转发 | 采集、图表、端口流量、转发启动与停止、断线后的恢复 |
| RDP | Worker 通信、画面、输入、剪贴板、音频及系统 RDP 入口 |
| 应用壳 | 主题、语言、缩放、设置、首次启动引导、独立编辑器窗口 |
| 多实例与更新 | 共享运维配置、凭据持久化、实例内会话隔离、关闭清理及更新协调 |
| 数据交换 | 当前提供的加密导出、导入及 LAN Sync 功能 |

SSH Agent 是认证机制，`PromptHost` / `PromptBroker` 是认证与主机信任交互，均属于保留范围。导出格式中的 v1/v2 分别承载明文数据加密码块、整文件加密数据，不因移除 AI 而改写。

## 3. 文件级开发清单

### 3.1 删除专用业务文件

| 文件或目录 | 内容 |
| --- | --- |
| `src/main/ipc/ai.ipc.ts` | AI IPC 注册和参数 schema |
| `src/main/services/aiService.ts` | 问答、模型发现、连接测试、图片测试、请求状态和取消 |
| `src/main/services/aiProfiles.ts` | 服务配置、默认服务、URL 处理和 Token 访问 |
| `src/main/services/aiModels.ts` | 模型列表及输入能力解析 |
| `src/renderer/src/features/ai/` | 删除整个目录：`AiAssistantModal.tsx`、`AiSettingsPanel.tsx`、`AiResponse.tsx`、`aiAnswer.ts`、`AiSettingsPanel.module.css`、`AiResponse.module.css` |
| `src/renderer/src/features/terminal/terminalPaste.ts` | 仅供 AI 回答填入使用的文本准备函数 |

删除后通过引用检索和类型检查清理导入，不把专用代码迁往公共目录。

### 3.2 清理主进程接入和存储定义

| 文件 | 修改内容 |
| --- | --- |
| `src/main/index.ts` | 删除 `registerAiIpc` 的导入和启动调用；删除 `cancelAllAi` 的导入和退出调用 |
| `src/main/services/updater.ts` | 删除 `cancelAllAi` 的导入及安装更新清理调用；保留其余资源释放顺序 |
| `src/main/store/Database.ts` | 删除 `ai_profiles` 建表语句及注释；从配置 revision 的表映射中删除 `ai_profiles: 'ai'` |
| `src/main/instances/InstanceCoordinator.ts` | 从 `configEntity` 枚举中删除 `'ai'` |

新数据库的 schema 不包含 `ai_profiles`，不生成对应 revision 行或触发器，不生成默认服务记录及 `ai_defaults_v1` 标记。

`secrets` 表、Vault、系统安全存储和配置加密继续服务 SSH/RDP 等现有功能。数据库初始化直接使用剩余 schema；不添加 `DROP TABLE ai_profiles`、旧 Token 清理 SQL、旧字段检测或任何 AI 迁移模块。

`settings.ts`、`DocStore.ts`、`ConfigStore.ts`、`exportData.ts`、`importData.ts` 和 LAN Sync 当前没有 AI 专用数据交换实现，不为旧 AI 数据增加分支。首发输入由当前界面、当前契约及当前应用导出生成。

### 3.3 清理共享类型、契约及白名单

在 `src/shared/types.ts` 删除：

- `AiProfileId`。
- `AiProviderProfile`、`AiProviderProfileDraft`。
- `AiImageCapability`、`AiConnectionTestResult`。
- `AiModelEndpointDraft`、`AiImageCapabilityTestResult`、`AiModelInfo`。
- `AiMessageContentPart`、`AiChatMessage`、`AiChatRequest`。
- `AppSettings.aiAssistantEnabled` 及其注释。

在 `src/shared/constants.ts` 删除 `DEFAULT_SETTINGS.aiAssistantEnabled`。

在 `src/shared/ipc.ts` 删除 AI 类型导入，并同步删除以下契约和白名单条目：

| 类别 | 删除项 |
| --- | --- |
| InvokeMap / INVOKE_CHANNELS | `ai:profiles:list`、`ai:profiles:save`、`ai:profiles:delete`、`ai:connectionTest`、`ai:models:discover`、`ai:model:capabilityTest`、`ai:chat`、`ai:cancel` |
| EventMap / EVENT_CHANNELS | `ai:delta`、`ai:completed`、`ai:error`、`ai:cancelled` |
| 配置变更事件 | 从 `app:configChanged.entity` 联合类型删除 `'ai'` |
| 前缀登记 | 从 `CHANNEL_PREFIXES` 删除 `'ai:'` |

preload 从共享契约读取白名单，完成上述删除后继续使用现有通用桥接实现，不新增单独的拒绝处理器。调用通道、事件和配置实体的定义必须同时收敛。

### 3.4 清理渲染层入口和状态

| 文件 | 修改内容 |
| --- | --- |
| `src/renderer/src/App.tsx` | 删除 `AiAssistantModal` 导入和挂载 |
| `src/renderer/src/features/layout/ActivityBar.tsx` | 删除 AI 按钮、`Bot` 导入、`setAiOpen` 订阅及总开关判断 |
| `src/renderer/src/features/settings/SettingsModal.tsx` | 删除 `AiSettingsPanel` 导入、`Section` 中的 `'ai'`、菜单项、标题分支和面板渲染 |
| `src/renderer/src/features/terminal/TerminalPane.tsx` | 删除 AI 工具条按钮、右键菜单项、菜单处理分支、`Bot` 导入及 AI UI 状态订阅 |
| `src/renderer/src/stores/useUiStore.ts` | 删除 AI 状态、方法和初始化值；删除仅为目标终端推导存在的 `activeSshTermId` |
| `src/renderer/src/features/terminal/termRegistry.ts` | 删除 `terminalPaste` 导入、`registeredTermPasteIssue` 和 `writeToRegisteredTerm` |
| `src/renderer/src/ipc/mock.ts` | 删除所有 AI mock handler、延迟回答事件及其文案 |

`useUiStore` 删除的状态和方法为 `aiOpen`、`aiPrefill`、`aiTargetTermId`、`setAiOpen`、`setAiTargetTermId`、`openAiWithText`。删除这些内容后，清理不再使用的 `TermId` 和 `useSessionStore` 导入。

`openSettingsSection` 当前只有 AI 助手调用，随调用方一起删除。同步删除 `settingsSection` 状态、初始化值及 `SettingsModal` 中对应的订阅和 effect；调整组件测试的初始状态。设置弹窗继续使用自己的区段状态和菜单导航。

终端右键菜单的 AI 条目目前不受总开关控制，必须显式删除。终端保留 `getTerm`、注册/注销、数据接线、流量确认、`PromptTracker` 和 `noteProgrammaticWrite`，它们仍服务终端、历史与快捷命令。

普通粘贴继续使用 `bracketedUserPaste.ts`，保留 `plainUserPaste` / `bracketedUserPaste`、剪贴板逻辑和多行确认。命令编辑器继续走现有发送流程，不用 AI 的专用填入函数替换它。

### 3.5 清理共享 schema

- 删除 `shared-schema/schemas/ai-service.schema.json`。
- 删除 `shared-schema/fixtures/ai-model-inputs.json`。
- 重写 `shared-schema/schemas/command-library.schema.json` 的 `$comment`，只说明快捷命令的导出和显式执行语义，删除 AI 排除说明。
- 更新 `shared-schema/README.md`，以当前首发契约说明 schema 的职责和生成方式，删除沿用上线前格式的兼容承诺。
- 执行 `npm run generate-schema`，重新生成 `src/shared/generated/sharedSchema.ts` 和 `protocolModels.ts`。
- 更新 `test/unit/sharedSchema.test.ts`：登记表包含 `command-library`、`export-envelope`、`lansync`、`monitor-snapshot`、`port-traffic` 五项。

生成文件由脚本产生，不单独手工维护第二份 schema 登记。

### 3.6 多语言和产品内容

对 `src/shared/locales/` 中全部 10 种语言同时执行：

1. 删除 `aiConnectionTest`、`aiCapabilities`、`aiCommands`、`aiAssistant` 四组文案，共约 67 个键/语言。
2. 重写 `welcome.subtitle`，只列 SSH、SFTP、RDP 等现存能力。
3. 重写 `onboarding.welcomeIntro`，保留运维工作台和多实例介绍。
4. 检查其他嵌在常规介绍中的 AI 文案及插值，保持各语言键集合一致。

中文推荐正文：

- `welcome.subtitle`：`远程运维工作台 · SSH · SFTP · RDP`。
- `onboarding.welcomeIntro`：`集中管理 SSH 终端、SFTP 文件、RDP 远程桌面与服务器监控。Windows 可在设置中开启多个独立窗口，共享连接配置。`

英文及其他语言保持相同含义。`locales/registry.ts` 中有关翻译来源的注释可写为“其余语言经机器翻译，待母语校对”；保留真实的 `_meta.machineTranslated` 标记。

产品和开发文档按下表调整：

| 文件 | 修改内容 |
| --- | --- |
| `package.json` | 重写 description，删除 AI 功能描述 |
| `electron-builder.yml` | 清理 synopsis 及其他安装包介绍中的 AI assistance |
| `README.md` | 更新简介、核心能力、平台表、导航、设置区段、侧栏介绍、代码结构、多实例说明和测试说明；删除 AI 功能章节、AI 边界说明、相关文档链接、导出/LAN Sync 排除项中的 AI 说明及历史能力表中的 AI 内容 |
| `docs/ai-model-capabilities.md` | 删除整个文件 |
| `docs/multi-instance.md` | 删除 AI 服务共享、Token、增量事件、AI 实例隔离及验收描述；改用 SSH/SFTP/运维凭据说明现有能力 |
| `docs/desktop-ui-design.md` | 删除针对 AI 服务的专项说明，保留现有 UI 验证边界 |
| `docs/desktop-ui-refresh-validation.md` | 删除 AI 配置入口、AI 面板及截图引用；涉及旧截图数量的统计，以重新执行后的结果更新 |
| `shared-schema/schemas/command-library.schema.json` | 删除关于 AI 不进入导出的说明 |
| `agent.md` | 翻译来源统一表述为机器翻译，保留语言键同步和母语校对要求 |

没有 AI 专用依赖需要卸载。保留现有 React、Ant Design、Zustand、Zod、Electron 和终端依赖；仅修改 description 不要求重建依赖锁文件。

包名、应用名称、仓库地址、GitHub owner 和发布连接保持原配置；`kexue-aihao` 等标识不是功能实现。不能通过字符串替换误改 URL、支付宝文案或其他包含字母 `ai` 的普通单词。

### 3.7 首发说明和上线前历史

运行时的 `src/renderer/src/features/onboarding/releaseNotes.ts` 只登记当前首发版本，使用 `package.json` 中的发布版本值。内容描述首次提供的运维能力，不记录“删除 AI”的变更，不包含上线前 AI 或 Android 功能记录。

`docs/releases/` 重建为当前版本的一份首发说明，移除原上线前版本说明；README 的历史发布表收敛为当前交付能力。文件名保持 `v<当前版本>.md`，与 `.github/workflows/build-windows.yml` 使用发布 tag 拼接说明路径的规则一致。应用中的更新说明入口可显示首发说明。版本号由现有发布配置统一维护，本次方案不指定新的版本编号。

发布流水线生成的 Release 正文同步按首发介绍整理，去掉旧 JSON 配置升级迁移的介绍，保留下载方式、卸载的数据影响、系统账户绑定的凭据存储和正常导出导入说明。仅调整正文模板，不改构建矩阵、签名、校验和、产物上传或更新 feed。

保留通用版本比较、未来更新展示、首次启动引导和自动更新逻辑。首发定位不要求删除未来正常更新能力，也不要求改动无关的连接数据处理。

更新 `test/renderer/onboarding.test.ts`：不再依赖登记表中的 `0.9.0`、`0.10.0`、`0.11.0`、`0.12.0` 等历史记录。保留版本比较、当前发布版本登记、说明项有效性、当前版本筛选及引导接线检查；筛选行为以当前登记数据验证，不为保留旧测试添加上线前记录。

## 4. 测试和冒烟脚本调整

### 4.1 删除 AI 专用测试

- `test/unit/aiProfiles.test.ts`。
- `test/unit/aiModels.test.ts`。
- `test/unit/aiChatModes.test.ts`。
- `test/unit/aiCapabilityTest.test.ts`。
- `test/renderer/aiAnswer.test.ts`，包括仅供 AI 使用的 `prepareTerminalPaste` 测试。
- `test/component/aiCapabilities.test.tsx`。

不保留调用已删除模块的空测试，不新增用于识别旧 AI 数据的用例。

### 4.2 修改混合测试

| 文件 | 修改内容 |
| --- | --- |
| `test/unit/multiInstanceStore.test.ts` | 删除 AI 配置导入及两项 AI 用例；保留文档刷新、事务回滚、连接并发、写锁和分组冲突测试 |
| `test/unit/sharedSchema.test.ts` | 更新五项 schema 预期 |
| `test/component/multiInstanceSettings.test.tsx` | 删除 `settingsSection` 初始化；继续验证通用设置区段中的多实例开关 |
| `test/renderer/bracketedUserPaste.test.ts` | 删除针对已删除 AI 填入模块的隔离断言；保留 CRLF、heredoc、末尾换行及终端调用普通粘贴函数的验证 |
| `test/renderer/onboarding.test.ts` | 调整为首发登记表及通用引导的测试 |

### 4.3 保留多实例冒烟中的有效凭据验证

`test/fixtures/multiInstanceSmoke.mjs` 同时验证 SSH/SFTP、实例协调和 AI，需要逐段清理：

1. 删除 `node:http` 的 AI 测试服务器导入、服务器实例、监听端口及关闭调用。
2. 删除 AI 配置创建、Token 字符串、总开关写入、聊天调用和回答隔离检查。
3. 从事件采集中删除 `ai:delta` 和 `ai:completed`。
4. 删除重启后的 AI 配置和问答检查。
5. 重启后使用此前已保存密码的 SSH 连接再次打开会话，验证成功连接及真实终端回显；不重新提供密码、不通过认证提示注入密码。这样继续验证共用 Vault 在重启后可用。
6. 保留 SSH 密码的日志检查和脱敏，删除 AI Token 专用检查、替换文本及报告描述。
7. 保留同时启动、配置冲突、SSH 数据隔离、SFTP 队列隔离、关闭一个实例后其他实例存活、多实例偏好重启持久化等检查。

修改 `scripts/uiSmoke.mjs`：删除开启 AI 的设置 patch、AI 按钮点击和 `ai-dark` 截图步骤；保留欢迎页、设置、连接、传输、主题、语言、缩放、终端、监控和独立编辑器检查。截图数及断言数从实际结果生成。

普通的 `test/fixtures/packagedSmoke.mjs` 和终端兼容性冒烟继续覆盖运维行为。仅处理真实受影响的选择器或断言，不因按钮删除而缩减原有业务验证。

## 5. 实施顺序

### 阶段一：确定当前开发基线

记录工作区已有改动，重点关注终端面板、IPC、浏览器 mock、终端兼容性文件及冒烟脚本。当前工作区已有未提交改动，实施基于这些内容局部编辑，不回退文件或覆盖现有修复。

使用现有项目检查获取一次开发前基线。已有失败与此次删除造成的失败分开记录；检查完成后，后续只在修改影响对应证据时重跑。

### 阶段二：删除业务实现和接入

按 3.1–3.4 清单完成删除，形成完整的代码改动。清理多余 import、状态、effect、计时器、组件和菜单分支。同步调整 mock 及直接受影响的测试，避免共享契约与调用方脱节。

此阶段的出口：主进程、preload 和渲染层能够通过类型检查，运行时没有 AI 通道、入口或服务代码；终端公共能力仍完整。

### 阶段三：清理契约资料和产品内容

删除专用 schema、fixture 和文档，生成剩余 schema 登记。同步完成 10 种语言、README、安装包元数据、多实例文档、首发说明及历史记录收敛。

此阶段的出口：产品介绍只描述当前功能，语言键集合一致，schema 及测试预期与实际文件一致。

### 阶段四：调整冒烟并完成验证

完成多实例冒烟的 SSH 凭据替代验证，移除 UI 冒烟的 AI 步骤。执行规定检查，随后使用隔离的新用户目录完成真实 Electron 验证和主要交互回归。

最终构建采用修改后的源码，不复用此前包含 AI 的 renderer/main 输出或安装包。输出目录的处理限于已确认的构建产物，不清理真实用户数据目录。

## 6. 验证方法

### 6.1 源码和内容检索

使用 `rg` 检查 `src`、`test`、`scripts`、`shared-schema`、`native` 和 `.github`。实现范围内不应出现 AI 功能符号、端点、专用测试服务或专用文件：

```powershell
rg -n 'AiProfileId|AiProviderProfile|AiChat|AiModel|AiImage|AiConnection|AiAssistant|AiSettings|AiResponse|parseAiAnswer|parseAiModels|registerAiIpc|cancelAllAi|aiAssistant|aiCapabilities|aiCommands|aiConnectionTest|aiOpen|aiPrefill|aiTargetTermId|setAiOpen|setAiTargetTermId|openAiWithText|ai_profiles|ai_defaults|ai:|ai-service|smoke-ai-token|chat/completions|api\.openai\.com|api\.deepseek\.com|registeredTermPasteIssue|writeToRegisteredTerm|prepareTerminalPaste' src test scripts shared-schema native .github

rg --files src test scripts shared-schema | rg '(^|[\\/])(ai[^\\/]*|Ai[^\\/]*|terminalPaste\.[^\\/]*)$'
```

对 README、产品文档和本地化 JSON 单独检查功能文案及死链接。本文包含删除清单，不纳入产品内容零残留检查。检索按语义确认匹配，不对 `main`、`await`、`Alipay`、仓库 owner 等进行全局替换。

检索属于此次开发验收命令，不新增永久扫描脚本、AI 禁止名单或检查框架。

### 6.2 项目自动检查

schema 生成在 3.5 阶段执行一次。最终按顺序执行：

```powershell
npm run typecheck
npm run check:i18n
npm test -- --no-file-parallelism --maxWorkers=1 --testTimeout=120000 --reporter=dot
npm run build
npm run check:bundle
```

测试命令沿用 Windows CI 的串行配置。类型检查验证删除后的契约和全部调用方；i18n 检查验证各语言键集合、引用和游离键；测试验证保留业务；构建和预算检查验证实际输出。

检查失败时定位对应改动并修复。全部通过后，不再无原因重复全量验证。

### 6.3 新数据库和真实应用验证

使用全新的临时 `--user-data-dir`，通过正常启动路径创建数据库和设置：

- SQLite schema 中没有 `ai_profiles` 及其 revision 触发器。
- `config_revisions` 中没有 `ai` 实体；meta 中没有默认 AI 服务标记。
- `settings:get` 返回的默认设置没有 `aiAssistantEnabled`。
- 首次启动引导介绍现有运维能力，重新启动后遵循正常的同版本提示策略。
- 当前应用生成的导出文件及解密后的整文件导出内容没有 AI 配置或字段。

上述检查只读取隔离测试数据库和当前应用输出；不创建旧版数据库样本，不测试或实现旧 AI 数据清理。

真实应用的验证使用现有脚本：

```powershell
npm run smoke:ui
npm run smoke:multi-instance
npm run smoke:terminal
```

以上脚本使用已构建的应用；UI、多实例和终端兼容性脚本自行启动隔离环境及本地 fixture。目录包业务冒烟使用两个终端，先启动 SSH fixture，再运行脚本：

```powershell
# 终端 A
node test/fixtures/testSshServer.mjs 2270

# 终端 B：使用本次构建生成的 Windows x64 目录包
node test/fixtures/packagedSmoke.mjs release/win-unpacked/OpenFinalShell.exe 2270 9333
```

目录包必须先完成现有 RDP Worker 构建、校验和 Electron 打包前置步骤。其他平台继续通过原有 Worker 和平台产物检查。

### 6.4 交互回归重点

| 场景 | 验证结果 |
| --- | --- |
| 欢迎页和首次引导 | 功能介绍完整，当前语言下无 AI 描述 |
| 侧栏 | 四个运维视图、设置和主题入口可用，删除按钮后布局正常 |
| 设置 | 九个现存区段可导航，窗口尺寸和缩放下操作可达 |
| 终端工具条和右键菜单 | 搜索、历史、复制、粘贴、清屏及断开可用，没有空分隔项 |
| 普通粘贴与命令发送 | 单行、CRLF、heredoc、多行确认、快捷命令和命令编辑器正常 |
| 会话和面板切换 | 主题、SFTP 开关及标签切换不会重建或关闭终端 |
| SSH/SFTP/监控/转发 | 连接、传输、采集、端口流量及重连恢复正常 |
| 独立编辑器 | 打开、编辑、保存、冲突确认和脏数据关闭裁决正常 |
| 多实例 | 配置共享、凭据重启可用、会话与队列隔离、关闭清理正常 |
| RDP | 删除 AI 后标签、Worker 端口和输入接线正常；真实互操作沿用平台验收 |

## 7. 完成标准

- 专用业务文件、辅助函数、测试、schema 和 fixture 已删除，全部引用已清理。
- 共享类型、调用通道、事件、白名单、配置实体和默认设置中不再定义 AI 功能。
- 新数据库不创建 AI 存储结构或初始化记录，代码中没有旧 AI 数据的识别或迁移逻辑。
- 浏览器 mock 和真实 Electron 均只提供现有运维能力。
- 10 种语言、产品介绍、安装包元数据、首发说明和配套文档完成同步清理。
- 多实例冒烟继续通过真实 SSH 认证验证 Vault 持久化，没有把凭据验证随 AI 用例一起删除。
- 类型、i18n、测试、构建、预算和主要应用冒烟通过；受外部平台环境限制的发布检查如实列明。
- 最终差异限定在完整删除及其必要调用方、测试和文档调整，保留工作区已有终端修复。

## 8. 交付物

交付修改后的源码、当前 schema 及生成登记、更新后的测试和冒烟脚本、10 种语言包、README、安装包元数据和一份首发说明。正式发布构建使用这些最终文件。

开发完成说明记录删除范围、保留功能的验证结果及实际未完成项。产品正文不携带开发过程、旧 AI 能力说明或兼容承诺。
