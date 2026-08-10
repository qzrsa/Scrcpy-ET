package qzrs.Scrcpy.easytier;

import android.system.Os;
import android.util.Log;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * 通过 memfd_create + fork + execve 在内存中执行 ELF 字节流，
 * 绕过 Android 对 app 私有目录的 noexec 挂载限制（无需 root，无需 NDK）。
 *
 * 这正是 Termux 解决 noexec 的底层技术（termux-exec 的等价实现）。
 */
public final class MemfdExec {
  private static final String TAG = "MemfdExec";

  public static class ExecHandle {
    public final int pid;
    public final FileDescriptor out;
    ExecHandle(int pid, FileDescriptor out) {
      this.pid = pid;
      this.out = out;
    }
  }

  /**
   * 在子进程中执行 ELF。父进程返回子进程 pid 与用于读取 stdout/stderr 的管道读端。
   * 子进程 execve 成功后永不返回；失败则 exit(1)。
   */
  public static ExecHandle exec(byte[] elf, String[] argv) throws Exception {
    FileDescriptor[] pipe = Os.pipe();
    FileDescriptor readEnd = pipe[0];
    FileDescriptor writeEnd = pipe[1];

    Map<String, String> envMap = System.getenv();
    String[] envp = new String[envMap.size()];
    int i = 0;
    for (Map.Entry<String, String> e : envMap.entrySet()) {
      envp[i++] = e.getKey() + "=" + e.getValue();
    }

    int pid = Os.fork();
    if (pid == 0) {
      try {
        Os.dup2(writeEnd, 1);
        Os.dup2(writeEnd, 2);
        Os.close(readEnd);

        FileDescriptor fd = Os.memfd_create("ezbin", 0);
        ByteBuffer buf = ByteBuffer.wrap(elf);
        while (buf.hasRemaining()) {
          Os.write(fd, buf);
        }
        Os.execve("/proc/self/fd/" + fd.getInt(), argv, envp);
      } catch (Throwable t) {
        Log.e(TAG, "child exec failed", t);
      }
      System.exit(1);
      return null; // unreachable
    }

    Os.close(writeEnd);
    return new ExecHandle(pid, readEnd);
  }
}
