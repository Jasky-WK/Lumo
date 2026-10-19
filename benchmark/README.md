# 性能基准模块（S4）

这个模块**不参与 App 打包**，只在开发者本机跑性能基准、生成 Baseline Profile 时用到。
构建 APK 不需要设备；只有下面两条命令需要真机（或模拟器）。

## 前置条件

- 一台开启「开发者选项 → USB 调试」的设备，或一个已启动的模拟器（API 28+）
- 设备上关闭动画缩放（开发者选项里三项缩放都调成「关闭」），否则帧率数据不准

## 一、测启动与滚动基线（先测，再优化）

```bash
# Windows（Git Bash / CMD 都行）
set JAVA_HOME=D:\linshi\jdk-17
set ANDROID_HOME=D:\linshi\android-sdk
D:\linshi\gradle-8.7\bin\gradle :benchmark:connectedBenchmarkAndroidTest
```

关注的数字（结果在 `benchmark/build/outputs/androidTest-results/connected/`）：

| 指标 | 含义 | 目标 |
|---|---|---|
| `timeToInitialDisplayMs` | 冷启动到首帧（TTID） | 越低越好，记录为优化基线 |
| `timeToFullDisplayMs` | 冷启动到界面可用（TTFD） | 与 TTID 差值越小越好 |
| `frameDurationCpuMs` P90/P95 | 滚动时单帧耗时 | P95 < 16.6ms（液态玻璃改造后用它守门） |

## 二、生成 Baseline Profile（提升冷启动）

```bash
D:\linshi\gradle-8.7\bin\gradle :benchmark:connectedBenchmarkAndroidTest --tests "*BaselineProfileGenerator*"
```

跑完后：
- profile 由 `androidx.baselineprofile` 插件自动放进 `app/src/main/generated/baselineProfiles/`
- 之后正常 `:app:assembleRelease`，profile 会被打进 APK（不需要其他操作）
- **建议跑完再测一次启动**，对比 TTID 前后差异

## 三、模拟器（无真机时）

```bash
# 需要先 sdkmanager 下载对应系统镜像（约 1GB）
gradlew :benchmark:connectedBenchmarkAndroidTest
```
或使用 Gradle 托管设备（`benchmark/build.gradle.kts` 里加 `managedDevices`，本项目未预置）。

## 说明

- 基准只在 `benchmark` 变体上跑（已关调试、不混淆），贴近真实性能
- `BaselineProfileGenerator` 只覆盖稳定主路径（启动 → 列表滚动 → 三个 Tab），
  不依赖具体文案，避免改 UI 文案就失效
- 想加路径：在 `BaselineProfileGenerator.generate()` 的 `collect` 块里补交互即可
