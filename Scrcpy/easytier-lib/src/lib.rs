//! JNI bridge that embeds the EasyTier core into the Scrcpy Android app.
//!
//! Design mirrors the official EasyTier Android app:
//!   * The Android `VpnService` creates the TUN interface and hands the fd back
//!     to Java via a callback.
//!   * Java calls [`attach_tun_fd`] so the core can use the externally-created
//!     TUN fd (the `run_for_mobile` path) instead of opening `/dev/net/tun`
//!     itself -- this is what makes a true virtual NIC work WITHOUT root.
//!
//! Build with `cargo ndk` (see README.md). Produces `libeasytier_android.so`.

use std::sync::{Arc, OnceLock};

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use uuid::Uuid;

// Imports verified against EasyTier `main` (easytier/src/instance/factory.rs and
// easytier-core/src/config/toml.rs). The `management-rpc` feature on `easytier`
// enables `native_instance_manager_with_runtime` / `NativeInstanceManager` and
// transitively `easytier-core/management-rpc`. `TomlConfig`, `InstanceManager`,
// `attach_tun_fd` and `delete_network_instances` live in easytier-core (no extra
// feature needed). `new_from_str` is an inherent method on `TomlConfig`.
use easytier::instance::factory::{native_instance_manager_with_runtime, NativeInstanceManager};
use easytier_core::config::toml::TomlConfig;

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .worker_threads(2)
            .build()
            .expect("failed to build tokio runtime")
    })
}

// One manager instance must live for the whole process so instances created by
// `start_network` can later be found by `attach_tun_fd` / `stop_network`.
static MANAGER: OnceLock<Arc<NativeInstanceManager>> = OnceLock::new();
fn manager() -> &'static Arc<NativeInstanceManager> {
    MANAGER.get_or_init(|| Arc::new(native_instance_manager_with_runtime(runtime().handle().clone())))
}

static LOGGER: OnceLock<()> = OnceLock::new();
fn init_logger() {
    LOGGER.get_or_init(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("easytier"),
        );
    });
}

fn start_network(toml: String) -> anyhow::Result<String> {
    init_logger();
    // `TomlConfig::new_from_str(&str)` is the verified inherent constructor
    // (replaces the non-existent `load_from_str`).
    let config = TomlConfig::new_from_str(&toml).map_err(|e| anyhow::anyhow!("parse config: {e}"))?;
    let mgr = manager().clone();
    let inst = runtime().block_on(async move {
        let inst = mgr.create(config, ()).map_err(|e| anyhow::anyhow!("create: {e}"))?;
        inst.start().await.map_err(|e| anyhow::anyhow!("start: {e}"))?;
        Ok::<_, anyhow::Error>(inst)
    })?;
    Ok(inst.instance_id().to_string())
}

fn stop_network(id: String) -> anyhow::Result<()> {
    let uuid = Uuid::parse_str(&id).map_err(|e| anyhow::anyhow!("bad id: {e}"))?;
    let mgr = manager().clone();
    runtime().block_on(async move {
        mgr.delete_network_instances([uuid])
            .await
            .map_err(|e| anyhow::anyhow!("stop: {e}"))?;
        Ok(())
    })
}

fn attach_tun_fd(id: String, fd: i32) -> anyhow::Result<()> {
    let uuid = Uuid::parse_str(&id).map_err(|e| anyhow::anyhow!("bad id: {e}"))?;
    // Verified: `InstanceManager::attach_tun_fd(&self, instance_id: Uuid, fd: i32)`
    // lives in easytier-core/src/instance/manager.rs. It feeds the externally
    // created VpnService TUN fd into the mobile TUN path (no /dev/net/tun).
    manager()
        .attach_tun_fd(uuid, fd)
        .map_err(|e| anyhow::anyhow!("attach tun: {e}"))
}

fn get_status() -> String {
    // Minimal for v1. `InstanceManager` exposes `instance_ids()` if a richer
    // status / instance list is wanted later.
    "running".to_string()
}

fn jstr(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(j) => j.into_raw(),
        Err(_) => env.new_string("ERROR: oom").unwrap().into_raw(),
    }
}

fn jin(env: &mut JNIEnv, js: &JString) -> String {
    env.get_string(js).map(|s| s.into()).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_qzrs_Scrcpy_easytier_EasyTierNative_startNetwork(
    mut env: JNIEnv,
    _class: JClass,
    toml: JString,
) -> jstring {
    let input = jin(&mut env, &toml);
    let out = start_network(input).unwrap_or_else(|e| format!("ERROR: {e}"));
    jstr(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_qzrs_Scrcpy_easytier_EasyTierNative_stopNetwork(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
) -> jstring {
    let input = jin(&mut env, &id);
    let out = stop_network(input).map(|_| "OK".to_string()).unwrap_or_else(|e| format!("ERROR: {e}"));
    jstr(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_qzrs_Scrcpy_easytier_EasyTierNative_attachTunFd(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
    fd: jint,
) -> jstring {
    let input = jin(&mut env, &id);
    let out = attach_tun_fd(input, fd)
        .map(|_| "OK".to_string())
        .unwrap_or_else(|e| format!("ERROR: {e}"));
    jstr(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_qzrs_Scrcpy_easytier_EasyTierNative_getStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jstr(&mut env, get_status())
}
