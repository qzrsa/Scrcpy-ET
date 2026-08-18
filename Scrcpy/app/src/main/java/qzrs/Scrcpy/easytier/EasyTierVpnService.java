package qzrs.Scrcpy.easytier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * Creates the Android TUN interface via the VpnService API and hands the file
 * descriptor back to Java. Adapted from EasyTier's official TauriVpnService.kt.
 *
 * The core never opens /dev/net/tun itself -- it receives this fd through
 * EasyTierNative.attachTunFd(), which is what gives a true virtual NIC without root.
 */
public class EasyTierVpnService extends VpnService {
  public static final String EXTRA_IPV4 = "IPV4_ADDR";
  public static final String EXTRA_ROUTES = "ROUTES";
  public static final String EXTRA_DNS = "DNS";
  public static final String EXTRA_DISALLOWED = "DISALLOWED_APPLICATIONS";
  public static final String EXTRA_MTU = "MTU";

  public interface VpnFdCallback {
    void onVpnStart(int fd);

    void onVpnStop();
  }

  private static VpnFdCallback sCallback;

  public static void setCallback(VpnFdCallback cb) {
    sCallback = cb;
  }

  private static final int NOTIF_ID = 9527;
  private static final String CHANNEL_ID = "easytier_vpn";
  private ParcelFileDescriptor vpnInterface;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    startForeground(NOTIF_ID, buildNotification());
    Bundle args = intent != null ? intent.getExtras() : null;
    try {
      vpnInterface = createVpnInterface(args);
    } catch (Exception e) {
      if (sCallback != null) sCallback.onVpnStop();
      stopSelf();
      return START_NOT_STICKY;
    }
    if (sCallback != null) sCallback.onVpnStart(vpnInterface.getFd());
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    disconnect();
    super.onDestroy();
  }

  @Override
  public void onRevoke() {
    disconnect();
    super.onRevoke();
  }

  private void disconnect() {
    if (sCallback != null) sCallback.onVpnStop();
    try {
      if (vpnInterface != null) vpnInterface.close();
    } catch (Exception ignored) {
    }
    vpnInterface = null;
    stopForeground(true);
  }

  private ParcelFileDescriptor createVpnInterface(Bundle args) {
    Builder builder = new Builder().setSession("EasyTier").setBlocking(false);

    int mtu = args != null ? args.getInt(EXTRA_MTU, 1500) : 1500;
    String ipv4 = args != null ? args.getString(EXTRA_IPV4) : null;
    if (ipv4 == null || ipv4.isEmpty()) ipv4 = "10.126.126.2/24";
    String dns = args != null ? args.getString(EXTRA_DNS) : null;
    String[] routes = args != null ? args.getStringArray(EXTRA_ROUTES) : null;
    String[] disallowed = args != null ? args.getStringArray(EXTRA_DISALLOWED) : null;

    String[] parts = ipv4.split("/");
    if (parts.length != 2) throw new IllegalArgumentException("Invalid IP addr: " + ipv4);
    builder.addAddress(parts[0], Integer.parseInt(parts[1]));
    builder.addAddress("fd00::1", 128);
    builder.setMtu(mtu);
    if (dns != null && !dns.isEmpty()) builder.addDnsServer(dns);
    if (routes != null) {
      for (String r : routes) {
        String[] p = r.split("/");
        if (p.length == 2) builder.addRoute(p[0], Integer.parseInt(p[1]));
      }
    }
    if (disallowed != null) {
      for (String app : disallowed) {
        try {
          builder.addDisallowedApplication(app);
        } catch (Exception ignored) {
        }
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);
    return builder.establish();
  }

  private Notification buildNotification() {
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "EasyTier VPN", NotificationManager.IMPORTANCE_LOW);
      nm.createNotificationChannel(ch);
    }
    return new Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("EasyTier")
        .setContentText("虚拟组网运行中")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .build();
  }
}
