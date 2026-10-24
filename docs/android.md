# Android 客户端说明

本文件只描述这个仓库里的 Android 应用。构建命令以根目录 [README](../README.md) 为准。

## 模块

- `:app`：Compose 界面、本机 shell、更新安装、Shizuku 接入，以及 `src/main/jni` 里的 PTY 宿主。
- `:core`：SSH/SFTP、监控帧、端口转发、局域网同步、AI HTTP 客户端。
- `:storage`：Room 数据库与加密导入导出。

## 线协议

导入导出、局域网同步、监控帧和命令库夹具跟独立维护的桌面客户端使用同一套数据格式，这样一份 v2 口令包可以在两端之间搬运。系统绑定的桌面机密（Windows DPAPI）不在包里，Android 无法解开。

嵌入式 RDP 没有 Android 实现。导入时若配置里带有 RDP 字段，解析应保持兼容，但应用不会打开远程桌面。

## 验证范围

单元测试、`checkI18n`、debug APK、未签名 release APK/AAB 可以由本仓库的 Gradle 任务和 `android.yml` 覆盖。API 26/35 仪器测试在 pull request 上跑，包含导航、Room/Keystore，以及真实 ContentResolver 的文件与 pipe 操作。

下列项仍依赖实机，构建通过不等于验收完成：ARM64 真机、云文档 SAF provider、真实 SSH/AI 服务、Shizuku 与 root 档的特权 PTY。平板横屏工作台、手机底栏，以及把应用切到后台后 SSH 是否仍保持连接，也要在真机上看。强制停止和厂商省电会断开会话，说明见 [android-desktop-alignment.md](android-desktop-alignment.md) 的「会话保活」。
