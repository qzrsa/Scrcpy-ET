package qzrs.Scrcpy.easytier;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import qzrs.Scrcpy.R;
import qzrs.Scrcpy.databinding.ActivityEasytierBinding;
import qzrs.Scrcpy.entity.AppData;
import qzrs.Scrcpy.helper.ViewTools;

public class EasyTierActivity extends Activity implements EasyTierManager.StatusListener {
  private ActivityEasytierBinding binding;
  private EasyTierManager manager;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    ViewTools.setStatusAndNavBar(this);
    binding = ActivityEasytierBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    manager = EasyTierManager.getInstance();
    manager.setListener(this);

    loadSettings();
    updateStatus(manager.getStatus(), manager.getVpnIp());
    setButtonListener();
  }

  @Override
  protected void onDestroy() {
    manager.setListener(null);
    super.onDestroy();
  }

  private void loadSettings() {
    binding.switchEnable.setChecked(AppData.setting.getEasyTierEnabled());
    binding.editSecret.setText(AppData.setting.getEasyTierSecret());
    binding.editNetworkName.setText(AppData.setting.getEasyTierNetworkName());
    binding.editPort.setText(String.valueOf(AppData.setting.getEasyTierPort()));
    binding.editServer.setText(AppData.setting.getEasyTierServer());
    binding.switchPublic.setChecked(AppData.setting.getEasyTierUsePublic());
    binding.switchDhcp.setChecked(AppData.setting.getEasyTierDhcpEnabled());
    binding.editVirtualIp.setText(AppData.setting.getEasyTierVirtualIp());

    updateConfigArea();
    updateVirtualIpArea();

    binding.switchEnable.setOnCheckedChangeListener((v, checked) -> {
      AppData.setting.setEasyTierEnabled(checked);
      updateConfigArea();
    });

    binding.switchDhcp.setOnCheckedChangeListener((v, checked) -> {
      AppData.setting.setEasyTierDhcpEnabled(checked);
      updateVirtualIpArea();
    });
  }

  private void updateVirtualIpArea() {
    boolean dhcp = binding.switchDhcp.isChecked();
    binding.virtualIpArea.setVisibility(dhcp ? View.GONE : View.VISIBLE);
  }

  private void updateConfigArea() {
    boolean enabled = binding.switchEnable.isChecked();
    binding.configArea.setVisibility(enabled ? View.VISIBLE : View.GONE);
    binding.statusArea.setVisibility(enabled ? View.VISIBLE : View.GONE);
  }

  private void setButtonListener() {
    binding.backButton.setOnClickListener(v -> finish());

    binding.btnSave.setOnClickListener(v -> {
      String secret = binding.editSecret.getText().toString().trim();
      String networkName = binding.editNetworkName.getText().toString().trim();
      String portStr = binding.editPort.getText().toString().trim();
      String server = binding.editServer.getText().toString().trim();
      String virtualIp = binding.editVirtualIp.getText().toString().trim();
      int port = 11010;
      try { port = Integer.parseInt(portStr); } catch (Exception ignored) {}

      AppData.setting.setEasyTierSecret(secret);
      AppData.setting.setEasyTierNetworkName(networkName.isEmpty() ? "scrcpy-et" : networkName);
      AppData.setting.setEasyTierPort(port);
      AppData.setting.setEasyTierServer(server);
      AppData.setting.setEasyTierUsePublic(binding.switchPublic.isChecked());
      AppData.setting.setEasyTierDhcpEnabled(binding.switchDhcp.isChecked());
      AppData.setting.setEasyTierVirtualIp(virtualIp);

      Toast.makeText(this, getString(R.string.toast_success), Toast.LENGTH_SHORT).show();
    });

    binding.btnStart.setOnClickListener(v -> {
      if (binding.editSecret.getText().toString().trim().isEmpty()) {
        Toast.makeText(this, getString(R.string.easytier_need_permission), Toast.LENGTH_SHORT).show();
        return;
      }
      manager.ensureBinaryAndStart();
    });

    binding.btnStop.setOnClickListener(v -> manager.stop());

    // VPN IP 点击复制
    binding.vpnIpText.setOnClickListener(v -> {
      String ip = binding.vpnIpText.getText().toString();
      if (!ip.isEmpty()) {
        AppData.clipBoard.setPrimaryClip(ClipData.newPlainText(ClipDescription.MIMETYPE_TEXT_PLAIN, ip));
        Toast.makeText(this, getString(R.string.easytier_copy_ip), Toast.LENGTH_SHORT).show();
      }
    });

    binding.editSecret.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {}
      public void afterTextChanged(Editable s) {
        AppData.setting.setEasyTierSecret(s.toString());
      }
    });

    binding.editNetworkName.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {}
      public void afterTextChanged(Editable s) {
        AppData.setting.setEasyTierNetworkName(s.toString());
      }
    });

    binding.switchPublic.setOnCheckedChangeListener((v, checked) -> {
      AppData.setting.setEasyTierUsePublic(checked);
    });

    binding.editServer.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {}
      public void afterTextChanged(Editable s) {
        AppData.setting.setEasyTierServer(s.toString());
      }
    });
  }

  @Override
  public void onStatusChanged(int status, String vpnIp) {
    runOnUiThread(() -> updateStatus(status, vpnIp));
  }

  private void updateStatus(int status, String vpnIp) {
    String statusText = EasyTierManager.getStatusText(status);
    binding.statusText.setText(statusText);

    // 状态颜色
    int color;
    switch (status) {
      case EasyTierManager.STATUS_RUNNING:     color = Color.parseColor("#4CAF50"); break;
      case EasyTierManager.STATUS_DOWNLOADING: color = Color.parseColor("#FF9800"); break;
      case EasyTierManager.STATUS_STARTING:   color = Color.parseColor("#2196F3"); break;
      case EasyTierManager.STATUS_ERROR:       color = Color.parseColor("#F44336"); break;
      default:                                 color = Color.parseColor("#9E9E9E"); break;
    }
    GradientDrawable dotDrawable = (GradientDrawable) binding.statusDot.getBackground();
    dotDrawable.setColor(color);

    // VPN IP 显示
    if (status == EasyTierManager.STATUS_RUNNING && !vpnIp.isEmpty()) {
      binding.vpnIpLabel.setVisibility(View.VISIBLE);
      binding.vpnIpText.setVisibility(View.VISIBLE);
      binding.vpnIpText.setText(vpnIp);
    } else {
      binding.vpnIpLabel.setVisibility(View.GONE);
      binding.vpnIpText.setVisibility(View.GONE);
    }

    // 按钮状态
    boolean isRunning = (status == EasyTierManager.STATUS_RUNNING);
    binding.btnStart.setEnabled(!isRunning);
    binding.btnStart.setAlpha(isRunning ? 0.5f : 1f);
    binding.btnStop.setEnabled(isRunning);
    binding.btnStop.setAlpha(isRunning ? 1f : 0.5f);
  }

  @Override
  public void onLog(String line) {
    runOnUiThread(() -> {
      binding.logText.setVisibility(View.VISIBLE);
      String current = binding.logText.getText().toString();
      String[] lines = current.split("\n");
      StringBuilder sb = new StringBuilder();
      for (int i = Math.max(0, lines.length - 10); i < lines.length; i++) {
        sb.append(lines[i]).append("\n");
      }
      sb.append(line);
      binding.logText.setText(sb.toString());
    });
  }
}
