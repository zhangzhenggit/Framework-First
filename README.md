# Framework-First

`Framework-First` 是一个独立的 Android Studio 插件工程，用来解决这类 Android 源码工程里的 IDE 解析问题：

- Gradle 编译能看到 `.common_libs/framework.jar`
- Android Studio 代码洞察仍优先使用标准 SDK `android.jar`
- 导致隐藏 API 报红、补全缺成员、跳转不正确

当前稳定主线是 `overlay-home`：

- 插件只改 IDE 侧解析，不改 Gradle 编译逻辑
- 对每个唯一的 `framework.jar + base SDK` 组合，在 **IDE system cache** 下生成一份合成 Android SDK
- 这份合成 SDK 复用原始平台目录结构，但不会直接用 `framework.jar` 顶掉官方 `android.jar`
- 插件会以官方 `android.jar` 为底，增量合并 `framework.jar` 中缺失的隐藏字段、方法和类，生成 IDE 使用的 merged `android.jar`
- Android 模块会被重新绑定到这份 IDE-only overlay SDK，用于代码解析、补全、跳转

## 目录说明

- `src/main/kotlin/com/lenovo/tools/frameworkfirst/FrameworkOverlayService.kt`
  负责识别 Android 模块、切换 overlay SDK、清理旧实验遗留
- `src/main/kotlin/com/lenovo/tools/frameworkfirst/FrameworkSdkOverlayService.kt`
  负责合成 SDK 缓存、fingerprint 复用、旧缓存清理
- `src/main/kotlin/com/lenovo/tools/frameworkfirst/FrameworkOverlayStartupActivity.kt`
  项目打开后重放 overlay
- `src/main/kotlin/com/lenovo/tools/frameworkfirst/FrameworkOverlaySyncStep.kt`
  Gradle Sync 后重放 overlay

## 当前约束

- 当前版本默认只识别项目根目录下的 `.common_libs/framework.jar`
- 不在目标工程目录生成配置文件
- 运行时缓存不落目标工程根目录，而是落 IDE system cache
- 当前发布策略只声明支持 Android Studio `253.*`
- `framework.jar` 只用于补充官方 SDK 中缺失的 Android 平台成员，不会覆盖 `java.* / libcore` 这层标准类型系统

## 缓存策略

缓存位置在 IDE system cache 下，路径形如：

- `.../system/Framework-First/cache/<fingerprint>/sdk`

特点：

- 同一份 `framework.jar` 内容和同一 base SDK 会复用同一缓存
- 多工程同时打开不会互相覆盖
- 旧缓存会按最后访问时间清理
- 不会在目标工程根目录生成 `.framework-first-sdk`
- overlay 使用的是 merged `android.jar`，能减少“hidden API 修好了，但标准 SDK 反而出现假红”的问题

## 构建要求

构建插件需要一份本机 Android Studio 安装路径。推荐两种方式二选一：

1. 设置环境变量

```powershell
$env:FRAMEWORK_FIRST_STUDIO_PATH='D:\Android\Android Studio Panda'
```

2. 或在当前工程根目录放一个 **不提交版本库** 的 `local.properties`

```properties
studioPath=D:/Android/Android Studio Panda
```

## 构建方式

推荐在本工程根目录执行：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio Panda\jbr'
$env:GRADLE_USER_HOME="$PWD\.gradle-home"
.\gradlew.bat buildPlugin --no-daemon
```

产物位置：

- `build/distributions/Framework-First-<version>.zip`

## 安装方式

在 Android Studio 中选择：

- `Settings / Plugins / Install Plugin from Disk...`

然后选择 `build/distributions` 下的 zip 包并重启 IDE。

## 验证入口

当前建议验证类：

- `Settings/src/com/android/settings/notification/modes/ZenModeOtherLinkPreferenceController.java`

预期行为：

- 代码不再因为 `framework.jar` 里的隐藏成员而报红
- 补全可见对应隐藏 API
- 跳转优先进入源码视图，而不是错误版本的标准 SDK stub

## 兼容性说明

- 当前版本面向 Android Studio `253.*`
- 不是只锁当前一个精确小版本
- 也不承诺未来所有更高版本自动兼容
- 如果后续要支持新的 Android Studio baseline，需要单独验证后再放开
