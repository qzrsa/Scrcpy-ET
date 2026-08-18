package qzrs.Scrcpy.easytier;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import qzrs.Scrcpy.R;
import qzrs.Scrcpy.databinding.ActivityEasytierBinding;

public class EasyTierActivity extends Activity {
  private ActivityEasytierBinding binding;
  private static final int REQUEST_PREPARE_VPN = 1001;

  private String runningId = null;
  private boolean running = false;
  // holds the TOML built at start time, used once the TUN fd arrives
  private String pendingToml = null;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivityEasytierBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    binding.buttonBack.setOnClickListener(v -> finish());
    binding.buttonToggle.setOnClickListener(v -> {
      if (running) stopVpn();
      else prepareAndStart();
    });

    EasyTierVpnService.setCallback(new EasyTierVpnService.VpnFdCallback() {
      @Override
      public void onVpnStart(int fd) {
        if (pendingToml == null) return;
        String id = EasyTierNative.startNetwork(pendingToml);
        if (id.startsWith("ERROR:")) {
          Toast.makeText(EasyTierActivity.this, id, Toast.LENGTH_LONG).show();
          stopService(new Intent(EasyTierActivity.this, EasyTierVpnService.class));
          setStopped();
          return;
        }
        String attach = EasyTierNative.attachTunFd(id, fd);
        if (attach.startsWith("ERROR:")) {
          Toast.makeText(EasyTierActivity.this, attach, Toast.LENGTH_LONG).show();
          EasyTierNative.stopNetwork(id);
          stopService(new Intent(EasyTierActivity.this, EasyTierVpnService.class));
          setStopped();
          return;
        }
        runningId = id;
        setRunning();
      }

      @Override
      public void onVpnStop() {
        setStopped();
      }
    });
  }

  @Override
  protected void onDestroy() {
    EasyTierVpnService.setCallback(null);
    super.onDestroy();
  }

  private void prepareAndStart() {
    Intent prepare = VpnService.prepare(this);
    if (prepare != null) {
      startActivityForResult(prepare, REQUEST_PREPARE_VPN);
    } else {
      doStart();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_PREPARE_VPN && resultCode == RESULT_OK) {
      doStart();
    } else if (requestCode == REQUEST_PREPARE_VPN) {
      Toast.makeText(this, "需要 VPN 权限才能组建虚拟网卡", Toast.LENGTH_SHORT).show();
    }
  }

  private void doStart() {
    String toml = buildToml();
    if (toml == null) return;
    pendingToml = toml;

    String ipv4 = binding.etIpv4.getText().toString().trim();
    String subnet = subnetOf(ipv4);

    Intent intent = new Intent(this, EasyTierVpnService.class);
    intent.putExtra(EasyTierVpnService.EXTRA_IPV4, ipv4.isEmpty() ? "10.126.126.2/24" : ipv4);
    if (subnet != null) {
      intent.putExtra(EasyTierVpnService.EXTRA_ROUTES, new String[]{subnet});
    }
    intent.putExtra(EasyTierVpnService.EXTRA_DISALLOWED, new String[]{getPackageName()});
    intent.putExtra(EasyTierVpnService.EXTRA_MTU, 1500);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(intent);
    } else {
      startService(intent);
    }
    binding.tvStatus.setText("正在启动…");
  }

  private void stopVpn() {
    if (runningId != null) {
      EasyTierNative.stopNetwork(runningId);
      runningId = null;
    }
    stopService(new Intent(this, EasyTierVpnService.class));
    setStopped();
  }

  private void setRunning() {
    running = true;
    binding.buttonToggle.setText("停止");
    binding.tvStatus.setText("运行中 (ID: " + (runningId == null ? "?" : runningId.substring(0, 8)) + ")");
  }

  private void setStopped() {
    running = false;
    pendingToml = null;
    binding.buttonToggle.setText("启动");
    binding.tvStatus.setText("已停止");
  }

  /** Build the EasyTier TOML config from the UI. Returns null if required fields are missing. */
  private String buildToml() {
    String name = binding.etNetworkName.getText().toString().trim();
    String secret = binding.etNetworkSecret.getText().toString().trim();
    if (name.isEmpty() || secret.isEmpty()) {
      Toast.makeText(this, "请填写网络名称和密码", Toast.LENGTH_SHORT).show();
      return null;
    }
    String ipv4 = stripMask(binding.etIpv4.getText().toString().trim());
    String peer = binding.etPeer.getText().toString().trim();
    String proxy = binding.etProxy.getText().toString().trim();

    StringBuilder sb = new StringBuilder();
    sb.append("instance_name = \"qzrs-scrcpy\"\n");
    if (!ipv4.isEmpty()) sb.append("ipv4 = \"").append(ipv4).append("\"\n");
    sb.append("\n[network_identity]\n");
    sb.append("network_name = \"").append(escape(name)).append("\"\n");
    sb.append("network_secret = \"").append(escape(secret)).append("\"\n");
    if (!peer.isEmpty()) {
      sb.append("\n[[peer]]\n");
      sb.append("uri = \"").append(escape(peer)).append("\"\n");
    }
    if (!proxy.isEmpty()) {
      sb.append("\nproxy_networks = [\"").append(escape(proxy)).append("\"]\n");
    }
    sb.append("\n[flags]\n");
    sb.append("no_tun = false\n");
    sb.append("dev_name = \"easytier0\"\n");
    return sb.toString();
  }

  private static String stripMask(String cidr) {
    int i = cidr.indexOf('/');
    return i >= 0 ? cidr.substring(0, i) : cidr;
  }

  private static String subnetOf(String cidr) {
    int i = cidr.indexOf('/');
    if (i < 0) return null;
    String ip = cidr.substring(0, i);
    String[] p = ip.split("\\.");
    if (p.length != 4) return null;
    return p[0] + "." + p[1] + "." + p[2] + ".0/" + cidr.substring(i + 1);
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
