package qzrs.Scrcpy.client.tools;

import android.hardware.usb.UsbDevice;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.regex.Pattern;

import qzrs.Scrcpy.adb.Adb;
import qzrs.Scrcpy.easytier.EasyTierManager;
import qzrs.Scrcpy.entity.AppData;
import qzrs.Scrcpy.entity.Device;
import qzrs.Scrcpy.entity.MyInterface;
import qzrs.Scrcpy.helper.PublicTools;

public class AdbTools {
  private static final HashMap<String, Adb> allAdbConnect = new HashMap<>();
  public static final ArrayList<Device> devicesList = new ArrayList<>();
  public static final HashMap<String, UsbDevice> usbDevicesList = new HashMap<>();

  // 连接ADB
  public static Adb connectADB(Device device) throws Exception {
    String addressId = device.isLinkDevice() ? device.address : device.address + ":" + device.adbPort;
    Adb adb = allAdbConnect.get(addressId);
    if (adb == null || adb.isClosed()) {
      if (device.isLinkDevice()) {
        // USB 直连
        adb = new Adb(usbDevicesList.get(addressId), AppData.keyPair);
      } else {
        // 网络设备：支持 EasyTier P2P 路由
        String targetIp;
        int targetPort = device.adbPort;
        EasyTierManager et = EasyTierManager.getInstance();
        if (et.isEnabled() && et.isRunning()) {
          // 走 EasyTier VPN 网络
          targetIp = et.getVpnIp();
        } else {
          // 走原有局域网/WiFi
          targetIp = PublicTools.getIp(device.address);
        }
        adb = new Adb(targetIp, targetPort, AppData.keyPair);
      }
      allAdbConnect.put(addressId, adb);
    }
    return adb;
  }

  public static void runOnceCmd(Device device, String cmd, MyInterface.MyFunctionBoolean handle) {
    new Thread(() -> {
      try {
        Adb adb = connectADB(device);
        adb.runAdbCmd(cmd);
        handle.run(true);
      } catch (Exception ignored) {
        handle.run(false);
      }
    }).start();
  }

  public static void restartOnTcpip(Device device, MyInterface.MyFunctionBoolean handle) {
    new Thread(() -> {
      try {
        Adb adb = connectADB(device);
        String output = adb.restartOnTcpip(5555);
        handle.run(output.contains("restarting"));
      } catch (Exception ignored) {
        handle.run(false);
      }
    }).start();
  }

  public static void pushFile(Device device, InputStream file, String fileName, MyInterface.MyFunctionInt handleProcess) {
    new Thread(() -> {
      try {
        String tempFileName = fileName;
        Adb adb = connectADB(device);
        // 因为糟糕的ADB，如果使用中文名的话，会崩溃，所以此处使用随机名词
        if (!Pattern.compile("^[a-zA-Z0-9\\(\\)\\-\\_\\[\\]\\.]+$").matcher(tempFileName).matches()) {
          int dotIndex = tempFileName.lastIndexOf(".");
          tempFileName = UUID.randomUUID() + (dotIndex == -1 ? "" : tempFileName.substring(dotIndex));
        }
        adb.pushFile(file, "/sdcard/Download/Easycontrol/" + tempFileName, handleProcess);
      } catch (Exception ignored) {
        handleProcess.run(-1);
      }
    }).start();
  }
}
