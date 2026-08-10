# EasyTier 免 root 执行方案（memfd + 原生 helper）— 2026-08-10

## 问题
Android 11+ 把 app 私有目录（`/data/data/<pkg>/files/`、`/data/local/tmp/`）挂载为 **noexec**，
即使 `chmod 755` + `canExecute()=true` 也无法 `execve()`。控制端手机自身就是要加入 VPN 的端点，
必须在本机跑 easytier-core。

## 失败尝试
- 纯 Java `android.system.Os` + `memfd_create`：编译报错——**`Os` 未暴露 `fork()` / `execve()`**
  （只有 memfd_create/pipe/dup2 等）。无法在纯 Java 里 fork 子进程执行。
- adb push 到被控端 /tmp：控制端未连接设备时拿不到 Device，且被控端不是 VPN 端点（架构误解）。

## 最终方案：原生 helper（libexecmem.so）
- 新增 `cpp/execmem.c`：JNI 函数 `nativeExec(byte[] elf, String[] argv)`
  - 父进程 `pipe()` 建输出管道
  - `fork()` → 子进程 `dup2` 管道写端到 stdout/stderr，`memfd_create("ezbin")`，
    把 ELF 字节流 `write` 进 memfd，`execve("/proc/self/fd/N", argv, environ)` 执行
  - 父进程返回 `jlong`：高32位=pid，低32位=管道读端 fd
  - `nativeKill(int pid)` → `kill(pid, SIGKILL)`
- `MemfdExec.java`：`System.loadLibrary("execmem")`；`exec()` 用
  `ParcelFileDescriptor.adoptFd(fd)` 接管读端 → 返回 `ExecHandle(pid, FileInputStream)`
- 用 NDK 26 本地编译：`aarch64-linux-android21-clang -shared -fPIC -llog`
  → 产物 `app/src/main/jniLibs/arm64-v8a/libexecmem.so`（9072 字节），**直接提交，CI 无需装 NDK**
- `EasyTierManager.startEasyTier()`：读 assets 里的 easytier-core 到 `byte[]` → `MemfdExec.exec()`
  → 直接 `status=STATUS_RUNNING`（代理模式无 tun IP，不再依赖解析 tun0）
- `stop()`：`MemfdExec.kill(execPid)` + 复位

## 无 root 关键：SOCKS5 代理模式
`buildConfig()` 增加 `socks5 = ["127.0.0.1:1080"]`，使 easytier 以代理模式运行，
**无需创建 tun 设备、无需 root**。端口仍用 `device.adbPort` 走原链路。

## 构建结果
- commit `0acfa85` 推送 main
- CI run 31361271280 全部通过（含 Build App）
- 发布：`https://github.com/qzrsa/Scrcpy-ET/releases/tag/2026.08.10-0616`
  APK: `Scrcpy.apk`（version 2.1.0-ET-native, code 10005）
- 仅 arm64-v8a（easytier 为 aarch64 静态二进制）

## 待验证 / 下一步
- 真机安装测试：开启 EasyTier → 状态点变绿、日志出现 `listening`/`socks5`
- 要让 Scrcpy 真正把 adb 流量走 VPN，需让 `AdbTools` 使用 SOCKS5（127.0.0.1:1080）
  或（root/ tun 场景）直接用 VPN IP；目前 `connectADB()` 路由仅把 host 换成 `et.getVpnIp()`
- arm64 之外设备（x86/armv7）原生 lib 缺失会 UnsatisfiedLinkError，当前按 arm64-only 处理
