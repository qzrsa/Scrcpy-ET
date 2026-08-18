package qzrs.Scrcpy.client.view;

import android.annotation.SuppressLint;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.os.Build;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import qzrs.Scrcpy.R;
import qzrs.Scrcpy.client.Client;
import qzrs.Scrcpy.client.tools.ClientController;
import qzrs.Scrcpy.client.tools.ControlPacket;
import qzrs.Scrcpy.databinding.ModuleSmallViewBinding;
import qzrs.Scrcpy.entity.AppData;
import qzrs.Scrcpy.entity.Device;
import qzrs.Scrcpy.helper.PublicTools;
import qzrs.Scrcpy.helper.ViewTools;

public class SmallView extends ViewOutlineProvider {
  private final Device device;
  private ClientController clientController;
  private boolean isShow = false;
  private boolean light = true;

  private final ModuleSmallViewBinding smallView = ModuleSmallViewBinding.inflate(LayoutInflater.from(AppData.applicationContext));
  private final WindowManager.LayoutParams smallViewParams =
    new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
      LayoutParamsFlagFocus,
      PixelFormat.TRANSLUCENT
    );

  private static final int LayoutParamsFlagFocus = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
  private static final int LayoutParamsFlagNoFocus = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

  public SmallView(String uuid) {
    device = Client.getDevice(uuid);
    clientController = Client.getClientController(uuid);
    if (device == null || clientController == null) return;
    smallViewParams.gravity = Gravity.START | Gravity.TOP;
    smallView.navBar.setVisibility(View.VISIBLE);
    setFloatVideoListener();
    setReSizeListener();
    setBarListener();
    setButtonListener();
    setKeyEvent();
    smallView.body.setOutlineProvider(this);
    smallView.body.setClipToOutline(true);
  }

  public void show() {
    if (device == null || clientController == null) return;
    smallView.barView.setVisibility(View.GONE);
    smallViewParams.x = device.smallX;
    smallViewParams.y = device.smallY;
    updateMaxSize();
    if (!Objects.equals(device.startApp, "")) {
      smallView.buttonHome.setVisibility(View.GONE);
      smallView.buttonSwitch.setVisibility(View.GONE);
      smallView.buttonApp.setVisibility(View.GONE);
    }
    if (!device.customResolutionOnConnect && device.changeResolutionOnRunning) clientController.handleAction("writeByteBuffer", ControlPacket.createChangeResolutionEvent(0.5f), 0);
    AppData.windowManager.addView(smallView.getRoot(), smallViewParams);
    smallView.textureViewLayout.addView(clientController.getTextureView(), 0);
    ViewTools.viewAnim(smallView.getRoot(), true, 0, PublicTools.dp2px(40f), null);
    isShow = true;
  }

  public void hide() {
    if (device == null || clientController == null) return;
    try {
      smallView.textureViewLayout.removeView(clientController.getTextureView());
      AppData.windowManager.removeView(smallView.getRoot());
      isShow = false;
    } catch (Exception ignored) {
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private void setFloatVideoListener() {
    smallView.getRoot().setOnTouchHandle(event -> {
      if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
        if (device.smallToMiniOnRunning) clientController.handleAction("changeToMini", ByteBuffer.wrap("changeToSmall".getBytes()), 0);
        else if (smallViewParams.flags != LayoutParamsFlagNoFocus) {
          smallView.editText.clearFocus();
          smallViewParams.flags = LayoutParamsFlagNoFocus;
          AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
        }
      } else if (smallViewParams.flags != LayoutParamsFlagFocus) {
        smallViewParams.flags = LayoutParamsFlagFocus;
        AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
        smallView.editText.requestFocus();
      }
    });
  }

  @SuppressLint("ClickableViewAccessibility")
  private void setBarListener() {
    AtomicBoolean isFilp = new AtomicBoolean(false);
    AtomicInteger xx = new AtomicInteger();
    AtomicInteger yy = new AtomicInteger();
    AtomicInteger paramsX = new AtomicInteger();
    AtomicInteger paramsY = new AtomicInteger();
    smallView.bar.setOnTouchListener((v, event) -> {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: {
          xx.set((int) event.getRawX());
          yy.set((int) event.getRawY());
          paramsX.set(smallViewParams.x);
          paramsY.set(smallViewParams.y);
          isFilp.set(false);
          break;
        }
        case MotionEvent.ACTION_MOVE: {
          int x = (int) event.getRawX();
          int y = (int) event.getRawY();
          int flipX = x - xx.get();
          int flipY = y - yy.get();
          if (!isFilp.get()) {
            if (flipX * flipX + flipY * flipY < 16) return true;
            isFilp.set(true);
          }
          if (y < statusBarHeight + 10) return true;
          updateSite(paramsX.get() + flipX, paramsY.get() + flipY);
          break;
        }
        case MotionEvent.ACTION_UP:
          if (!isFilp.get()) changeBarView();
          break;
      }
      return true;
    });
  }

  private void setButtonListener() {
    smallView.buttonBack.setOnClickListener(v -> clientController.handleAction("buttonBack", null, 0));
    smallView.buttonHome.setOnClickListener(v -> clientController.handleAction("buttonHome", null, 0));
    smallView.buttonSwitch.setOnClickListener(v -> clientController.handleAction("buttonSwitch", null, 0));
    smallView.buttonApp.setOnClickListener(v -> {
      clientController.handleAction("changeToApp", null, 0);
      changeBarView();
    });
    smallView.buttonMini.setOnClickListener(v -> clientController.handleAction("changeToMini", null, 0));
    smallView.buttonFull.setOnClickListener(v -> clientController.handleAction("changeToFull", null, 0));
    smallView.buttonClose.setOnClickListener(v -> Client.sendAction(device.uuid, "close", null, 0));
    smallView.buttonRotate.setOnClickListener(v -> {
      clientController.handleAction("buttonRotate", null, 0);
      changeBarView();
    });
    smallView.buttonNavBar.setOnClickListener(v -> {
      boolean nowVisible = smallView.navBar.getVisibility() == View.VISIBLE;
      smallView.navBar.setVisibility(nowVisible ? View.GONE : View.VISIBLE);
      smallView.buttonNavBar.setImageResource(nowVisible ? R.drawable.equals : R.drawable.not_equal);
      changeBarView();
    });
    smallView.buttonPower.setOnClickListener(v -> {
      clientController.handleAction("buttonPower", null, 0);
      changeBarView();
    });
    smallView.buttonLight.setOnClickListener(v -> {
      light = !light;
      smallView.buttonLight.setImageResource(light ? R.drawable.lightbulb_off : R.drawable.lightbulb);
      clientController.handleAction(light ? "buttonLight" : "buttonLightOff", null, 0);
      changeBarView();
    });
  }

  private void changeBarView() {
    boolean toShowView = smallView.barView.getVisibility() == View.GONE;
    ViewTools.viewAnim(smallView.barView, toShowView, 0, PublicTools.dp2px(-40f), (isStart -> {
      if (isStart && toShowView) smallView.barView.setVisibility(View.VISIBLE);
      else if (!isStart && !toShowView) smallView.barView.setVisibility(View.GONE);
    }));
  }

  @SuppressLint("ClickableViewAccessibility")
  private void setReSizeListener() {
    smallView.reSize.setOnTouchListener((v, event) -> {
      int sizeX = (int) (event.getRawX() - smallViewParams.x);
      int sizeY = (int) (event.getRawY() - smallViewParams.y);
      int length = Math.max(sizeX, sizeY);
      ViewGroup.LayoutParams textureViewLayoutParams = clientController.getTextureView().getLayoutParams();
      if (textureViewLayoutParams.width < textureViewLayoutParams.height) device.smallLength = length;
      else device.smallLengthLan = length;
      updateMaxSize();
      return true;
    });
  }

  private void updateSite(int x, int y) {
    ByteBuffer byteBuffer = ByteBuffer.allocate(8);
    byteBuffer.putInt(x);
    byteBuffer.putInt(y);
    byteBuffer.flip();
    clientController.handleAction("updateSite", byteBuffer, 0);
  }

  public void updateView(int x, int y) {
    smallViewParams.x = x;
    smallViewParams.y = y;
    AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
  }

  private void updateMaxSize() {
    ByteBuffer byteBuffer = ByteBuffer.allocate(8);
    byteBuffer.putInt(device.smallLength);
    byteBuffer.putInt(device.smallLengthLan);
    byteBuffer.flip();
    clientController.handleAction("updateMaxSize", byteBuffer, 0);
  }

  public boolean isShow() {
    return isShow;
  }

  private void setKeyEvent() {
    smallView.editText.setInputType(InputType.TYPE_NULL);
    smallView.editText.setOnKeyListener((v, keyCode, event) -> {
      if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
        clientController.handleAction("writeByteBuffer", ControlPacket.createKeyEvent(event.getKeyCode(), event.getMetaState()), 0);
        return true;
      }
      return false;
    });
  }

  public void checkSizeAndSite() {
    if (!isShow) return;
    DisplayMetrics screenSize = PublicTools.getScreenSize();
    int screenMaxWidth = screenSize.widthPixels - 50;
    int screenMaxHeight = screenSize.heightPixels - statusBarHeight - 50;
    ViewGroup.LayoutParams textureViewLayoutParams = clientController.getTextureView().getLayoutParams();
    int width = textureViewLayoutParams.width;
    int height = textureViewLayoutParams.height;
    int startX = smallViewParams.x;
    int startY = smallViewParams.y;
    if (width > screenMaxWidth + 200 || height > screenMaxHeight + 200) {
      int maxLength = Math.min(screenMaxWidth, screenMaxHeight);
      if (width < height) device.smallLength = maxLength;
      else device.smallLengthLan = maxLength;
      updateMaxSize();
      updateSite(0, statusBarHeight);
      return;
    }
    int halfWidth = (int) (width * 0.5);
    if (startX < -1 * halfWidth) updateSite(-1 * halfWidth + 50, startY);
    if (startX > screenSize.widthPixels - halfWidth) updateSite(screenSize.widthPixels - halfWidth - 50, startY);
    if (startY < statusBarHeight / 2) updateSite(startX, statusBarHeight);
    if (startY > screenSize.heightPixels - 100) updateSite(startX, screenSize.heightPixels - 200);
  }

  @Override
  public void getOutline(View view, Outline outline) {
    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AppData.applicationContext.getResources().getDimension(R.dimen.cron));
  }

  private static int statusBarHeight = 0;

  static {
    @SuppressLint("InternalInsetResource") int resourceId = AppData.applicationContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
    if (resourceId > 0) {
      statusBarHeight = AppData.applicationContext.getResources().getDimensionPixelSize(resourceId);
    }
  }

}
