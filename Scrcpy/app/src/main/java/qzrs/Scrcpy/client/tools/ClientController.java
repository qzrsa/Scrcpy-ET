package qzrs.Scrcpy.client.tools;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Pair;
import android.view.Display;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import qzrs.Scrcpy.R;
import qzrs.Scrcpy.client.Client;
import qzrs.Scrcpy.client.view.FullActivity;
import qzrs.Scrcpy.client.view.MiniView;
import qzrs.Scrcpy.client.view.SmallView;
import qzrs.Scrcpy.entity.AppData;
import qzrs.Scrcpy.entity.Device;
import qzrs.Scrcpy.entity.MyInterface;
import qzrs.Scrcpy.helper.PublicTools;

public class ClientController implements TextureView.SurfaceTextureListener {
  private final Device device;
  private final ClientStream clientStream;
  private final MyInterface.MyFunction handle;
  private final TextureView textureView = new TextureView(AppData.applicationContext);
  private SurfaceTexture surfaceTexture;

  private SmallView smallView;
  private MiniView miniView;
  private FullActivity fullView;

  private Pair<Integer, Integer> videoSize;
  private Pair<Integer, Integer> maxSize;
  private Pair<Integer, Integer> surfaceSize;

  private final HandlerThread mainThread = new HandlerThread("scrcpy_client_main");
  private Handler mainHandler;

  public ClientController(Device device, ClientStream clientStream, MyInterface.MyFunction handle) {
    this.device = device;
    this.clientStream = clientStream;
    this.handle = handle;
    mainThread.start();
    mainHandler = new Handler(mainThread.getLooper());
    textureView.setSurfaceTextureListener(this);
    setTouchListener();
    mainHandler.post(this::otherService);
  }

  public void handleAction(String action, ByteBuffer byteBuffer, int delay) {
    if (delay == 0) mainHandler.post(() -> handleAction(action, byteBuffer));
    else mainHandler.postDelayed(() -> handleAction(action, byteBuffer), delay);
  }

  private void handleAction(String action, ByteBuffer byteBuffer) {
    try {
      switch (action) {
        case "changeToSmall":
          changeToSmall();
          break;
        case "changeToFull":
          changeToFull();
          break;
        case "changeToMini":
          changeToMini(byteBuffer);
          break;
        case "changeToApp":
          changeToApp();
          break;
        case "buttonPower":
          clientStream.writeToMain(ControlPacket.createPowerEvent(-1));
          break;
        case "buttonWake":
          clientStream.writeToMain(ControlPacket.createPowerEvent(1));
          break;
        case "buttonLock":
          clientStream.writeToMain(ControlPacket.createPowerEvent(0));
          break;
        case "buttonLight":
          clientStream.writeToMain(ControlPacket.createLightEvent(Display.STATE_ON));
          clientStream.writeToMain(ControlPacket.createLightEvent(Display.STATE_OFF));
          break;
        case "buttonLightOff":
          clientStream.writeToMain(ControlPacket.createLightEvent(Display.STATE_UNKNOWN));
          break;
        case "buttonBack":
          clientStream.writeToMain(ControlPacket.createKeyEvent(4, 0));
          break;
        case "buttonHome":
          clientStream.writeToMain(ControlPacket.createKeyEvent(3, 0));
          break;
        case "buttonSwitch":
          clientStream.writeToMain(ControlPacket.createKeyEvent(187, 0));
          break;
        case "buttonRotate":
          clientStream.writeToMain(ControlPacket.createRotateEvent());
          break;
        case "keepAlive":
          // 用 keepAlive 包的发送耗时近似作为链路延迟
          clientStream.writeToMainWithLatency(ControlPacket.createKeepAlive());
          break;
        case "checkSizeAndSite":
          checkSizeAndSite();
          break;
        case "checkClipBoard":
          checkClipBoard();
          break;
        case "updateSite":
          updateSite(byteBuffer);
          break;
        default:
          if (byteBuffer == null) break;
        case "writeByteBuffer":
          clientStream.writeToMain(byteBuffer);
          break;
        case "updateMaxSize":
          updateMaxSize(byteBuffer);
          break;
        case "updateVideoSize":
          updateVideoSize(byteBuffer);
          break;
        case "runShell":
          runShell(byteBuffer);
          break;
        case "setClipBoard":
          setClipBoard(byteBuffer);
          break;
      }
    } catch (Exception ignored) {
      byte[] err = ("controller" + AppData.applicationContext.getString(R.string.toast_stream_closed) + action).getBytes(StandardCharsets.UTF_8);
      Client.sendAction(device.uuid, "close", ByteBuffer.wrap(err), 0);
    }
  }

  private void otherService() {
    handleAction("checkClipBoard", null, 0);
    handleAction("keepAlive", null, 0);
    handleAction("checkSizeAndSite", null, 0);
    mainHandler.postDelayed(this::otherService, 2000);
  }

  public void setFullView(FullActivity fullView) {
    this.fullView = fullView;
  }

  public TextureView getTextureView() {
    return textureView;
  }

  private synchronized void changeToFull() {
    hide();
    if (clientStream != null) clientStream.getStatsOverlay().show();
    Intent intent = new Intent(AppData.mainActivity, FullActivity.class);
    intent.putExtra("uuid", device.uuid);
    AppData.mainActivity.startActivity(intent);
  }

  private synchronized void changeToSmall() {
    hide();
    if (clientStream != null) clientStream.getStatsOverlay().hide();
    if (noFloatPermission()) {
      PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_float_per), true);
      changeToFull();
    } else {
      if (smallView == null) smallView = new SmallView(device.uuid);
      AppData.uiHandler.post(smallView::show);
      updateSite(null);
    }
  }

  private synchronized void changeToMini(ByteBuffer byteBuffer) {
    hide();
    if (clientStream != null) clientStream.getStatsOverlay().hide();
    if (noFloatPermission()) {
      PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_float_per), true);
      changeToFull();
    } else {
      if (miniView == null) miniView = new MiniView(device.uuid);
      AppData.uiHandler.post(() -> miniView.show(byteBuffer));
    }
  }

  private boolean noFloatPermission() {
    try {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(AppData.applicationContext);
    } catch (Exception ignored) {
      return false;
    }
  }

  private synchronized void changeToApp() throws Exception {
    if (noFloatPermission()) {
      PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_float_per), true);
      return;
    }
    String output = clientStream.runShell("dumpsys window | grep mCurrentFocus=Window");
    Matcher matcher = Pattern.compile(" ([a-zA-Z0-9.]+)/").matcher(output);
    if (matcher.find()) {
      Device tempDevice = device.clone(String.valueOf(UUID.randomUUID()));
      tempDevice.name = "----";
      tempDevice.startApp = matcher.group(1);
      tempDevice.smallX += 200;
      tempDevice.smallY += 200;
      tempDevice.smallLength -= 200;
      tempDevice.miniY += 200;
      Client.startDevice(tempDevice);
    }
  }

  private synchronized void hide() {
    if (fullView != null) AppData.uiHandler.post(fullView::hide);
    fullView = null;
    if (smallView != null) AppData.uiHandler.post(smallView::hide);
    if (miniView != null) AppData.uiHandler.post(miniView::hide);
  }

  public void close() {
    hide();
    mainThread.quitSafely();
    if (surfaceTexture != null) surfaceTexture.release();
  }

  private static final int minLength = PublicTools.dp2px(200f);

  private void updateMaxSize(ByteBuffer byteBuffer) {
    int width = Math.max(byteBuffer.getInt(), minLength);
    int height = Math.max(byteBuffer.getInt(), minLength);
    this.maxSize = new Pair<>(width, height);
    AppData.uiHandler.post(this::reCalculateTextureViewSize);
  }

  private void updateVideoSize(ByteBuffer byteBuffer) {
    int width = byteBuffer.getInt();
    int height = byteBuffer.getInt();
    if (width <= 100 || height <= 100) return;
    this.videoSize = new Pair<>(width, height);
    updateSite(null);
    AppData.uiHandler.post(this::reCalculateTextureViewSize);
  }

  private void updateSite(ByteBuffer byteBuffer) {
    if (smallView == null || videoSize == null || !smallView.isShow()) return;
    int x;
    int y;
    boolean isAuto = byteBuffer == null;
    if (videoSize.first < videoSize.second) {
      x = isAuto ? device.smallX : byteBuffer.getInt();
      y = isAuto ? device.smallY : byteBuffer.getInt();
      device.smallX = x;
      device.smallY = y;
    } else {
      x = isAuto ? device.smallXLan : byteBuffer.getInt();
      y = isAuto ? device.smallYLan : byteBuffer.getInt();
      device.smallXLan = x;
      device.smallYLan = y;
    }
    AppData.uiHandler.post(() -> smallView.updateView(x, y));
  }

  private void reCalculateTextureViewSize() {
    if (maxSize == null || videoSize == null) return;
    Pair<Integer, Integer> maxSize = this.maxSize;
    if (smallView != null && smallView.isShow()) {
      if (videoSize.first < videoSize.second) maxSize = new Pair<>(this.maxSize.first, this.maxSize.first);
      else maxSize = new Pair<>(this.maxSize.second, this.maxSize.second);
    }
    int tmp1 = videoSize.second * maxSize.first / videoSize.first;
    if (maxSize.second > tmp1) surfaceSize = new Pair<>(maxSize.first, tmp1);
    else surfaceSize = new Pair<>(videoSize.first * maxSize.second / videoSize.second, maxSize.second);
    ViewGroup.LayoutParams layoutParams = textureView.getLayoutParams();
    layoutParams.width = surfaceSize.first;
    layoutParams.height = surfaceSize.second;
    textureView.setLayoutParams(layoutParams);
  }

  private void checkSizeAndSite() {
    if (smallView != null) AppData.uiHandler.post(smallView::checkSizeAndSite);
  }

  @SuppressLint("ClickableViewAccessibility")
  private void setTouchListener() {
    textureView.setOnTouchListener((view, event) -> {
      if (surfaceSize == null) return true;
      int action = event.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
        int i = event.getActionIndex();
        pointerDownTime[i] = event.getEventTime();
        createTouchPacket(event, MotionEvent.ACTION_DOWN, i);
      } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) createTouchPacket(event, MotionEvent.ACTION_UP, event.getActionIndex());
      else for (int i = 0; i < event.getPointerCount(); i++) createTouchPacket(event, MotionEvent.ACTION_MOVE, i);
      return true;
    });
  }

  private final int[] pointerList = new int[20];
  private final long[] pointerDownTime = new long[10];

  private void createTouchPacket(MotionEvent event, int action, int i) {
    int offsetTime = (int) (event.getEventTime() - pointerDownTime[i]);
    int x = (int) event.getX(i);
    int y = (int) event.getY(i);
    int p = event.getPointerId(i);
    if (action == MotionEvent.ACTION_MOVE) {
      int flipY = pointerList[10 + p] - y;
      if (flipY > -4 && flipY < 4) {
        int flipX = pointerList[p] - x;
        if (flipX > -4 && flipX < 4) return;
      }
    }
    pointerList[p] = x;
    pointerList[10 + p] = y;
    handleAction("writeByteBuffer", ControlPacket.createTouchEvent(action, p, (float) x / surfaceSize.first, (float) y / surfaceSize.second, offsetTime), 0);
  }

  private String nowClipboardText = "";

  private void checkClipBoard() {
    if (!device.listenClip) return;
    ClipData clipBoard = AppData.clipBoard.getPrimaryClip();
    if (clipBoard != null && clipBoard.getItemCount() > 0) {
      String newClipBoardText = String.valueOf(clipBoard.getItemAt(0).getText());
      if (!Objects.equals(nowClipboardText, newClipBoardText)) {
        nowClipboardText = newClipBoardText;
        handleAction("writeByteBuffer", ControlPacket.createClipboardEvent(nowClipboardText), 0);
      }
    }
  }

  private void setClipBoard(ByteBuffer byteBuffer) {
    nowClipboardText = new String(byteBuffer.array());
    AppData.clipBoard.setPrimaryClip(ClipData.newPlainText(MIMETYPE_TEXT_PLAIN, nowClipboardText));
  }

  private void runShell(ByteBuffer byteBuffer) throws Exception {
    String cmd = new String(byteBuffer.array());
    clientStream.runShell(cmd);
  }

  @Override
  public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
    if (this.surfaceTexture == null) {
      this.surfaceTexture = surfaceTexture;
      handle.run();
    } else textureView.setSurfaceTexture(this.surfaceTexture);
  }

  @Override
  public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
  }

  @Override
  public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
    return false;
  }

  @Override
  public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
  }

}
