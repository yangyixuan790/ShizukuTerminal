# Shizuku Terminal

一个基于 [Shizuku](https://shizuku.rikka.app/) API 的 Android 终端工具，让你在不需要真正 Root 的情况下，也能以 `shell`（ADB）身份执行任意 shell 命令。

同时提供了无 Shizuku 环境下的降级模式（普通 `Runtime.exec`），保证应用在任何设备上都能跑起来。

---

## ✨ 特性

- 🎨 **终端风格 UI**：深色主题 + 绿字提示符 + 彩色 stdout/stderr 输出，体验贴近真终端
- ⚡ **双模式执行引擎**
  - **Shizuku 模式**：通过反射调用 `Shizuku.newProcess`，使用 ADB / Root UID 执行命令，可访问 `/data`、`/system`、`settings`、`pm grant`、`dumpsys` 等受限 API
  - **普通模式**：`Runtime.getRuntime().exec("sh -c ...")` 作为 fallback，无需任何外部依赖
- 🛡 **智能三态状态条**：实时显示 Shizuku 连接状态
  - 🔴 未连接：Shizuku 服务不存在（需要先装 Shizuku App）
  - 🟡 未授权：服务已在，但用户未在 Shizuku Manager 里授权
  - 🟢 已连接：一切就绪，可使用高级权限命令
- 🧩 **零代码混淆 & 无额外二进制依赖**：纯 Kotlin + AndroidX，反射安全调用 Shizuku API
- 📦 **小体积 APK**：Release APK 通常 < 5 MB
- 🎯 **兼容性广泛**：`minSdk 24` (Android 7.0+) → `targetSdk 34` (Android 14)

---

## 📱 Screenshots

> ⚠️ 欢迎提交 PR 补充真实截图：在 `art/` 目录下加入 `screenshot-main.png`、`screenshot-shizuku-connected.png`、`screenshot-command-output.png` 即可。

| 主界面（未授权） | Shizuku 已授权 |
|:---:|:---:|
| ![placeholder](https://via.placeholder.com/360x720/0f172a/22c55e?text=Main+UI) | ![placeholder](https://via.placeholder.com/360x720/0f172a/f59e0b?text=Shizuku+On) |

| 命令执行输出 | 终端输入区 |
|:---:|:---:|
| ![placeholder](https://via.placeholder.com/360x720/0f172a/38bdf8?text=stdout+output) | ![placeholder](https://via.placeholder.com/360x720/0f172a/a855f4?text=cmd+input) |

---

## 🚀 快速开始

### 方式 1：使用预构建 APK（推荐）

1. 打开本仓库 [Releases](https://github.com/yangyixuan790/ShizukuTerminal/releases) 页面，下载最新版本 `.apk`
2. 安装到你的 Android 设备（允许未知来源）
3. 安装并启动 [Shizuku App](https://shizuku.rikka.app/)（通过"无线调试"ADB 或 Root 方式启动服务）
4. 打开本 App → 点击「申请权限」→ 在 Shizuku Manager 授权窗口点击确定
5. 顶部状态变为 **🟢 已连接 / 执行模式: Shizuku** → 开跑！

### 方式 2：自己编译（Android Studio / 命令行）

```bash
# 1. Clone
git clone https://github.com/yangyixuan790/ShizukuTerminal.git
cd ShizukuTerminal

# 2. 确保 Android SDK 34 已安装，且 local.properties 指向正确路径
#    （Android Studio 打开时会自动写入）
# echo "sdk.dir=/path/to/your/Android/Sdk" > local.properties

# 3. 构建 Debug APK
./gradlew assembleDebug          # 产物: app/build/outputs/apk/debug/app-debug.apk

# 4. 构建 Release APK（需要自己配置 signingConfig 或用 apksigner 后签）
./gradlew assembleRelease

# 5. 或直接安装到已连接的设备
./gradlew installDebug
```

---

## 💡 命令示例

Shizuku 授权后，下面这些都可以直接跑：

```bash
# 查看其它应用私有目录（普通权限不可）
ls -la /data/data

# 查看顶层 Activity
dumpsys activity top | head -20

# 列出所有用户安装包
pm list packages -3

# 给其它应用动态授权
pm grant com.example.app android.permission.ACCESS_FINE_LOCATION

# 读取/修改系统设置
settings get global http_proxy
settings put global window_animation_scale 0.5

# 查看 SELinux 上下文
ls -laZ /sys/fs/selinux
```

普通模式（未授权）下可以执行常见命令：
```bash
ls -la /sdcard
echo $PATH
ps -A | grep -i shizuku
uname -a
```

---

## 🧱 技术栈 & 架构

| 模块 | 选型 |
|---|---|
| 语言 | **Kotlin 1.9.22** |
| 构建 | **Gradle 8.4** + **AGP 8.1.4** |
| SDK | `compileSdk 34` / `minSdk 24` / `targetSdk 34` |
| UI 框架 | 原生 **ConstraintLayout**，无 Jetpack Compose 依赖 |
| 主题 | 深色 Material **AppCompat**（`Theme.MaterialComponents.DayNight.NoActionBar`） |
| Shizuku 集成 | `dev.rikka.shizuku:api:13.1.5` + `provider:13.1.5`（运行时通过反射安全调用，ClassNotFound 时自动 fallback） |
| 命令执行 | `Shizuku.newProcess` / `Runtime.getRuntime().exec("sh", "-c", cmd)` 双实现 |

核心代码：
- [`ShizukuExecutor`](app/src/main/java/com/shizuku/terminal/ShizukuExecutor.kt)：封装权限检查、Shizuku API 反射调用、双模式执行
- [`MainActivity`](app/src/main/java/com/shizuku/terminal/MainActivity.kt)：终端 UI、状态机、命令输入/输出渲染、滚动/清空逻辑
- [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)：声明 `ShizukuProvider`、权限、`queries` 包名

---

## 🤖 CI / 自动构建

本仓库已内置 GitHub Actions 工作流：

- **每次 push / PR 到 main** → 自动构建 Debug APK → 上传为 Artifact
- **打 v* 标签 (v1.0.0 等)** → 自动构建 Release APK → 创建 GitHub Release Draft 并附带 APK 产物

见：[`.github/workflows/build.yml`](.github/workflows/build.yml)

---

## 🧾 License

本项目采用 [MIT License](LICENSE) 开源。

```
MIT License - Copyright (c) 2026 yangyixuan790
```

> Shizuku 本身是 **RikkaApps** 的作品，遵循其各自的开源协议。
> Shizuku 官网：https://shizuku.rikka.app/ ， GitHub：https://github.com/RikkaApps/Shizuku

---

## 🙏 致谢

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) - 没有它就没有这个 App
- [Gradle Wrapper](gradle/wrapper/gradle-wrapper.properties) / Android 开源生态
