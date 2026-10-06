# 已取消的内置特权实验（截至 v17）

2026-10-03用户改用唯一外置Shizuku。本目录保存被撤出的源码、专属测试、构建逻辑和归属说明，
不在settings.gradle.kts模块或app sourceSets中，也不参与正式APK构建。

- 不执行这里的配对/启动/权限授予工具，不将其Next当成当前任务。
- v17真实配对未通过；归档不是验收通过，也不承诺这些快照能独立构建。
- 主工程保留外置Shizuku执行器及恢复记录；没有删除手机私有密钥或no_backup数据。
- 恢复本实验需用户重新决定、审查安全边界并重新验证，不能仅复制回目录后开启。
- 当前方案与交接读docs/EXTERNAL_SHIZUKU_V18.md、docs/PROJECT_STATE.md、docs/CODEX_HANDOFF.md。

实验中的PrivKit引用/补丁历史遵循原归属说明；主项目不会因归档而重新分发其运行组件。
