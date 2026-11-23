package com.duopost.daoche.server;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 倒车影像主界面（横屏锁定）。
 * 开始前：设置面板。开始后：全屏沉浸显示摄像头画面（SurfaceView 原生预览），
 * 顶部半透明条显示观看地址——点一下即复制，右上角停止按钮。
 */
public class MainActivity extends Activity {

    // 由 UI 写入、Service 启动时读取
    static int camIndex = 0;        // 0=后置 1=前置
    static int prefWidth = 1280, prefHeight = 720;
    static int rotationDeg = 0;     // 0/90/180/270 顺时针
    static int jpegQuality = 80;

    private static final int REQ_CAM = 200;
    private static String urlToDisplay = null;
    private static MainActivity instance = null;

    private TextView tvState, tvUrl;
    private Button btnStart;
    private LinearLayout panelSettings;
    private android.view.View scrollSettings;
    private SurfaceView surfaceView;
    private LinearLayout overlay;       // 开始后顶部覆盖条
    private TextView tvCopyUrl;         // 点击复制
    private Button btnStop;
    private boolean surfaceReady = false;

    private Spinner spCam, spRes, spRot, spQ;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        prefs = getSharedPreferences("daochecam", MODE_PRIVATE);
        camIndex = prefs.getInt("cam", 0);
        rotationDeg = prefs.getInt("rot", 0);
        jpegQuality = prefs.getInt("q", 80);
        int resIdx = prefs.getInt("res", 1);

        tvState = findViewById(R.id.tv_state);
        tvUrl = findViewById(R.id.tv_url);
        btnStart = findViewById(R.id.btn_start);
        panelSettings = findViewById(R.id.panel_settings);
        scrollSettings = findViewById(R.id.scroll_settings);
        surfaceView = findViewById(R.id.surface_view);
        overlay = findViewById(R.id.overlay);
        tvCopyUrl = findViewById(R.id.tv_copy_url);
        btnStop = findViewById(R.id.btn_stop);
        spCam = findViewById(R.id.sp_cam);
        spRes = findViewById(R.id.sp_res);
        spRot = findViewById(R.id.sp_rot);
        spQ = findViewById(R.id.sp_q);

        setupSpinner(spCam, new String[]{"后置摄像头", "前置摄像头"}, camIndex);
        setupSpinner(spRes, new String[]{"640×480", "1280×720（推荐）", "1920×1080"}, resIdx);
        setupSpinner(spRot, new String[]{"不旋转", "旋转 90°", "旋转 180°", "旋转 270°"}, rotationDeg / 90);
        setupSpinner(spQ, new String[]{"画质 中(60)", "画质 高(80)", "画质 最高(90)"}, qualityToIdx(jpegQuality));

        spCam.setOnItemSelectedListener(onSel((pos, sel) -> {
            camIndex = pos;
            prefs.edit().putInt("cam", pos).apply();
        }));
        spRes.setOnItemSelectedListener(onSel((pos, sel) -> prefs.edit().putInt("res", pos).apply()));
        spRot.setOnItemSelectedListener(onSel((pos, sel) -> {
            rotationDeg = pos * 90;
            prefs.edit().putInt("rot", rotationDeg).apply();
        }));
        spQ.setOnItemSelectedListener(onSel((pos, sel) -> {
            jpegQuality = idxToQuality(pos);
            prefs.edit().putInt("q", jpegQuality).apply();
        }));

        btnStart.setOnClickListener(v -> {
            if (CamService.running) {
                stopService(new Intent(this, CamService.class));
                onStopped();
            } else {
                applyResolution(resIdxNow());
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
                } else {
                    startServing();
                }
            }
        });

        // 一键复制观看地址：整条覆盖条都能点
        View.OnClickListener copyClick = v -> {
            String u = tvCopyUrl.getText().toString();
            int i = u.indexOf("http");
            if (i < 0) return;
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("url", u.substring(i).trim()));
                Toast.makeText(this, "已复制，粘贴到查看页配置即可", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        };
        tvCopyUrl.setOnClickListener(copyClick);
        overlay.setOnClickListener(copyClick);

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, CamService.class));
            onStopped();
        });

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                if (CamService.running && instance != null) CamService.attachSurface(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                CamService.detachSurface();
            }
        });

        if (CamService.running) {
            onRunning();
            if (urlToDisplay != null) showUrl(urlToDisplay);
        }
    }

    private interface SelListener {
        void onSel(int pos, String label);
    }

    private AdapterView.OnItemSelectedListener onSel(final SelListener l) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                l.onSel(pos, (String) p.getItemAtPosition(pos));
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        };
    }

    private int resIdxNow() {
        return spRes.getSelectedItemPosition();
    }

    private void applyResolution(int idx) {
        switch (idx) {
            case 0:
                prefWidth = 640;
                prefHeight = 480;
                break;
            case 2:
                prefWidth = 1920;
                prefHeight = 1080;
                break;
            default:
                prefWidth = 1280;
                prefHeight = 720;
        }
    }

    private int qualityToIdx(int q) {
        return q >= 90 ? 2 : (q >= 80 ? 1 : 0);
    }

    private int idxToQuality(int idx) {
        return idx == 2 ? 90 : (idx == 1 ? 80 : 60);
    }

    private void setupSpinner(Spinner sp, String[] items, int sel) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(sel);
    }

    private void startServing() {
        startForegroundService(new Intent(this, CamService.class));
        onRunning();
    }

    private void onRunning() {
        scrollSettings.setVisibility(View.GONE);   // 整个设置层隐藏：不挡画面也不吃触摸
        surfaceView.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
        btnStart.setVisibility(View.GONE);
        if (surfaceReady && CamService.instance != null) {
            CamService.attachSurface(surfaceView.getHolder());
        }
    }

    private void onStopped() {
        scrollSettings.setVisibility(View.VISIBLE);
        surfaceView.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);
        btnStart.setVisibility(View.VISIBLE);
        btnStart.setText("开始服务");
        tvState.setText("未在运行");
        tvUrl.setVisibility(View.GONE);
        urlToDisplay = null;
    }

    // ---- Service 回调（HTTP 线程调过来，必须切回主线程，否则闪退）----

    public static void onUrlReady(String url) {
        urlToDisplay = url;
        final String u = url;
        if (instance != null) {
            instance.runOnUiThread(() -> instance.showUrl(u));
        }
    }

    public static void onShareStoppedStatic() {
        if (instance != null) {
            instance.runOnUiThread(() -> instance.onStopped());
        }
    }

    /** 摄像头打开失败等信息显示到界面 */
    public static void onError(String msg) {
        if (instance != null) {
            instance.runOnUiThread(() -> {
                instance.tvState.setText("出错：" + msg);
                instance.tvState.setTextColor(0xFFCC3300);
            });
        }
    }

    private void showUrl(String url) {
        if (url == null || url.isEmpty()) {
            tvCopyUrl.setText("获取 IP 失败：请确认已连接 WiFi/热点");
            return;
        }
        onRunning();
        tvCopyUrl.setText("点此复制观看地址：" + url + "/video");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAM && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startServing();
        } else {
            Toast.makeText(this, "需要相机权限才能推流", Toast.LENGTH_SHORT).show();
        }
    }

    /** 沉浸式全屏：隐藏状态栏和导航栏 */
    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && CamService.running) goImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        instance = this;
        if (CamService.running) {
            goImmersive();
            onRunning();
            if (urlToDisplay != null) showUrl(urlToDisplay);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        instance = null;
        CamService.detachSurface(); // 离开界面回无 UI 推流
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && CamService.running) {
            stopService(new Intent(this, CamService.class));
        }
    }
}
