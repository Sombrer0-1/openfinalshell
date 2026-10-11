# Gradle 工程

OpenFinalShell Android 的 Gradle 根工程。应用说明和构建步骤见仓库根目录的 [README](../README.md)。

模块：

- `:app` — 应用、Compose UI、JNI PTY 宿主
- `:core` — SSH、SFTP、监控、转发、局域网同步、AI
- `:storage` — Room 与加密导入导出

常用任务（在仓库根目录）：

```bash
sh android/gradlew -p android :app:checkI18n testDebugUnitTest
sh android/gradlew -p android :app:assembleDebug
sh android/gradlew -p android :app:lintDebug :app:assembleRelease :app:bundleRelease
```

第三方终端组件的许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
