# Agent 工作须知（OpenFinalShell Android）

本仓库只有 Android 客户端。不要加回 Electron、npm 桌面脚本、RDP Worker 或桌面 CI。

## 构建

JDK 17，`ANDROID_HOME` 指向带 Platform 35、Build-Tools 35.0.0、NDK `27.0.12077973`、CMake `3.22.1` 的 SDK。在仓库根目录：

```bash
node scripts/generateSharedSchema.mjs
sh android/gradlew -p android :app:checkI18n testDebugUnitTest :app:assembleDebug
```

Windows 使用 `android\gradlew.bat`。Wrapper 固定 Gradle 8.10.2。未签名 release 可以本地编过；能安装的正式包需要四个 `ANDROID_KEYSTORE_*` / `ANDROID_KEY_*` 变量，发布时再加 `-PrequireReleaseSigning=true`。

版本号在 `android/gradle.properties` 的 `versionName`。发布标签必须是 `v` 加这个版本。

## 国际化

用户可见文案放在 `android/app/src/main/res/values*/`，不要在 Kotlin/Compose 里硬编码给用户看的句子。

- 基准是 `values/`。每种可选语言一份 `values-<qualifier>`：`zh-rCN`、`zh-rTW`、`ja-rJP`、`ko-rKR`、`ru-rRU`、`es-rES`、`fr-rFR`、`de-rDE`、`pt-rBR`。英文走默认 `values/`。
- 语言标签枚举在 `AndroidLocales.kt`，须与资源目录一致。
- 提交前跑 `:app:checkI18n`。新增可翻译字符串要在每一种语言里都有同名 key。
- 语言自称（endonym）保持该语言原文，不翻译成另一种语言的叫法。

## 共享 schema

`shared-schema/schemas/` 是协议文件名的来源，`shared-schema/fixtures/` 被 `android/core` 与 `android/storage` 的单元测试直接读取。改 schema 文件名后运行：

```bash
node scripts/generateSharedSchema.mjs
```

并提交 `SharedSchemaRegistry.kt` 与 `GeneratedModels.kt`。不要手改这两个文件。

## 本机 shell

`:app` 用 CMake 编译 `libofspty.so`，给 Shizuku / root 档在特权进程里分配 PTY。缺 NDK 或 CMake 时，Gradle 在配置阶段就会失败，而不只是 native 任务失败。Play 变体用 `-PlocalShell=false`。

## 不要做的事

- 不要把桌面端仓库的 `src/`、`native/rdp-worker`、`electron-builder` 或 npm 脚本复制回来。
- 不要改 [Sombrer0-1/openfinalshell](https://github.com/Sombrer0-1/openfinalshell)。那边继续是桌面产品。
- 导出与局域网同步继续走现有线协议。Windows DPAPI / `materialRef` 不能在 Android 上解密，导入时跳过并提示用户。
