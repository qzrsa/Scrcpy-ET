package qzrs.Scrcpy.entity;

import android.content.SharedPreferences;

import java.util.UUID;

public final class Setting {
  private final SharedPreferences sharedPreferences;

  private final SharedPreferences.Editor editor;

  // EasyTier 配置
  public boolean getEasyTierEnabled() {
    return sharedPreferences.getBoolean("easytier_enabled", false);
  }

  public void setEasyTierEnabled(boolean value) {
    editor.putBoolean("easytier_enabled", value);
    editor.apply();
  }

  public String getEasyTierSecret() {
    return sharedPreferences.getString("easytier_secret", "");
  }

  public void setEasyTierSecret(String value) {
    editor.putString("easytier_secret", value);
    editor.apply();
  }

  public String getEasyTierNetworkName() {
    return sharedPreferences.getString("easytier_network_name", "scrcpy-et");
  }

  public void setEasyTierNetworkName(String value) {
    editor.putString("easytier_network_name", value);
    editor.apply();
  }

  public int getEasyTierPort() {
    return sharedPreferences.getInt("easytier_port", 11010);
  }

  public void setEasyTierPort(int value) {
    editor.putInt("easytier_port", value);
    editor.apply();
  }

  public boolean getEasyTierUsePublic() {
    return sharedPreferences.getBoolean("easytier_use_public", true);
  }

  public void setEasyTierUsePublic(boolean value) {
    editor.putBoolean("easytier_use_public", value);
    editor.apply();
  }

  public String getLocale() {
    return sharedPreferences.getString("locale", "");
  }

  public void setLocale(String value) {
    editor.putString("locale", value);
    editor.apply();
  }

  public boolean getAutoRotate() {
    return sharedPreferences.getBoolean("autoRotate", true);
  }

  public void setAutoRotate(boolean value) {
    editor.putBoolean("autoRotate", value);
    editor.apply();
  }

  public String getLocalUUID() {
    if (!sharedPreferences.contains("UUID")) {
      editor.putString("UUID", UUID.randomUUID().toString());
      editor.apply();
    }
    return sharedPreferences.getString("UUID", "");
  }

  public Setting(SharedPreferences sharedPreferences) {
    this.sharedPreferences = sharedPreferences;
    this.editor = sharedPreferences.edit();
  }
}
