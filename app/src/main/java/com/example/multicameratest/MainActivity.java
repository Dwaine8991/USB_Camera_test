package com.example.multicameratest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final Size TARGET_SIZE = new Size(1920, 1080);
    private static final int TARGET_FPS = 60;
    private static final String STATUS_RUNNING = "\u8fd0\u884c\u4e2d";
    private static final String STATUS_NO_FRAMES = "\u5df2\u6253\u5f00\u4f46\u6ca1\u6709\u5e27";

    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Handler mainHandler;

    private Button refreshButton;
    private Button startButton;
    private Button stopButton;
    private TextView summaryText;
    private LinearLayout cameraContainer;

    private final List<CameraSlot> slots = new ArrayList<>();
    private boolean testRunning = false;
    private int nextOpenIndex = 0;

    private final Runnable fpsTicker = new Runnable() {
        @Override
        public void run() {
            if (!testRunning) {
                return;
            }
            for (CameraSlot slot : slots) {
                slot.tickFps();
                renderSlot(slot);
            }
            updateSummary();
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        mainHandler = new Handler(getMainLooper());

        refreshButton = findViewById(R.id.refreshButton);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        summaryText = findViewById(R.id.summaryText);
        cameraContainer = findViewById(R.id.cameraContainer);

        refreshButton.setOnClickListener(v -> refreshCameraCards());
        startButton.setOnClickListener(v -> startTest());
        stopButton.setOnClickListener(v -> stopTest("测试已停止"));

        startCameraThread();

        if (hasCameraPermission()) {
            refreshCameraCards();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopTest("应用进入后台，已释放所有相机。");
    }

    @Override
    protected void onDestroy() {
        stopTest("应用销毁，已释放所有相机。");
        stopCameraThread();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA_PERMISSION) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshCameraCards();
        } else {
            setSummaryText("未授予 CAMERA 权限，无法开始测试。");
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("camera-test-thread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException ignored) {
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void refreshCameraCards() {
        stopTest("相机列表已刷新。");
        if (!hasCameraPermission()) {
            setSummaryText("缺少 CAMERA 权限。");
            return;
        }

        slots.clear();
        cameraContainer.removeAllViews();

        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            if (cameraIds.length == 0) {
                setSummaryText("未发现可用 Camera2 设备。");
                return;
            }
            for (String cameraId : cameraIds) {
                slots.add(buildSlot(cameraId));
            }
            Collections.sort(slots, new Comparator<CameraSlot>() {
                @Override
                public int compare(CameraSlot left, CameraSlot right) {
                    if (left.external != right.external) {
                        return left.external ? -1 : 1;
                    }
                    return left.cameraId.compareTo(right.cameraId);
                }
            });
            for (CameraSlot slot : slots) {
                addSlotCard(slot);
                renderSlot(slot);
            }
            updateSummary();
        } catch (CameraAccessException e) {
            setSummaryText("鐠囪褰囬惄鍛婃簚閸掓銆冩径杈Е: " + e.getMessage());
        }
    }

    private CameraSlot buildSlot(String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        CameraSlot slot = new CameraSlot();
        slot.cameraId = cameraId;
        slot.external = isExternal(characteristics);
        slot.facingLabel = facingToLabel(characteristics.get(CameraCharacteristics.LENS_FACING));
        slot.hardwareLevel = hardwareLevelToLabel(
                characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));

        android.hardware.camera2.params.StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes != null) {
                for (Size size : sizes) {
                    if (TARGET_SIZE.equals(size)) {
                        slot.supports1080p = true;
                        break;
                    }
                }
                slot.previewSizes = joinSizes(sizes, 8);
            }
        }

        Range<Integer>[] ranges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            slot.fpsRanges = joinRanges(ranges);
            slot.targetRange = choose60FpsRange(ranges);
        } else {
            slot.fpsRanges = "未知";
        }

        slot.supportsTarget = slot.supports1080p && slot.targetRange != null;
        if (slot.supportsTarget) {
            slot.status = "待测试";
        } else if (!slot.supports1080p) {
            slot.status = "不支持 1920x1080 预览";
        } else {
            slot.status = "没有可用的 60fps AE 范围";
        }
        return slot;
    }

    private boolean isExternal(CameraCharacteristics characteristics) {
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        return facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL;
    }

    private String facingToLabel(Integer facing) {
        if (facing == null) {
            return "未知";
        }
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return "前置";
        }
        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
            return "后置";
        }
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
            return "外接";
        }
        return "其他";
    }

    private String hardwareLevelToLabel(Integer level) {
        if (level == null) {
            return "未知";
        }
        switch (level) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                return "EXTERNAL";
            default:
                return "未知(" + level + ")";
        }
    }

    private Range<Integer> choose60FpsRange(Range<Integer>[] ranges) {
        Range<Integer> best = null;
        for (Range<Integer> range : ranges) {
            if (range == null || range.getUpper() < TARGET_FPS || range.getLower() > TARGET_FPS) {
                continue;
            }
            if (best == null) {
                best = range;
                continue;
            }
            int bestSpan = best.getUpper() - best.getLower();
            int currentSpan = range.getUpper() - range.getLower();
            if (currentSpan < bestSpan || (currentSpan == bestSpan && range.getUpper() < best.getUpper())) {
                best = range;
            }
        }
        return best;
    }

    private String joinSizes(Size[] sizes, int maxCount) {
        List<String> items = new ArrayList<>();
        int count = Math.min(sizes.length, maxCount);
        for (int i = 0; i < count; i++) {
            items.add(sizes[i].getWidth() + "x" + sizes[i].getHeight());
        }
        if (sizes.length > maxCount) {
            items.add("...");
        }
        return TextUtils.join(", ", items);
    }

    private String joinRanges(Range<Integer>[] ranges) {
        List<String> items = new ArrayList<>();
        for (Range<Integer> range : ranges) {
            items.add(range.getLower() + "-" + range.getUpper());
        }
        return TextUtils.join(", ", items);
    }

    private void addSlotCard(CameraSlot slot) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#F4F7FB"));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(12);
        cameraContainer.addView(card, cardParams);
        slot.cardView = card;

        TextView title = new TextView(this);
        title.setTextColor(Color.parseColor("#111111"));
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);
        slot.titleView = title;

        TextView info = new TextView(this);
        info.setTextColor(Color.parseColor("#444444"));
        info.setTextSize(13f);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        infoParams.topMargin = dp(4);
        card.addView(info, infoParams);
        slot.infoView = info;

        TextureView textureView = new TextureView(this);
        textureView.setOpaque(false);
        LinearLayout.LayoutParams textureParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(180)
        );
        textureParams.topMargin = dp(10);
        card.addView(textureView, textureParams);
        slot.textureView = textureView;
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                slot.surfaceReady = true;
                if (testRunning && slot.pendingSurfaceStart && !slot.openAttempted) {
                    slot.pendingSurfaceStart = false;
                    openSlot(slot);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                slot.surfaceReady = false;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        TextView metrics = new TextView(this);
        metrics.setTextColor(Color.parseColor("#1A2B49"));
        metrics.setTextSize(14f);
        LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        metricsParams.topMargin = dp(10);
        card.addView(metrics, metricsParams);
        slot.metricsView = metrics;
    }

    private void startTest() {
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }

        stopAllSessions();

        boolean hasAnyCandidate = false;
        boolean hasExternalCandidate = false;
        for (CameraSlot slot : slots) {
            slot.resetRuntimeState();
            if (slot.supportsTarget) {
                hasAnyCandidate = true;
                if (slot.external) {
                    hasExternalCandidate = true;
                }
            }
            renderSlot(slot);
        }

        if (!hasAnyCandidate) {
            setSummaryText("当前没有找到支持 1920x1080 且包含 60fps 范围的 Camera2 设备。");
            return;
        }

        testRunning = true;
        nextOpenIndex = 0;
        for (CameraSlot slot : slots) {
            slot.selectedForTest = slot.supportsTarget && (!hasExternalCandidate || slot.external);
            if (!slot.selectedForTest && slot.supportsTarget) {
                slot.status = hasExternalCandidate ? "已跳过（优先测试外接相机）" : "待测试";
            } else if (slot.selectedForTest) {
                slot.status = "等待打开";
            }
            renderSlot(slot);
        }

        setSummaryText(hasExternalCandidate
                ? "开始测试外接相机的 1080p60 并发能力。"
                : "未发现外接相机，开始测试所有支持 1080p60 的相机。");

        mainHandler.removeCallbacks(fpsTicker);
        mainHandler.postDelayed(fpsTicker, 1000);
        openNextSelectedSlot();
    }

    private void openNextSelectedSlot() {
        if (!testRunning) {
            return;
        }
        while (nextOpenIndex < slots.size()) {
            CameraSlot slot = slots.get(nextOpenIndex++);
            if (!slot.selectedForTest) {
                continue;
            }
            if (!slot.textureView.isAvailable()) {
                slot.pendingSurfaceStart = true;
                slot.status = "等待预览 Surface";
                renderSlot(slot);
                return;
            }
            openSlot(slot);
            return;
        }
        updateSummary();
    }

    private void openSlot(CameraSlot slot) {
        if (!testRunning || slot.openAttempted) {
            return;
        }
        slot.openAttempted = true;
        slot.status = "打开中";
        renderSlot(slot);

        try {
            SurfaceTexture texture = slot.textureView.getSurfaceTexture();
            if (texture == null) {
                slot.status = "SurfaceTexture 不可用";
                renderSlot(slot);
                openNextSelectedSlot();
                return;
            }
            texture.setDefaultBufferSize(TARGET_SIZE.getWidth(), TARGET_SIZE.getHeight());
            slot.previewSurface = new Surface(texture);
            cameraManager.openCamera(slot.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    slot.cameraDevice = camera;
                    slot.status = "相机已打开，配置会话中";
                    renderSlot(slot);
                    createSession(slot);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    slot.status = "相机断开";
                    closeSlot(slot);
                    renderSlot(slot);
                    openNextSelectedSlot();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    slot.status = "打开失败，错误码=" + error;
                    closeSlot(slot);
                    renderSlot(slot);
                    openNextSelectedSlot();
                }
            }, cameraHandler);
        } catch (SecurityException | CameraAccessException e) {
            slot.status = "打开异常: " + e.getMessage();
            renderSlot(slot);
            openNextSelectedSlot();
        }
    }

    private void createSession(CameraSlot slot) {
        if (slot.cameraDevice == null || slot.previewSurface == null) {
            slot.status = "会话创建前资源缺失";
            renderSlot(slot);
            openNextSelectedSlot();
            return;
        }
        try {
            slot.cameraDevice.createCaptureSession(
                    Arrays.asList(slot.previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            slot.captureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        slot.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(slot.previewSurface);
                                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                if (slot.targetRange != null) {
                                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, slot.targetRange);
                                }
                                slot.captureSession.setRepeatingRequest(builder.build(),
                                        new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureCompleted(CameraCaptureSession session,
                                                                           CaptureRequest request,
                                                                           TotalCaptureResult result) {
                                                slot.frameCounter++;
                                            }
                                }, cameraHandler);
                                slot.status = STATUS_RUNNING;
                                slot.running = true;
                                renderSlot(slot);
                            } catch (CameraAccessException e) {
                                slot.status = "启动预览失败: " + e.getMessage();
                                closeSlot(slot);
                                renderSlot(slot);
                            }
                            openNextSelectedSlot();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            slot.status = "会话配置失败";
                            closeSlot(slot);
                            renderSlot(slot);
                            openNextSelectedSlot();
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException e) {
            slot.status = "创建会话异常: " + e.getMessage();
            closeSlot(slot);
            renderSlot(slot);
            openNextSelectedSlot();
        }
    }

    private void stopTest(String message) {
        testRunning = false;
        mainHandler.removeCallbacks(fpsTicker);
        stopAllSessions();
        updateSummary();
        if (!TextUtils.isEmpty(message)) {
            mainHandler.post(() -> summaryText.setText(message + "\n" + summaryText.getText()));
        }
    }

    private void stopAllSessions() {
        for (CameraSlot slot : slots) {
            closeSlot(slot);
            slot.running = false;
            slot.pendingSurfaceStart = false;
            slot.openAttempted = false;
            slot.lastFps = 0f;
            slot.frameCounter = 0;
            slot.lastFrameCounter = 0;
            if (slot.selectedForTest && slot.supportsTarget) {
                slot.status = "待测试";
            }
            renderSlot(slot);
        }
    }

    private void closeSlot(CameraSlot slot) {
        if (slot.captureSession != null) {
            try {
                slot.captureSession.stopRepeating();
            } catch (Exception ignored) {
            }
            slot.captureSession.close();
            slot.captureSession = null;
        }
        if (slot.cameraDevice != null) {
            slot.cameraDevice.close();
            slot.cameraDevice = null;
        }
        if (slot.previewSurface != null) {
            slot.previewSurface.release();
            slot.previewSurface = null;
        }
    }

    private void updateSummary() {
        int external = 0;
        int supported = 0;
        int selected = 0;
        int running = 0;
        int stable = 0;
        int failed = 0;

        for (CameraSlot slot : slots) {
            if (slot.external) {
                external++;
            }
            if (slot.supportsTarget) {
                supported++;
            }
            if (!slot.selectedForTest) {
                continue;
            }
            selected++;
            if (slot.running) {
                running++;
            }
            if (slot.lastFps >= 55f) {
                stable++;
            }
            if (slot.openAttempted && !slot.running
                    && !"等待预览 Surface".equals(slot.status)
                    && !"打开中".equals(slot.status)
                    && !"相机已打开，配置会话中".equals(slot.status)) {
                failed++;
            }
        }

        String summary = String.format(Locale.US,
                "发现 %d 路 Camera2，相机中外接=%d，支持 1080p60=%d。\n已纳入测试=%d，正在运行=%d，稳定 >=55fps=%d，失败/掉线=%d。",
                slots.size(), external, supported, selected, running, stable, failed);
        setSummaryText(summary);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void renderSlot(CameraSlot slot) {
        mainHandler.post(slot::render);
    }

    private void setSummaryText(String text) {
        mainHandler.post(() -> summaryText.setText(text));
    }

    private static class CameraSlot {
        String cameraId;
        boolean external;
        boolean supports1080p;
        boolean supportsTarget;
        boolean selectedForTest;
        boolean surfaceReady;
        boolean pendingSurfaceStart;
        boolean openAttempted;
        boolean running;
        String facingLabel;
        String hardwareLevel;
        String previewSizes;
        String fpsRanges;
        String status;
        Range<Integer> targetRange;

        TextureView textureView;
        LinearLayout cardView;
        TextView titleView;
        TextView infoView;
        TextView metricsView;

        Surface previewSurface;
        CameraDevice cameraDevice;
        CameraCaptureSession captureSession;

        int frameCounter;
        int lastFrameCounter;
        float lastFps;

        void resetRuntimeState() {
            selectedForTest = false;
            surfaceReady = false;
            pendingSurfaceStart = false;
            openAttempted = false;
            running = false;
            frameCounter = 0;
            lastFrameCounter = 0;
            lastFps = 0f;
            if (supportsTarget) {
                status = "待测试";
            }
        }

        void tickFps() {
            lastFps = frameCounter - lastFrameCounter;
            lastFrameCounter = frameCounter;
            if (!running) {
                return;
            }
            if (lastFps < 1f) {
                status = STATUS_NO_FRAMES;
                return;
            }
            status = STATUS_RUNNING;
        }

        void render() {
            if (titleView == null || infoView == null || metricsView == null) {
                return;
            }
            titleView.setText(String.format(Locale.US, "相机 %s（%s）", cameraId, external ? "外接" : facingLabel));
            infoView.setText(String.format(Locale.US,
                    "硬件级别=%s\n支持 1080p=%s\n可用 FPS 范围=%s\n预览尺寸=%s",
                    hardwareLevel,
                    supports1080p ? "是" : "否",
                    fpsRanges,
                    previewSizes == null ? "未知" : previewSizes));
            metricsView.setText(String.format(Locale.US,
                    "状态: %s\n目标: 1920x1080 @ %s\n当前 FPS: %.0f",
                    status,
                    targetRange == null ? "无" : targetRange.getLower() + "-" + targetRange.getUpper(),
                    lastFps));
            metricsView.setGravity(Gravity.START);

            if (running && lastFps >= 55f) {
                metricsView.setTextColor(Color.parseColor("#0C6B2F"));
            } else if (running) {
                metricsView.setTextColor(Color.parseColor("#A15C00"));
            } else {
                metricsView.setTextColor(Color.parseColor("#1A2B49"));
            }

            if (cardView != null) {
                cardView.setAlpha(selectedForTest || supportsTarget ? 1f : 0.7f);
            }
        }
    }
}
