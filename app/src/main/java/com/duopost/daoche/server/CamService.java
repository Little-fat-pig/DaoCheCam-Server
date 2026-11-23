package com.duopost.daoche.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.IBinder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;

/**
 * 摄像头推流服务（原创实现）：
 * Camera1 采集 NV21 → 按需旋转 → JPEG → HTTP 提供两个接口：
 *   /video    multipart MJPEG 实时流
 *   /shot.jpg 单帧 JPEG（供网页端自动发现探测）
 */
public class CamService extends Service {

    public static volatile boolean running = false;
    public static volatile String lastUrl = null;
    public static volatile CamService instance = null; // Activity 绑定预览画面用

    private static final int PORT = 8080;

    private Camera camera;
    private SurfaceTexture fakeTexture;
    private android.view.SurfaceHolder previewHolder; // Activity 传来的全屏预览画面（有就用它）
    private int previewW = 640, previewH = 480;
    private int rotationDeg = 0; // 0/90/180/270

    private final Object frameLock = new Object();
    private byte[] latestFrame; // 最新 JPEG
    private long frameVersion = 0;
    private byte[] pendingFrame; // 待压缩的最新 NV21（编码线程专用，旧的直接丢）

    private Thread serverThread;
    private ServerSocket serverSocket;
    private Thread encThread; // 独立压缩线程：绝不在相机回调里做重活（老手机会堵死相机→黑屏卡顿）

    private boolean needPeriodicAf = false;      // 只支持单次对焦的机型需要定时触发
    private android.os.Handler afTimer;
    private Runnable afRunnable;

    /** Activity 全屏预览画面就绪：把相机预览切到 Surface 上（清晰原生显示） */
    public static void attachSurface(android.view.SurfaceHolder holder) {
        CamService svc = instance;
        if (svc != null) svc.attachSurfaceInternal(holder);
    }

    /** Activity 预览画面销毁：回退到无 UI 推流模式 */
    public static void detachSurface() {
        CamService svc = instance;
        if (svc != null) svc.detachSurfaceInternal();
    }

    private synchronized void attachSurfaceInternal(android.view.SurfaceHolder holder) {
        previewHolder = holder;
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay(holder);
                camera.setDisplayOrientation(calcDisplayOrientation());
                camera.startPreview();
            } catch (Exception ignored) {
            }
        }
    }

    private synchronized void detachSurfaceInternal() {
        previewHolder = null;
        if (camera != null) {
            try {
                camera.stopPreview();
                if (fakeTexture == null) fakeTexture = new SurfaceTexture(0);
                camera.setPreviewTexture(fakeTexture);
                camera.startPreview();
            } catch (Exception ignored) {
            }
        }
    }

    /** 按屏幕方向校正预览显示方向（只影响 Surface 预览，不影响推流帧数据） */
    private int calcDisplayOrientation() {
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(MainActivity.camIndex, info);
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            int rotation = wm.getDefaultDisplay().getRotation();
            int degrees = rotation == android.view.Surface.ROTATION_0 ? 0
                    : rotation == android.view.Surface.ROTATION_90 ? 90
                    : rotation == android.view.Surface.ROTATION_180 ? 180 : 270;
            int result;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (info.orientation + degrees) % 360;
                result = (360 - result) % 360;
            } else {
                result = (info.orientation - degrees + 360) % 360;
            }
            return result;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        startForegroundInternal();
        running = true;
        openCamera();
        startServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        running = false;
        stopServer();
        releaseCamera();
        MainActivity.onShareStoppedStatic();
        super.onDestroy();
    }

    // ---------------- 摄像头 ----------------

    private void openCamera() {
        try {
            camera = Camera.open(MainActivity.camIndex);
            Camera.Parameters p = camera.getParameters();
            p.setPreviewFormat(ImageFormat.NV21);

            // 选最接近期望的预览尺寸
            Camera.Size best = pickSize(p.getSupportedPreviewSizes(), MainActivity.prefWidth, MainActivity.prefHeight);
            previewW = best.width;
            previewH = best.height;
            p.setPreviewSize(previewW, previewH);

            int maxFps = 0;
            for (int[] fps : p.getSupportedPreviewFpsRange()) {
                if (fps[1] >= 15000 && fps[1] <= 30000 && fps[1] > maxFps) {
                    maxFps = fps[1];
                    p.setPreviewFpsRange(fps[0], fps[1]);
                }
            }

            // 对焦：照抄原相机 App 的策略——
            // ① 支持连续对焦就用连续对焦（画面自动保持清晰）
            // ② 老机器只有单次对焦(auto/macro)的，启动后每 2 秒自动触发一次对焦
            List<String> modes = p.getSupportedFocusModes();
            needPeriodicAf = false;
            if (modes != null) {
                if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                } else if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    needPeriodicAf = true; // 只能单次对焦 → 定时触发
                } else if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY); // 纯定焦头：锁无穷远
                }
            }
            // 白平衡/场景自动，改善偏色和过暗
            List<String> wb = p.getSupportedWhiteBalance();
            if (wb != null && wb.contains(Camera.Parameters.WHITE_BALANCE_AUTO)) {
                p.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
            }
            camera.setParameters(p);

            // 有 Activity 全屏画面就用 Surface（原生清晰显示），否则无 UI 推流
            if (previewHolder != null) {
                camera.setPreviewDisplay(previewHolder);
            } else {
                fakeTexture = new SurfaceTexture(0);
                camera.setPreviewTexture(fakeTexture);
            }
            camera.setDisplayOrientation(calcDisplayOrientation());

            rotationDeg = MainActivity.rotationDeg;

            camera.setPreviewCallbackWithBuffer((data, cam) -> {
                // 只拷贝 + 立刻还缓冲；压缩在独立线程做，永远压最新帧，压不完的自动丢
                byte[] copy = new byte[data.length];
                System.arraycopy(data, 0, copy, 0, data.length);
                cam.addCallbackBuffer(data);
                synchronized (frameLock) {
                    pendingFrame = copy;
                    frameLock.notifyAll();
                }
            });
            int bufSize = previewW * previewH * 3 / 2;
            for (int i = 0; i < 4; i++) camera.addCallbackBuffer(new byte[bufSize]);

            encThread = new Thread(() -> {
                long lastSend = 0;
                while (running) {
                    byte[] nv21;
                    synchronized (frameLock) {
                        while (pendingFrame == null && running) {
                            try {
                                frameLock.wait(300);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        nv21 = pendingFrame;
                        pendingFrame = null;
                    }
                    if (nv21 == null) continue;
                    long now = System.currentTimeMillis();
                    if (now - lastSend < 30) continue; // 上限 30fps
                    lastSend = now;
                    byte[] jpeg = nv21ToJpeg(nv21, previewW, previewH, rotationDeg, MainActivity.jpegQuality);
                    if (jpeg == null) continue;
                    synchronized (frameLock) {
                        latestFrame = jpeg;
                        frameVersion++;
                        frameLock.notifyAll();
                    }
                }
            }, "daochecam-enc");
            encThread.start();

            camera.startPreview();

            // 单次对焦机型：定时触发对焦（原相机 App 同款做法），对焦中不影响取景
            if (needPeriodicAf) {
                afTimer = new android.os.Handler(android.os.Looper.getMainLooper());
                afRunnable = new Runnable() {
                    @Override
                    public void run() {
                        Camera c = camera;
                        if (c == null || !running) return;
                        try {
                            c.autoFocus(new Camera.AutoFocusCallback() {
                                @Override
                                public void onAutoFocus(boolean ok, Camera cam) {
                                    // 只触发对焦，不需要回调动作
                                }
                            });
                        } catch (Exception ignored) {
                        }
                        afTimer.postDelayed(this, 2000);
                    }
                };
                afTimer.postDelayed(afRunnable, 1500);
            }
        } catch (Throwable e) {
            camera = null;
            String msg = (e.getMessage() == null || e.getMessage().isEmpty())
                    ? e.getClass().getSimpleName() : e.getMessage();
            MainActivity.onError("摄像头打开失败：" + msg);
        }
    }

    private void releaseCamera() {
        if (afTimer != null && afRunnable != null) {
            afTimer.removeCallbacks(afRunnable);
        }
        if (encThread != null) encThread.interrupt();
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.setPreviewCallbackWithBuffer(null);
                camera.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (fakeTexture != null) fakeTexture.release();
        } catch (Exception ignored) {
        }
        camera = null;
    }

    private Camera.Size pickSize(List<Camera.Size> sizes, int wantW, int wantH) {
        Camera.Size best = sizes.get(0);
        long bestDiff = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long diff = Math.abs((long) s.width * s.height - (long) wantW * wantH);
            if (diff < bestDiff) {
                best = s;
                bestDiff = diff;
            }
        }
        return best;
    }

    // ---------------- NV21 → JPEG（含旋转） ----------------

    private static byte[] nv21ToJpeg(byte[] nv21, int w, int h, int deg, int quality) {
        try {
            if (deg != 0) nv21 = rotateNv21(nv21, w, h, deg);
            if (deg == 90 || deg == 270) {
                int t = w;
                w = h;
                h = t;
            }
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), quality, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** NV21 顺时针旋转 deg 度（Y 平面 + VU 交织平面都要转） */
    private static byte[] rotateNv21(byte[] data, int w, int h, int deg) {
        byte[] out = new byte[data.length];
        int ySize = w * h;
        int uvW = w / 2;

        if (deg == 180) {
            for (int i = 0; i < ySize; i++) out[i] = data[ySize - 1 - i];
            int uvCount = ySize / 2;
            for (int j = 0; j < uvCount; j += 2) {
                int src = ySize + uvCount - 2 - j;
                out[ySize + j] = data[src];
                out[ySize + j + 1] = data[src + 1];
            }
        } else if (deg == 90) { // 顺时针 90
            int i = 0;
            for (int x = 0; x < w; x++)
                for (int y = h - 1; y >= 0; y--)
                    out[i++] = data[y * w + x];
            int uvH = h / 2;
            for (int x = 0; x < uvW; x++)
                for (int y = uvH - 1; y >= 0; y--) {
                    int src = ySize + (y * uvW + x) * 2;
                    out[i++] = data[src];     // V
                    out[i++] = data[src + 1]; // U
                }
        } else if (deg == 270) { // 顺时针 270 = 逆时针 90
            int i = 0;
            for (int x = w - 1; x >= 0; x--)
                for (int y = 0; y < h; y++)
                    out[i++] = data[y * w + x];
            int uvH = h / 2;
            for (int x = uvW - 1; x >= 0; x--)
                for (int y = 0; y < uvH; y++) {
                    int src = ySize + (y * uvW + x) * 2;
                    out[i++] = data[src];
                    out[i++] = data[src + 1];
                }
        } else {
            return data;
        }
        return out;
    }

    // ---------------- HTTP ----------------

    private void startForegroundInternal() {
        String id = "daochecam_channel";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(id, "摄像头服务", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, id)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("摄像头服务运行中")
                .setContentText(lastUrl == null ? "正在启动…" : lastUrl)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT, 64);
            } catch (Exception e) {
                serverSocket = null;
                for (int p = PORT + 1; p <= PORT + 5; p++) {
                    try {
                        serverSocket = new ServerSocket(p, 64);
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }
            if (serverSocket == null) {
                postUrl(null);
                return;
            }            postUrl("http://" + getLanIp() + ":" + serverSocket.getLocalPort());
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket s = serverSocket.accept();
                    new Thread(() -> handle(s)).start();
                } catch (Exception e) {
                    if (!running) break;
                }
            }
        }, "daochecam-http");
        serverThread.start();
    }

    private void stopServer() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
    }

    private void postUrl(String url) {
        lastUrl = url;
        MainActivity.onUrlReady(url);
    }

    /** 枚举网卡拿局域网 IPv4：优先 WiFi/热点接口，排除蜂窝网络，避免二维码拿到打不开的地址 */
    private String getLanIp() {
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            String best = null;      // wlan 类
            String siteLocal = null; // 其他私网
            String any = null;       // 兜底
            for (NetworkInterface ni : ifs) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase();
                boolean isWifi = n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap")
                        || n.startsWith("eth") || n.startsWith("usb");
                boolean isCellular = n.startsWith("rmnet") || n.startsWith("ccmni")
                        || n.startsWith("pdp") || n.startsWith("seth");
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    String host = addr.getHostAddress();
                    if (host.contains(":") || addr.isLoopbackAddress()) continue;
                    if (isCellular) continue; // 蜂窝网络的 IP 局域网打不开，跳过
                    if (isWifi) { if (best == null) best = host; }
                    else if (addr.isSiteLocalAddress()) { if (siteLocal == null) siteLocal = host; }
                    else if (any == null) any = host;
                }
            }
            if (best != null) return best;
            if (siteLocal != null) return siteLocal;
            return any;
        } catch (Exception ignored) {
        }
        return null;
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            byte[] buf = new byte[4096];
            InputStream in = sock.getInputStream();
            int n = in.read(buf);
            if (n <= 0) return;
            String req = new String(buf, 0, n, "ISO-8859-1");
            String path = req.split("\r?\n")[0].split(" ")[1];

            if (path.startsWith("/video")) serveStream(sock);
            else if (path.startsWith("/shot")) serveShot(sock);
            else serveIndex(sock);
        } catch (Exception ignored) {
        }
    }

    /** 首页 */
    private void serveIndex(Socket sock) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>DaoCheCam</title></head>"
                + "<body style='font-family:sans-serif;text-align:center'><h2>DaoCheCam 运行中</h2>"
                + "<p><a href='/video'>实时画面 /video</a></p>"
                + "<p><a href='/shot.jpg'>快照 /shot.jpg</a></p></body></html>";
        byte[] body = html.getBytes("UTF-8");
        OutputStream out = sock.getOutputStream();
        PrintWriter w = new PrintWriter(new java.io.OutputStreamWriter(out, "ISO-8859-1"));
        w.print("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n");
        w.print("Access-Control-Allow-Origin: *\r\n");
        w.print("Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n");
        w.flush();
        out.write(body);
        out.flush();
    }

    /** 单帧快照 */
    private void serveShot(Socket sock) throws IOException {
        byte[] frame = waitForFrame(5000);
        OutputStream out = sock.getOutputStream();
        PrintWriter w = new PrintWriter(new java.io.OutputStreamWriter(out, "ISO-8859-1"));
        if (frame == null) {
            w.print("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            w.flush();
            return;
        }
        w.print("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\n");
        w.print("Access-Control-Allow-Origin: *\r\n");
        w.print("Content-Length: " + frame.length + "\r\n");
        w.print("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        w.flush();
        out.write(frame);
        out.flush();
    }

    /** MJPEG 实时流 */
    private void serveStream(Socket sock) throws IOException {
        OutputStream out = sock.getOutputStream();
        PrintWriter w = new PrintWriter(new java.io.OutputStreamWriter(out, "ISO-8859-1"));
        w.print("HTTP/1.1 200 OK\r\n");
        w.print("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n");
        w.print("Access-Control-Allow-Origin: *\r\n");
        w.print("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        w.flush();

        long last = 0;
        long lastSend = 0;
        while (running) {
            byte[] frame;
            long v;
            synchronized (frameLock) {
                while (frameVersion <= last && running) {
                    try {
                        frameLock.wait(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!running) break;
                frame = latestFrame;
                v = frameVersion;
            }
            if (frame == null) continue;
            last = v;
            // 上限 30fps（33ms 一帧），够流畅又不浪费带宽和 CPU
            long now = System.currentTimeMillis();
            if (now - lastSend < 33) continue;
            lastSend = now;

            w.print("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n");
            w.flush();
            try {
                out.write(frame);
                out.flush();
            } catch (Exception e) {
                return; // 客户端断开
            }
        }
    }

    private byte[] waitForFrame(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (latestFrame == null && System.currentTimeMillis() < deadline) {
                try {
                    frameLock.wait(200);
                } catch (InterruptedException e) {
                    break;
                }
            }
            return latestFrame;
        }
    }
}
