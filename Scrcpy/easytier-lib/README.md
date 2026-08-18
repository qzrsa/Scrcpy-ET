# EasyTier 安卓集成（JNI 嵌入核心）

把 [EasyTier](https://github.com/EasyTier/EasyTier) 的核心通过 JNI 嵌进 Scrcpy 安卓 App，
用 Android 的 `VpnService` 建 TUN 并把 fd 交给核心，从而在**非 root** 设备上也能拿到真·虚拟网卡。

机制与官方 EasyTier 安卓 App 一致：核心不在独立进程里自己开 `/dev/net/tun`，
而是由 `EasyTierVpnService` 用 `VpnService.Builder.establish()` 建好 TUN，
再通过 `EasyTierNative.attachTunFd(id, fd)` 注入核心（核心走 `run_for_mobile` 路径）。

## 目录结构

```
Scrcpy/Scrcpy/
├── easytier-lib/                # Rust cdylib，编译出 libeasytier_android.so
│   ├── Cargo.toml
│   └── src/lib.rs
└── app/src/main/
    ├── java/qzrs/Scrcpy/easytier/
    │   ├── EasyTierNative.java      # JNI 声明 + System.loadLibrary
    │   ├── EasyTierVpnService.java   # VpnService，建 TUN 并回传 fd
    │   └── EasyTierActivity.java     # 配置界面 + 启停编排
    ├── res/layout/activity_easytier.xml
    └── jniLibs/arm64-v8a/libeasytier_android.so   # 由 CI 生成，不入库
```

## 本地构建（需要先装好 Rust + Android NDK）

```bash
# 1. 安装 cargo-ndk（或用手动 config.toml + clang linker，见 CI）
cargo install cargo-ndk --locked
rustup target add aarch64-linux-android

# 2. 设置 NDK 路径（ANDROID_NDK_HOME 指向 NDK r26d 根目录）
export ANDROID_NDK_HOME=/path/to/android-ndk-r26d

# 3. 编译
cd Scrcpy/Scrcpy/easytier-lib
RUSTFLAGS="-C target-feature=+crt-static" \
  cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release

# 4. 正常用 Gradle 打包 App（.so 会被打进 APK）
```

## CI

`.github/workflows/android_build.yml` 在打包 App 之前新增了：
安装 Rust + Android target、`nttld/setup-ndk`(r26d)、`protobuf-compiler`，
然后 `cargo build --target aarch64-linux-android` 并把 `libeasytier_android.so`
复制到 `app/src/main/jniLibs/arm64-v8a/`。

## 需要验证 / 可能要改的点（首次编译时）

代码里已用 `VERIFY` 注释标出。我**无法在此环境编译/运行验证**，以下 API 以
EasyTier `main` 分支为准，首次构建大概率需要微调：

1. **依赖声明**（`Cargo.toml`）：`easytier` / `easytier-core` 的 git 源、`package` 名、
   feature（`management-rpc`）要和所编译的 EasyTier 版本一致。建议把 `rev = "<commit>"`
   钉死，避免 `main` 移动导致 API 漂移。
2. **`TomlConfig::load_from_str`**：加载 TOML 配置的方法名来自 `ConfigLoader` trait。
   若编译报错，尝试 `easytier::common::config::TomlConfigLoader::load_from_str`。
3. **`attach_tun_fd(uuid, fd)` / `delete_network_instances(...)`**：方法签名与是否为
   `async` 以实际 `InstanceManager` 为准。
4. **`native_instance_manager_with_runtime(handle)`**：需要 `management-rpc` feature。
5. **Rust 工具链**：EasyTier 可能用 `rust-toolchain.toml` 钉版本。若 `cargo build`
   报“target 未安装”，在 `easytier-lib` 目录执行 `rustup target add aarch64-linux-android`
   （针对被解析到的工具链）。
6. **protoc**：EasyTier 依赖 protobuf，构建机需有 `protobuf-compiler`。

## 当前限制（v1）

- 仅 `arm64-v8a`（核心只编了这个架构；如需其它架构要扩展构建目标）。
- 虚拟 IP 当前用界面里填的静态地址（如 `10.126.126.2/24`），未启用核心 DHCP。
- `getStatus()` 仅返回 "running"，详细节点/路由信息后续可接核心 RPC 补充。
- 非 root 的“真·虚拟网卡”依赖 `VpnService`，首次启动会向用户申请 VPN 权限。
