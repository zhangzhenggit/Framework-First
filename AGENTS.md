# Repository Guidelines

## 项目结构与模块组织

`Framework-First` 是一个单模块 Android Studio 插件工程。

- `src/main/kotlin/com/lenovo/tools/frameworkfirst`：插件核心逻辑，包括 overlay SDK 生成、设置页、状态栏入口和跳转处理。
- `src/main/resources/META-INF/plugin.xml`：插件元数据与扩展点注册。
- `src/main/resources/icons`：插件图标与状态栏图标。
- `release/`：仓库内保留的当前正式发布包。
- `build/`：本地构建产物目录，不提交这里的内容。

## 构建、测试与开发命令

- `.\gradlew.bat buildPlugin --no-daemon`
  生成可安装插件包，输出到 `build/distributions/`。
- `.\gradlew.bat clean buildPlugin --no-daemon`
  从干净环境重新构建，适合发布前验证。

构建前建议设置：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio Panda\jbr'
$env:FRAMEWORK_FIRST_STUDIO_PATH='D:\Android\Android Studio Panda'
$env:GRADLE_USER_HOME="$PWD\.gradle-home"
```

也可在不提交的 `local.properties` 中配置 `studioPath=...`。

## 代码风格与命名约定

- 仅使用 Kotlin，缩进为 4 个空格。
- 包名保持在 `com.lenovo.tools.frameworkfirst` 下。
- 类型命名尽量直接，如 `FrameworkSdkOverlayService`、`FrameworkOriginNavigationHandler`。
- UI 文案保持简短、稳定，避免把调试信息放进正式界面。
- 不要把机器相关绝对路径写入提交内容、默认值或说明文档。

## 测试要求

当前没有自动化测试，提交前至少完成：

- 执行一次 `buildPlugin`
- 在 Android Studio 中手工验证两种模式：
  - `Android SDK`
  - `Framework JAR`
- 复查隐藏 API 消红、跳转目标、设置保存、状态栏行为是否正常

## Commit 与 PR 规范

沿用仓库现有风格：简短、直接、偏动作式或发布式，例如：

- `Finalize 1.0.20 release package`
- `Prepare 1.0.19 release package`
- `Optimize startup overlay scheduling`

PR 建议包含：

- 变更目的与范围
- 用户可见行为变化
- 验证步骤
- 设置页或状态栏改动截图

## 发布与配置说明

- `release/` 目录只保留一个当前版本包，命名为 `Framework-First-<version>.zip`。
- 发布新版本前删除旧版本 ZIP，避免仓库持续膨胀。
- 涉及 IDE 外部缓存或配置清理时，不要隐式处理；需要在文档或说明中明确告知。
