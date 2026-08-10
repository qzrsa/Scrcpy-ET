package qzrs.Scrcpy.easytier;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
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
  private int execPid = -1;
  private volatile boolean childRunning = false;
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
    return status == STATUS_RUNNING && execPid > 0 && childRunning;
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
      return; // extractBinaryFromAssets 末尾会递归调用本次启动
    }
    startEasyTier();
  }

  public void stop() {
    executor.execute(() -> {
      if (execPid > 0) {
        MemfdExec.kill(execPid);
      }
      execPid = -1;
      childRunning = false;
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

        // 读二进制到内存（memfd 执行用）
        byte[] elf = Files.readAllBytes(binaryFile.toPath());
        logLine("[EasyTier] 已读入内存: " + elf.length + " 字节");

        String[] argv = new String[] {
          "easytier-core",
          "-c", confFile.getAbsolutePath()
        };

        logLine("[EasyTier] 通过 memfd 启动（无 root / 绕过 noexec）...");
        MemfdExec.ExecHandle h = MemfdExec.exec(elf, argv);
        execPid = h.pid;
        childRunning = true;
        status = STATUS_RUNNING;
        mainHandler.post(() -> {
          if (listener != null) listener.onStatusChanged(status, currentVpnIp);
        });
        logLine("[EasyTier] 子进程 pid=" + execPid);

        // 读子进程输出
        BufferedReader reader = new BufferedReader(new InputStreamReader(h.out, StandardCharsets.UTF_8));
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

  private String buildConfig(String secret, String networkName, int port, boolean usePublic, String server) {
    StringBuilder sb = new StringBuilder();
    sb.append("instance_secret = \"").append(secret).append("\"\n");
    sb.append("protocol_name = \"").append(networkName).append("\"\n");
    sb.append("listen_port = ").append(port).append("\n");
    // 代理模式：提供本地 SOCKS5，无需 root/无需创建 tun 设备
    sb.append("socks5 = [\"127.0.0.1:1080\"]\n");
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
