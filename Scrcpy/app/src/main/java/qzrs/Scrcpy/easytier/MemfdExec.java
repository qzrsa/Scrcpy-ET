package qzrs.Scrcpy.easytier;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;

/**
 * 通过 memfd_create + fork + execve 在内存中执行 ELF 字节流，
 * 绕过 Android 对 app 私有目录的 noexec 挂载限制（无需 root，无需 NDK 在 CI 中编译）。
 *
 * 实际 fork/execve 由原生库 libexecmem.so 完成（见 cpp/execmem.c），
 * 因为 android.system.Os 并未暴露 fork/execve。
 */
public final class MemfdExec {
  private static final String TAG = "MemfdExec";

  static {
    System.loadLibrary("execmem");
  }

  public static class ExecHandle {
    public final int pid;
    public final FileInputStream out;
    ExecHandle(int pid, FileInputStream out) {
      this.pid = pid;
      this.out = out;
    }
  }

  // 高32位=pid, 低32位=管道读端fd
  private static native long nativeExec(byte[] elf, String[] argv);
  private static native void nativeKill(int pid);
  private static native int nativeWait(int pid); // 返回退出码

  public static int waitForExit(int pid) {
    try {
      return nativeWait(pid);
    } catch (Throwable t) {
      Log.e(TAG, "waitForExit fail", t);
      return -1;
    }
  }

  public static ExecHandle exec(byte[] elf, String[] argv) throws Exception {
    long combo = nativeExec(elf, argv);
    if (combo < 0) throw new RuntimeException("nativeExec 失败");
    int pid = (int) (combo >>> 32);
    int fd = (int) (combo & 0xffffffffL);
    ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(fd);
    return new ExecHandle(pid, new FileInputStream(pfd.getFileDescriptor()));
  }

  public static void kill(int pid) {
    try {
      nativeKill(pid);
    } catch (Throwable t) {
      Log.e(TAG, "kill fail", t);
    }
  }
}
