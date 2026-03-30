# Framework-First

`Framework-First` 是一个 Android Studio 插件，用来解决这类 Android 源码工程里的 IDE 解析偏差：

- Gradle 编译能看到 `.common_libs/framework.jar`
- Android Studio 代码洞察仍优先使用标准 SDK `android.jar`
- 导致隐藏 API 报红、补全缺成员、跳转落错目标

插件只修改 IDE 侧行为，不改 Gradle 编译逻辑。

## 作用原理

插件会根据当前工程实际使用的 Android SDK 和 `framework.jar`，在 IDE system cache 下生成一份仅供 IDE 使用的 overlay SDK：

- 以官方 `android.jar` 为底
- 增量合并 `framework.jar` 中缺失的类、字段、方法
- 把 AndroidFacet 模块重新绑定到这份 overlay SDK

这样做的结果是：

- 默认模式下，优先保持 Android SDK 的正常源码导航和类型系统
- 需要看 framework 实现时，可以切到 `Framework JAR`
- 两种模式都只影响 IDE 的解析、补全和跳转，不影响实际编译

## 适用场景

适合这类工程：

- 工程根目录存在 `.common_libs/framework.jar`
- Gradle 编译能通过，但 Android Studio 里有隐藏 API 报红
- 需要在 Android SDK 视图和 framework 实现视图之间切换看代码

## 核心功能

- 自动发现 `framework.jar`
- 为当前工程生成并复用 overlay SDK
- 当前工程级开关：`Enable / Disable`
- 当前工程级 API 查找优先级：
  - `Android SDK`
  - `Framework JAR`
- 支持自定义 `framework.jar` 路径
- 支持把跳转重定向到原始 SDK source 或原始 `framework.jar`

## 两种模式

### Android SDK

默认模式，适合日常开发。

- SDK 有源码时，优先跳原始 Android SDK source
- SDK 没源码时，回退到原始 Android SDK class
- SDK 没有该类或成员时，再回退到原始 `framework.jar`

### Framework JAR

适合排查 framework 新增逻辑或平台差异。

- framework 中已有的类优先打开原始 `framework.jar` 对应的 class
- framework 中没有的类再回退到原始 Android SDK source / class
- 不改变编译逻辑，只改变 IDE 查找与跳转优先级

## 自动发现策略

当前发现顺序如下：

1. 项目根目录 `.common_libs/framework.jar`
2. 项目根目录 `common_libs/framework.jar`
3. 扫描工程内 `build.gradle` / `build.gradle.kts` / `settings.gradle` / `settings.gradle.kts`
4. 工程目录内有限深度搜索 `framework.jar`

如果在 `Settings | Tools | Framework-First` 中配置了自定义路径，则优先使用自定义路径。

## 使用方法

1. 在 Android Studio 里安装插件 zip
2. 重启 IDE 并重新打开工程
3. 确认右下角出现 `Framework-First` 图标
4. 默认直接使用 `Android SDK` 模式
5. 如需查看 framework 实现，可在设置页把 `Code Insight Base` 切到 `Framework JAR`

设置页入口：

- `Settings | Tools | Framework-First`

状态栏入口：

- 右下角 `Framework-First` 图标

## 设置项说明

### Framework Jar Path

- 默认显示当前生效路径
- 未自定义时，显示自动发现到的路径
- 自定义后，显示用户选择的路径
- `Reset` 会清除自定义覆盖并恢复为自动发现路径

### Code Insight Base

- `Android SDK`
- `Framework JAR`

这项是当前工程级设置，只保存在 IDE 配置目录下的插件私有状态文件中，不写回目标工程。

## 缓存与状态

插件运行时会在 IDE 外部目录写两类数据：

### 1. IDE system cache

用于保存 overlay SDK 和 merged `android.jar`。

通用路径形态：

- `<IDE system>/Framework-First/cache/<fingerprint>/sdk`

### 2. IDE config

用于保存当前工程的开关状态、自定义 `framework.jar` 路径和当前模式。

通用路径形态：

- `<IDE config>/Framework-First/project-state.properties`

## 清理旧缓存

如果需要彻底排除旧版本干扰，建议清理这两类目录：

- IDE system cache 下的 `Framework-First`
- IDE config 下的 `Framework-First`

清理后影响：

- overlay SDK 会在下次打开工程时重新生成
- 当前工程的插件开关状态、自定义路径、模式设置会恢复默认

## 构建要求

构建插件需要一份本机 Android Studio 安装路径。推荐两种方式二选一：

1. 设置环境变量

```powershell
$env:FRAMEWORK_FIRST_STUDIO_PATH='D:\Android\Android Studio Panda'
```

2. 或在当前工程根目录放一个不提交版本库的 `local.properties`

```properties
studioPath=D:/Android/Android Studio Panda
```

## 构建方式

在本工程根目录执行：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio Panda\jbr'
$env:GRADLE_USER_HOME="$PWD\.gradle-home"
.\gradlew.bat buildPlugin --no-daemon
```

构建产物：

- `build/distributions/Framework-First-<version>.zip`

仓库内同时保留一份当前发布包：

- `release/Framework-First-<version>.zip`

## 安装方式

在 Android Studio 中选择：

- `Settings / Plugins / Install Plugin from Disk...`

然后选择 zip 包并重启 IDE。

## 验证建议

建议验证：

- 隐藏 API 不再报红
- `Android SDK` 模式下优先跳 SDK source
- `Framework JAR` 模式下优先跳 framework class
- 切换模式后跳转目标和路径显示符合预期

## 兼容性

- 当前版本声明支持 Android Studio `253.*`
- 不承诺未来所有更高版本自动兼容
- 如果后续要支持新的 Android Studio baseline，需要单独验证

## 下载建议

当前建议两种发布方式：

1. 仓库内保留 `release/Framework-First-<version>.zip`
   - 简单直接
   - 团队成员可以直接在 GitHub 仓库里下载
2. 后续再补 GitHub Release
   - 更适合正式对外分发
   - 可以把 zip 作为 Release Asset 提供下载

当前仓库内保留 `release/` 目录，适合作为团队内部下载入口。
