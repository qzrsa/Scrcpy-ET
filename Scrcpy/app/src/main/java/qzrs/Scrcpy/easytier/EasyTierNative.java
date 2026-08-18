package qzrs.Scrcpy.easytier;

/**
 * JNI bridge to the embedded EasyTier core (libeasytier_android.so).
 * Function names must match the #[no_mangle] exports in easytier-lib/src/lib.rs.
 */
public class EasyTierNative {
  static {
    System.loadLibrary("easytier_android");
  }

  /** Start a network instance from a TOML config string. Returns the instance id, or "ERROR: ...". */
  public static native String startNetwork(String tomlConfig);

  /** Stop a previously started instance by id. Returns "OK" or "ERROR: ...". */
  public static native String stopNetwork(String instanceId);

  /** Hand the VpnService-provided TUN fd to the core. Returns "OK" or "ERROR: ...". */
  public static native String attachTunFd(String instanceId, int fd);

  /** Basic status string. */
  public static native String getStatus();
}
