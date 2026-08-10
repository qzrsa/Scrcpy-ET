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
  private Process rootProcess = null; // root 模式用的 Process
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
      if (rootProcess != null) {
        rootProcess.destroy();
        try { rootProcess.waitFor(); } catch (Exception ignored) {}
        rootProcess = null;
      } else if (execPid > 0) {
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
        boolean dhcpEnabled = AppData.setting.getEasyTierDhcpEnabled();
        String virtualIp = AppData.setting.getEasyTierVirtualIp();

        // 生成配置
        File confFile = getConfigFile();
        String conf = buildConfig(secret, networkName, port, usePublic, server, dhcpEnabled, virtualIp);
        writeFile(confFile, conf);

        // 读二进制到内存（memfd 执行用）
        byte[] elf = Files.readAllBytes(binaryFile.toPath());
        logLine("[EasyTier] 已读入内存: " + elf.length + " 字节");

        String[] argv = new String[] {
          "easytier-core",
          "-c", confFile.getAbsolutePath()
        };

        // 策略1: 优先尝试 root 执行（最可靠）
        boolean useRoot = false;
        try {
          Process checkSu = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
          if (checkSu.waitFor() == 0) {
            useRoot = true;
            logLine("[EasyTier] 检测到 root，使用 su 执行");
          }
        } catch (Exception ignored) {}

        BufferedReader reader;
        if (useRoot) {
          // root 模式：直接用 su -c 执行 filesDir 下的二进制
          // 使用 ProcessBuilder 合并 stderr，方便诊断（TOML配置错误等信息会输出到 stderr）
          String cmd = getBinaryPath() + " -c " + confFile.getAbsolutePath();
          logLine("[EasyTier] su 命令: su -c " + cmd);
          ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
          pb.redirectErrorStream(true);
          rootProcess = pb.start();
          execPid = -2; // root 模式标记
          reader = new BufferedReader(new InputStreamReader(rootProcess.getInputStream(), StandardCharsets.UTF_8));
        } else {
          // 策略2: memfd 执行（免 root，但部分设备不支持 /proc/self/fd/N 执行）
          logLine("[EasyTier] 尝试 memfd 启动（免 root）...");
          MemfdExec.ExecHandle h = MemfdExec.exec(elf, argv);
          execPid = h.pid;
          reader = new BufferedReader(new InputStreamReader(h.out, StandardCharsets.UTF_8));
        }

        childRunning = true;
        status = STATUS_RUNNING;
        mainHandler.post(() -> {
          if (listener != null) listener.onStatusChanged(status, currentVpnIp);
        });
        logLine("[EasyTier] 子进程 pid=" + execPid);
        String line;
        boolean ipFound = false;

        final StringBuilder lastLines = new StringBuilder();
        int lineCount = 0;
        while ((line = reader.readLine()) != null) {
          logLine("[easytier] " + line);
          // 保留最后20行用于诊断
          if (lineCount < 20) {
            lastLines.append(line).append("\n");
            lineCount++;
          } else {
            int idx = lastLines.indexOf("\n");
            if (idx >= 0) lastLines.delete(0, idx + 1);
            lastLines.append(line).append("\n");
          }

          // 解析 tun 虚拟 IP（优先：这是本机真实获得的网卡地址）
          // 匹配包含 tun0/tun/easytier0+IP，或 "ip" 关键字+IP（v2.6.4可能输出格式不同）
          Matcher m = Pattern.compile("(?:(?:tun0|tun|easytier0)[^\\d]*|virtual[_-]?ip[^\\d]*|ip[^\\d]*)(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(line);
          if (m.find()) {
            currentVpnIp = m.group(1);
            if (!ipFound) {
              ipFound = true;
              status = STATUS_RUNNING;
              mainHandler.post(() -> {
                if (listener != null) listener.onStatusChanged(status, currentVpnIp);
                Toast.makeText(AppData.applicationContext,
                  AppData.applicationContext.getString(R.string.easytier_started),
                  Toast.LENGTH_SHORT).show();
              });
            } else {
              // 已启动，仅更新显示为本机真实虚拟 IP
              mainHandler.post(() -> {
                if (listener != null) listener.onStatusChanged(status, currentVpnIp);
              });
            }
          }
          // SOCKS5 代理监听检测：仅作为启动成功标志，不锁定 IP（让 tun 后续覆盖）
          if (!ipFound && (line.toLowerCase().contains("listening") || line.toLowerCase().contains("socks5"))) {
            ipFound = true;
            status = STATUS_RUNNING;
            currentVpnIp = "127.0.0.1:1080";
            mainHandler.post(() -> {
              if (listener != null) listener.onStatusChanged(status, currentVpnIp);
              Toast.makeText(AppData.applicationContext,
                AppData.applicationContext.getString(R.string.easytier_started),
                Toast.LENGTH_SHORT).show();
            });
          }
        }

        reader.close();

        // 等待子进程结束并获取退出码
        int exitCode;
        if (useRoot && rootProcess != null) {
          exitCode = rootProcess.waitFor();
        } else {
          exitCode = MemfdExec.waitForExit(execPid);
        }
        logLine("[EasyTier] 子进程退出, exitCode=" + exitCode);
        if (exitCode != 0) {
          logLine("[EasyTier] 最后输出:\n" + lastLines.toString());
          if (exitCode == 127 && !useRoot) {
            logLine("[EasyTier] 提示: memfd 执行失败 (127)，设备可能禁止 /proc/self/fd 执行");
            logLine("[EasyTier] 建议: 授予 root 权限，或安装 Termux 手动运行 easytier");
          }
        }

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
    return buildConfig(secret, networkName, port, usePublic, server, true, "");
  }

  private String buildConfig(String secret, String networkName, int port, boolean usePublic, String server,
                             boolean dhcpEnabled, String virtualIp) {
    StringBuilder sb = new StringBuilder();
    // 实例段
    sb.append("instance_name = \"default\"\n");
    sb.append("ipv4 = \"").append((!dhcpEnabled && virtualIp != null && !virtualIp.trim().isEmpty())
        ? virtualIp.trim() : "10.126.126.241").append("\"\n");
    sb.append("dhcp = ").append(dhcpEnabled ? "true" : "false").append("\n");
    sb.append("listeners = []\n");
    sb.append("mapped_listeners = []\n");
    sb.append("exit_nodes = []\n");
    sb.append("rpc_portal = \"127.0.0.1:15888\"\n");
    sb.append("\n");
    // 网络身份段
    sb.append("[network_identity]\n");
    sb.append("network_name = \"").append(networkName).append("\"\n");
    sb.append("network_secret = \"").append(secret).append("\"\n");
    sb.append("\n");
    // 公网中继节点段
    if (usePublic && server != null && !server.trim().isEmpty()) {
      sb.append("[[peer]]\n");
      String uri = server.trim();
      // TOML 解析器要求 URI 必须带 scheme（tcp/udp/ws等），裸 IP 会报错
      if (!uri.contains("://")) {
        if (!uri.contains(":")) uri = uri + ":" + port;
        uri = "tcp://" + uri;
      }
      sb.append("uri = \"").append(uri).append("\"\n");
      sb.append("\n");
    }
    // 高级 flags 段：tun 网卡 + 本地 SOCKS5
    sb.append("[flags]\n");
    sb.append("dev_name = \"tun0\"\n");
    sb.append("socks5 = [\"socks5://0.0.0.0:1080\"]\n");
    sb.append("enable_ipv6 = false\n");
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
