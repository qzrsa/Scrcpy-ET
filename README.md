# Scrcpy-ET

基于 [qzrsa/Scrcpy](https://github.com/qzrsa/Scrcpy)（EasyControl 分支）整合 **EasyTier** 去中心化组网能力的安卓投屏与控制应用。

> 必须给悬浮窗权限！

## 功能

- 原有 Scrcpy 投屏 / 控制能力。
- 内置 EasyTier 虚拟网卡（JNI 嵌入 `easytier-core`，arm64-v8a）：
  - 无需 ROOT：通过 Android `VpnService` 创建 TUN 接口，将其 fd 经 JNI 注入
    EasyTier core 的 `attach_tun_fd`（即官方 `run_for_mobile` 路径），实现「真·虚拟网卡」。
  - 主界面（`EasyTierActivity`）可配置网络名 / 密钥 / IPv4 / 对等节点 / 代理网段，
    启动后在通知栏常驻一个 VPN 连接。
- 应用内「EasyTier」入口按钮位于主界面。

## 目录结构

```
Scrcpy/                      # Android Gradle 工程（:app + :server）
  app/src/main/java/qzrs/Scrcpy/easytier/   # EasyTierActivity / EasyTierVpnService / EasyTierNative
  app/src/main/res/layout/activity_easytier.xml
  easytier-lib/              # Rust cdylib（libeasytier_android.so）JNI 桥
    src/lib.rs
    Cargo.toml
.github/workflows/android_build.yml        # CI：编译 Rust .so + 打包 APK
```

## 构建

本地构建需要 Android SDK + NDK r26d + Rust target `aarch64-linux-android`：

```bash
# 1) 编译 Rust 原生库
cd Scrcpy/easytier-lib
cargo install cargo-ndk            # 或手动配置 $CLANG 链接器
RUSTFLAGS="-C target-feature=+crt-static" cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
# 2) 用 Android Studio / gradle 打包
```

CI（GitHub Actions）会在 push 到 `main` 时自动编译（cargo + debug APK），
手动 `workflow_dispatch` 会产出带签名的 release APK（需仓库 Secrets：
`SIGNING_KEY` / `KEY_ALIAS` / `KEY_PASSWORD` / `STORE_PASSWORD`）。

## ABI

仅 `arm64-v8a`（EasyTier core 当前仅编译该架构）。
