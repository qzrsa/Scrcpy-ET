package qzrs.Scrcpy.easytier;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import qzrs.Scrcpy.R;
import qzrs.Scrcpy.client.tools.AdbTools;
import qzrs.Scrcpy.entity.AppData;
import qzrs.Scrcpy.entity.Device;
import qzrs.Scrcpy.entity.MyInterface;

public class EasyTierManager {
  private static final String TAG = "EasyTierManager";
  private static final String BINARY_NAME = "easytier-core-aarch64-android";
  private static final String VERSION_URL = "https://api.github.com/repos/qzrsa/easytier-android-build/releases/latest";
  private static final String DOWNLOAD_BASE = "https://github.com/qzrsa/easytier-android-build/releases/download/";

  private static EasyTierManager instance;
  private Process process;
  private ExecutorService executor = Executors.newSingleThreadExecutor();
  private Handler mainHandler = new Handler(Looper.getMainLooper());

  public static final int STATUS_STOPPED = 0;
  public static final int STATUS_DOWNLOADING = 1;
  public static final int STATUS_STARTING = 2;
  public static final int STATUS_RUNNING = 3;
  public static final int STATUS_ERROR = 4;

  private int status = STATUS_STOPPED;
  private String currentVpnIp = "";
  private String currentInstanceSecret = "";

  public interface StatusListener {
    void onStatusChanged(int status, String vpnIp);
    void onLog(String line);
  }

  private StatusListener listener;

  public static synchronized EasyTierManager getInstance() {
    if (instance == null) instance = new EasyTierManager();
    return instance;
  }

  private EasyTierManager() {}

  public void setListener(StatusListener listener) {
    this.listener = listener;
  }

  public int getStatus() {
    return status;
  }

  public String getVpnIp() {
    return currentVpnIp;
  }

  public boolean isRunning() {
    return status == STATUS_RUNNING && process != null && process.isAlive();
  }

  public boolean isEnabled() {
    return AppData.setting.getEasyTierEnabled();
  }

  private Device pickTargetDevice() {
    // 优先从已连接设备列表选
    ArrayList<Device> list = AdbTools.devicesList;
    if (list != null && !list.isEmpty()) {
      for (Device d : list) {
        if (!d.isLinkDevice()) return d;
      }
      return list.get(0);
    }
    // 已连接列表为空，从数据库读取配置的设备
    ArrayList<Device> dbList = AppData.dbHelper.getAll();
    if (dbList != null && !dbList.isEmpty()) {
      for (Device d : dbList) {
        if (!d.isLinkDevice()) return d;
      }
      return dbList.get(0);
    }
    return null;
  }

  public String getBinaryPath() {
    return new File(AppData.applicationContext.getFilesDir(), BINARY_NAME).getAbsolutePath();
  }

  private String getAltBinaryPath() {
    return "/data/local/tmp/" + BINARY_NAME;
  }

  private File getConfigFile() {
    return new File(AppData.applicationContext.getFilesDir(), "easytier.conf");
  }

  // ==================== 启动流程 ====================

  public void ensureBinaryAndStart() {
    if (isRunning()) {
      if (listener != null) listener.onStatusChanged(status, currentVpnIp);
      return;
    }
    File binary = new File(getBinaryPath());
    if (!binary.exists()) {
      extractBinaryFromAssets();
      return;
    }
    executor.execute(() -> {
      try {
        logLine("[EasyTier] 本地二进制就绪: " + binary.length() + " 字节");
        
        // 方式1: 尝试 root 执行 (su -c)
        if (tryStartWithRoot(binary)) {
          return;
        }
        
        // 方式2: 尝试复制到 /data/local/tmp/ 执行
        logLine("[EasyTier] 尝试复制到 /data/local/tmp/ ...");
        if (tryLocalCopyToTmp()) {
          startEasyTier();
          return;
        }
        
        // 方式3: 直接执行 (会失败，但记录日志)
        logLine("[EasyTier] 无 root 且无法写入 tmp，尝试直接执行 (预计失败)...");
        startEasyTier();
      } catch (Exception e) {
        logLine("[EasyTier] 启动异常: " + e.getMessage());
        status = STATUS_ERROR;
        notifyStatus();
      }
    });
  }

  private boolean tryStartWithRoot(File binary) {
    try {
      logLine("[EasyTier] 检测 root 权限...");
      Process check = Runtime.getRuntime().exec("su -c id");
      int checkExit = check.waitFor();
      logLine("[EasyTier] root 检测 exit=" + checkExit);
      if (checkExit != 0) return false;
      
      logLine("[EasyTier] 使用 root 启动...");
      String conf = buildConfig(
        AppData.setting.getEasyTierSecret(),
        AppData.setting.getEasyTierNetworkName(),
        AppData.setting.getEasyTierPort(),
        AppData.setting.getEasyTierUsePublic(),
        AppData.setting.getEasyTierServer()
      );
      File confFile = new File(AppData.applicationContext.getFilesDir(), "easytier.conf");
      writeFile(confFile, conf);
      
      ProcessBuilder pb = new ProcessBuilder();
      pb.command("su", "-c", binary.getAbsolutePath() + " -c " + confFile.getAbsolutePath());
      pb.redirectErrorStream(true);
      process = pb.start();
      status = STATUS_RUNNING;
      notifyStatus();
      
      // 启动日志线程
      executor.execute(() -> readProcessOutput(process));
      // 启动VPN IP检测
      executor.execute(() -> monitorVpnIp());
      return true;
    } catch (Exception e) {
      logLine("[EasyTier] root 启动失败: " + e.getMessage());
      return false;
    }
  }

  public void stop() {
    executor.execute(() -> {
      try {
        if (process != null) {
          process.destroy();
          process.waitFor();
        }
      } catch (Exception ignored) {}
      process = null;
      status = STATUS_STOPPED;
      currentVpnIp = "";
      mainHandler.post(() -> {
        if (listener != null) listener.onStatusChanged(status, "");
      });
    });
  }

  // ==================== 内置二进制 ====================

  private void extractBinaryFromAssets() {
    executor.execute(() -> {
      try {
        logLine("[EasyTier] 释放内置二进制...");
        File binary = new File(getBinaryPath());
        InputStream in = AppData.applicationContext.getAssets().open(BINARY_NAME);
        FileOutputStream out = new FileOutputStream(binary);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
          out.write(buf, 0, len);
        }
        out.close();
        in.close();
        chmodBinary();
        logLine("[EasyTier] 本地文件模式: " + getFileMode(binary.getAbsolutePath()));
        logLine("[EasyTier] canExecute=" + binary.canExecute());
        // 提取成功后递归调用 ensureBinaryAndStart
        startEasyTier();
      } catch (Exception e) {
        logLine("[EasyTier] 内置二进制释放失败，尝试网络下载: " + e.getMessage());
        downloadBinary();
      }
    });
  }

  private boolean tryLocalCopyToTmp() {
    try {
      Process test = Runtime.getRuntime().exec(new String[] { "sh", "-c", "ls -ld /data/local/tmp/ ; id ; whoami" });
      BufferedReader tr = new BufferedReader(new InputStreamReader(test.getInputStream()));
      StringBuilder sb = new StringBuilder();
      String l;
      while ((l = tr.readLine()) != null) sb.append(l).append("|");
      tr.close();
      test.waitFor();
      logLine("[EasyTier] tmp env: " + sb);
      Process p = Runtime.getRuntime().exec(new String[] { "sh", "-c",
        "cat \"" + getBinaryPath() + "\" > \"" + getAltBinaryPath() + "\" && chmod 755 \"" + getAltBinaryPath() + "\"" });
      int exit = p.waitFor();
      return exit == 0 && new File(getAltBinaryPath()).canExecute();
    } catch (Exception e) {
      return false;
    }
  }

  private boolean adbPushToDeviceTmp(Device dev) {
    try {
      final boolean[] done = { false };
      final boolean[] ok = { false };
      InputStream is = AppData.applicationContext.getAssets().open(BINARY_NAME);
      AdbTools.pushToLocalTmp(dev, is, BINARY_NAME, code -> {
        ok[0] = code == 0;
        done[0] = true;
      });
      long deadline = System.currentTimeMillis() + 60000;
      while (!done[0] && System.currentTimeMillis() < deadline) Thread.sleep(200);
      is.close();
      return ok[0];
    } catch (Exception e) {
      logLine("[EasyTier] adb push 异常: " + e.getMessage());
      return false;
    }
  }

  private void launchOnDevice(Device dev) {
    // 通过 adb shell chmod + 后台启动 easytier-core，读 ifconfig tun0 拿 VPN IP
    executor.execute(() -> {
      try {
        String remoteTmp = "/data/local/tmp/" + BINARY_NAME;
        String remoteConf = "/data/local/tmp/easytier.conf";

        // 写配置文件到本地，adb push 上去
        String secret = AppData.setting.getEasyTierSecret();
        String networkName = AppData.setting.getEasyTierNetworkName();
        int port = AppData.setting.getEasyTierPort();
        boolean usePublic = AppData.setting.getEasyTierUsePublic();
        String server = AppData.setting.getEasyTierServer();
        String conf = buildConfig(secret, networkName, port, usePublic, server);

        // 先本地写好
        File confFile = new File(AppData.applicationContext.getFilesDir(), "easytier.conf");
        writeFile(confFile, conf);
        // push config
        final boolean[] confDone = { false };
        AdbTools.pushToLocalTmp(dev, new java.io.ByteArrayInputStream(conf.getBytes(StandardCharsets.UTF_8)), "easytier.conf", code -> confDone[0] = (code == 0));
        long deadline = System.currentTimeMillis() + 30000;
        while (!confDone[0] && System.currentTimeMillis() < deadline) Thread.sleep(200);

        // chmod + 启动
        AdbTools.runOnceCmd(dev, "chmod 755 " + remoteTmp + " && nohup " + remoteTmp + " -c " + remoteConf + " > /data/local/tmp/easytier.log 2>&1 &", success -> {
          logLine("[EasyTier] 远程启动: " + (success ? "ok" : "fail"));
        });

        // 等几秒拿 VPN IP
        Thread.sleep(8000);
        AdbTools.runOnceCmd(dev, "ifconfig tun0 2>/dev/null | grep -oE 'inet [0-9.]+' | head -1", success -> {});
        // 这里 ifconfig 输出需要另一条指令拿到，简化直接 cat /proc/net/dev 或 ifconfig 全文
        AdbTools.runOnceCmd(dev, "ip addr show tun0 2>/dev/null || ifconfig tun0 2>/dev/null", success -> {});

        status = STATUS_RUNNING;
        mainHandler.post(() -> {
          if (listener != null) listener.onStatusChanged(status, "(设备端运行)");
        });
      } catch (Exception e) {
        logLine("[EasyTier] 远程启动失败: " + e.getMessage());
        status = STATUS_ERROR;
        notifyStatus();
      }
    });
  }

  private String getFileMode(String path) {
    try {
      Process p = Runtime.getRuntime().exec(new String[] { "sh", "-c", "ls -l \"" + path + "\"" });
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      String line = r.readLine();
      r.close();
      p.waitFor();
      return line != null ? line : "(null)";
    } catch (Exception e) {
      return "(err: " + e.getMessage() + ")";
    }
  }

  // ==================== 下载二进制 ====================

  private void downloadBinary() {
    status = STATUS_DOWNLOADING;
    notifyStatus();

    executor.execute(() -> {
      try {
        logLine("[EasyTier] 正在获取最新版本信息...");
        String tagName = fetchLatestTag();
        if (tagName == null) {
          // fallback 到已知版本
          tagName = "v2.1.0";
          logLine("[EasyTier] 使用备用版本: " + tagName);
        }

        String downloadUrl = DOWNLOAD_BASE + tagName + "/" + BINARY_NAME;
        logLine("[EasyTier] 下载地址: " + downloadUrl);

        File binary = new File(getBinaryPath());
        downloadFile(downloadUrl, binary);
        chmodBinary();

        mainHandler.post(() -> {
          Toast.makeText(AppData.applicationContext,
            AppData.applicationContext.getString(R.string.easytier_download_success),
            Toast.LENGTH_SHORT).show();
        });

        ensureBinaryAndStart();
      } catch (Exception e) {
        logLine("[EasyTier] 下载失败: " + e.getMessage());
        status = STATUS_ERROR;
        notifyStatus();
      }
    });
  }

  private String fetchLatestTag() throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(VERSION_URL).openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(10000);
    int code = conn.getResponseCode();
    if (code == 200) {
      BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
      reader.close();
      Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(sb.toString());
      if (m.find()) return m.group(1);
    }
    return null;
  }

  private void downloadFile(String urlStr, File output) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
    conn.setConnectTimeout(30000);
    conn.setReadTimeout(30000);
    int code = conn.getResponseCode();
    if (code != 200) throw new RuntimeException("HTTP " + code);

    int total = conn.getContentLength();
    InputStream in = conn.getInputStream();
    FileOutputStream out = new FileOutputStream(output);

    byte[] buf = new byte[8192];
    int downloaded = 0;
    int lastProgress = -1;
    int len;
    while ((len = in.read(buf)) != -1) {
      out.write(buf, 0, len);
      downloaded += len;
      if (total > 0) {
        int progress = downloaded * 100 / total;
        if (progress != lastProgress) {
          lastProgress = progress;
          logLine("[EasyTier] 下载进度: " + progress + "%");
        }
      }
    }
    out.close();
    in.close();
    logLine("[EasyTier] 下载完成");
  }

  private void chmodBinary() throws Exception {
    String path = getBinaryPath();
    Process p = Runtime.getRuntime().exec(new String[] { "sh", "-c", "chmod 755 '" + path + "'" });
    p.waitFor();
  }

  // ==================== 启动 EasyTier ====================

  private void startEasyTier() {
    status = STATUS_STARTING;
    notifyStatus();

    executor.execute(() -> {
      try {
        File binaryFile = new File(getBinaryPath());
        logLine("[EasyTier] 二进制路径: " + binaryFile.getAbsolutePath());
        logLine("[EasyTier] 二进制存在: " + binaryFile.exists() + ", 大小: " + (binaryFile.exists() ? binaryFile.length() : -1));
        logLine("[EasyTier] 可执行: " + binaryFile.canExecute());

        String secret = AppData.setting.getEasyTierSecret();
        String networkName = AppData.setting.getEasyTierNetworkName();
        int port = AppData.setting.getEasyTierPort();
        boolean usePublic = AppData.setting.getEasyTierUsePublic();
        String server = AppData.setting.getEasyTierServer();

        // 生成配置
        File confFile = getConfigFile();
        String conf = buildConfig(secret, networkName, port, usePublic, server);
        writeFile(confFile, conf);

        logLine("[EasyTier] 正在启动...");
        ProcessBuilder pb = new ProcessBuilder();
        // 优先用 /data/local/tmp/ 路径（避开 filesDir noexec）
        File altBin = new File(getAltBinaryPath());
        String execPath = (altBin.exists() && altBin.canExecute()) ? altBin.getAbsolutePath() : getBinaryPath();
        logLine("[EasyTier] 使用二进制路径: " + execPath);
        pb.command(execPath, "-c", confFile.getAbsolutePath());
        pb.redirectErrorStream(true);
        process = pb.start();

        // 读取输出行
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        boolean ipFound = false;

        while ((line = reader.readLine()) != null) {
          logLine("[easytier] " + line);

          // 解析 VPN IP
          if (!ipFound) {
            Matcher m = Pattern.compile("(?:tun0|tun)[^\\d]*(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(line);
            if (m.find()) {
              currentVpnIp = m.group(1);
              ipFound = true;
              status = STATUS_RUNNING;
              mainHandler.post(() -> {
                if (listener != null) listener.onStatusChanged(status, currentVpnIp);
                Toast.makeText(AppData.applicationContext,
                  AppData.applicationContext.getString(R.string.easytier_started),
                  Toast.LENGTH_SHORT).show();
              });
            }
          }

          // 检测进程退出
          if (!isProcessAlive()) {
            logLine("[EasyTier] 进程已退出");
            break;
          }
        }

        reader.close();
        status = STATUS_STOPPED;
        currentVpnIp = "";
        mainHandler.post(() -> {
          if (listener != null) listener.onStatusChanged(status, "");
        });
      } catch (Exception e) {
        logLine("[EasyTier] 启动失败: " + e.getMessage());
        status = STATUS_ERROR;
        notifyStatus();
      }
    });
  }

  private boolean isProcessAlive() {
    try {
      process.exitValue();
      return false;
    } catch (IllegalThreadStateException e) {
      return true;
    }
  }

  private String buildConfig(String secret, String networkName, int port, boolean usePublic, String server) {
    StringBuilder sb = new StringBuilder();
    sb.append("instance_secret = \"").append(secret).append("\"\n");
    sb.append("protocol_name = \"").append(networkName).append("\"\n");
    sb.append("listen_port = ").append(port).append("\n");
    if (usePublic) {
      if (server != null && !server.trim().isEmpty()) {
        sb.append("server = [\"").append(server.trim()).append("\"]\n");
      }
    }
    sb.append("enable_ipv6 = false\n");
    sb.append("compression = 1\n");
    sb.append("encryption = 1\n");
    return sb.toString();
  }

  private void writeFile(File f, String content) throws Exception {
    FileOutputStream out = new FileOutputStream(f);
    out.write(content.getBytes(StandardCharsets.UTF_8));
    out.close();
  }

  // ==================== 工具方法 ====================

  private void logLine(String msg) {
    Log.e(TAG, msg);
    if (listener != null) {
      mainHandler.post(() -> listener.onLog(msg));
    }
  }

  private void notifyStatus() {
    if (listener != null) {
      mainHandler.post(() -> listener.onStatusChanged(status, currentVpnIp));
    }
  }

  private void readProcessOutput(Process proc) {
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
      String line;
      while ((line = reader.readLine()) != null) {
        logLine("[ET] " + line);
      }
    } catch (Exception e) {
      logLine("[EasyTier] 读取输出失败: " + e.getMessage());
    }
  }

  private void monitorVpnIp() {
    try {
      Thread.sleep(5000); // 等5秒让VPN建立
      for (int i = 0; i < 30; i++) { // 最多等30次
        String ip = getVpnIpFromSystem();
        if (ip != null && !ip.isEmpty()) {
          currentVpnIp = ip;
          notifyStatus();
          logLine("[EasyTier] VPN IP: " + ip);
          break;
        }
        Thread.sleep(2000);
      }
    } catch (Exception e) {
      logLine("[EasyTier] VPN IP检测失败: " + e.getMessage());
    }
  }

  private String getVpnIpFromSystem() {
    try {
      Process p = Runtime.getRuntime().exec("ifconfig tun0");
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      String line;
      while ((line = r.readLine()) != null) {
        Matcher m = Pattern.compile("inet ([0-9.]+)").matcher(line);
        if (m.find()) return m.group(1);
      }
      // 尝试 ip 命令
      p = Runtime.getRuntime().exec("ip addr show tun0");
      r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      while ((line = r.readLine()) != null) {
        Matcher m = Pattern.compile("inet ([0-9.]+)/").matcher(line);
        if (m.find()) return m.group(1);
      }
    } catch (Exception e) {}
    return "";
  }

  public static String getStatusText(int status) {
    switch (status) {
      case STATUS_STOPPED:    return "已停止";
      case STATUS_DOWNLOADING: return "下载中";
      case STATUS_STARTING:   return "启动中";
      case STATUS_RUNNING:    return "运行中";
      case STATUS_ERROR:      return "错误";
      default:                return "未知";
    }
  }
}
