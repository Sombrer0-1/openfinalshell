# OpenFinalShell Android

OpenFinalShell Android 是一个开源的 Android SSH 客户端。本仓库只包含 Android 应用：Kotlin、Jetpack Compose、Apache MINA SSHD、Room 和 Android Keystore。桌面端（Electron）不在这个仓库里，由 [Sombrer0-1/openfinalshell](https://github.com/Sombrer0-1/openfinalshell) 单独维护。

最低系统版本是 Android 8.0（API 26）。调试包会打出 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` 四个 ABI 包，以及一个 universal APK。

## 功能

- SSH 终端、SFTP 浏览与传输、Linux 服务器监控、端口转发
- 加密导入/导出，以及局域网配对传送
- 带认证的 HTTP CONNECT / SOCKS5 代理
- SAF 文件与目录传输
- 命令库，以及 OpenAI 兼容的 AI 助手
- 远程文本编辑（UTF-8、GB18030、GBK、Big5、Latin-1；拒绝二进制和超过 2 MiB 的内容）
- 本机 shell，分三档：应用自身 uid、经 Shizuku 的 ADB shell（uid 2000）、root（uid 0，经 Shizuku-as-root 或 `su`）

不包含嵌入式 RDP，也没有桌面端那种多窗口。本机 shell 在 Google Play 政策下不适合上架，Play 构建用 `-PlocalShell=false` 关掉入口。

AI 配置在设置里，从 SSH 界面打开。Token 放在 Android Keystore，不进入导出和局域网同步。只有明确加入的终端选区会发出去。命令库参与加密导出和局域网同步；命令历史和 AI 配置不参与。

桌面端用系统安全存储（例如 Windows DPAPI）封存的机密不能直接搬过来。换机或从桌面端迁移时，使用 v2 口令加密导出，或重新输入凭据。

## 环境

- JDK 17（编译目标也是 17）
- Android SDK Platform 35、Build-Tools 35.0.0
- NDK `27.0.12077973`、CMake `3.22.1`（本机 PTY 宿主 `libofspty.so` 要用）
- Node.js 仅用于刷新 schema 元数据，不参与 APK 编译

设置 `JAVA_HOME` 和 `ANDROID_HOME`。仓库自带 Gradle Wrapper（8.10.2），会校验发行包的 SHA-256，不需要另外安装 Gradle。

## 构建

在仓库根目录执行。Linux / macOS：

```bash
node scripts/generateSharedSchema.mjs
sh android/gradlew -p android :app:checkI18n testDebugUnitTest
sh android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest
sh android/gradlew -p android :app:lintDebug :app:assembleRelease :app:bundleRelease
```

Windows 把 `sh android/gradlew` 换成 `.\android\gradlew.bat`，任务名相同。

`versionName` 写在 `android/gradle.properties`（当前 `0.30.31`）。未配置签名时，release 任务产出不能当正式包安装的 unsigned APK/AAB，本地试用用 debug APK。四个签名变量必须同时提供，或全部留空：

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

正式发布加上 `-PrequireReleaseSigning=true`。关闭本机 shell：`-PlocalShell=false`。

## 仓库布局

| 路径 | 作用 |
| --- | --- |
| `android/` | Gradle 工程：`:app`、`:core`、`:storage` |
| `shared-schema/` | JSON Schema 与测试夹具。单元测试直接读取 `fixtures/` |
| `scripts/generateSharedSchema.mjs` | 根据 schema 文件名刷新 Kotlin 元数据 |
| `.github/workflows/android.yml` | 单元测试、lint、debug 与未签名 release |
| `.github/workflows/android-release.yml` | 标签 `v*` 上的签名 APK/AAB |

改了 `shared-schema/schemas/` 里的文件名之后，重新运行生成脚本并提交 Kotlin 输出。生成文件带 “Do not edit” 头注释。

## 持续集成

`android.yml` 在 `master` 推送和 pull request 上跑翻译检查、lint、Kotlin 单元测试、debug APK 和未签名 release。API 26 与 API 35 的仪器测试在 pull request 和手动触发时跑。

`android-release.yml` 在 `v*` 标签上打四个 ABI 的签名 APK、universal APK 和 AAB，并上传到对应 GitHub Release。标签必须等于 `android/gradle.properties` 里的 `versionName`（标签 `v0.30.31` 对应 `0.30.31`）。仓库需要这些 secrets：

```text
ANDROID_KEYSTORE_B64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

## 许可

MIT。终端渲染使用 Termux 的 `terminal-emulator` / `terminal-view`，见 [android/THIRD_PARTY_NOTICES.md](android/THIRD_PARTY_NOTICES.md)。
