# Shared schema

JSON Schema 文件和黄金夹具，供 Android 客户端使用。

- `schemas/` 里的文件名会写进 `SharedSchemaRegistry`。改名或增删 schema 后，在仓库根目录运行 `node scripts/generateSharedSchema.mjs`，并提交生成的 Kotlin 文件。
- `fixtures/` 被 Android 单元测试直接读取（AI 模型列表、命令库）。不要改夹具而不改对应测试期望。

这些文件描述客户端之间交换的数据，不描述界面或 Gradle 模块边界。
