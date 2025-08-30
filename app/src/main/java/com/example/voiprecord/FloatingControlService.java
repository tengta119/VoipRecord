package com.example.voiprecord;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class FloatingControlService extends Service {
    private WindowManager windowManager;
    private View floatingView;
    private ImageButton recordButton;
    private TextView statusText;
    private boolean isRecording = false;
    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    WindowManager.LayoutParams params;
    private static final int NOTIFICATION_ID = 10001;
    private static final String TAG = "FloatingControlService";

    private AudioManager audioManager;
    private ModeChangeListener modeChangeListener;


    // --- 新增：使用包含四个状态的枚举来管理UI，逻辑更清晰 ---
    private enum RecordingState {
        IDLE,       // 空闲
        STARTING,   // 正在启动
        RECORDING,  // 录音中
        STOPPING    // 正在停止
    }
    private RecordingState currentState = RecordingState.IDLE;

    private final BroadcastReceiver recordingStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case VoipRecordService.ACTION_RECORDING_STARTED:
                    currentState = RecordingState.RECORDING;
                    updateRecordButtonUI();
                    break;
                case VoipRecordService.ACTION_RECORDING_STOPPED:
                    currentState = RecordingState.IDLE;
                    updateRecordButtonUI();
                    break;
                default:
            }
        }
    };

    private class ModeChangeListener implements AudioManager.OnModeChangedListener {
        @Override
        public void onModeChanged(int mode) {
            Log.d(TAG, "Audio mode changed to: " + mode);
            // 检测VoIP通话状态
            if (mode == AudioManager.MODE_IN_COMMUNICATION) {
                currentState = RecordingState.STARTING;
                updateRecordButtonUI();
                startRecording();
            } else if (mode == AudioManager.MODE_NORMAL) {
                currentState = RecordingState.STOPPING;
                updateRecordButtonUI();
                stopRecording();
            }
        }
    }


    @Override
    public void onCreate() {
        super.onCreate();
        // 创建并显示悬浮窗
        createFloatingWindow();
        // 初始化：获取系统音频管理器。
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        modeChangeListener = new ModeChangeListener();
        // 册了一个监听器，用于监视系统音频模式的变化
        audioManager.addOnModeChangedListener(getMainExecutor(), modeChangeListener);

        // --- 新增：注册广播接收器 ---
        IntentFilter filter = new IntentFilter();
        filter.addAction(VoipRecordService.ACTION_RECORDING_STARTED);
        filter.addAction(VoipRecordService.ACTION_RECORDING_STOPPED);
        LocalBroadcastManager.getInstance(this).registerReceiver(recordingStateReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
            startForegroundService();
            return START_STICKY;
    }

    private void startForegroundService() {
        // 创建通知渠道 (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "voip_record_channel",
                    "语音通话录音",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("语音通话录音服务运行中");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        // 创建通知
        Notification notification = new NotificationCompat.Builder(this, "voip_record_channel")
                .setContentTitle("语音通话录音")
                .setContentText("正在后台录音...")
                .setSmallIcon(R.drawable.ic_mic)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        // 启动前台服务（兼容性：使用与你工程一致的 startForeground 形式）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 屏幕旋转时重新定位悬浮窗
        ensureWithinScreenBounds();
    }

    private void ensureWithinScreenBounds() {
        if (windowManager == null || floatingView == null || params == null) return;

        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;

        int viewWidth = floatingView.getWidth();
        int viewHeight = floatingView.getHeight();

        // 如果尚未测量出尺寸，跳过
        if (viewWidth <= 0 || viewHeight <= 0) return;

        // 确保悬浮窗不会移出屏幕
        params.x = Math.max(0, Math.min(params.x, screenWidth - viewWidth));
        params.y = Math.max(0, Math.min(params.y, screenHeight - viewHeight));

        windowManager.updateViewLayout(floatingView, params);
    }

    private void createFloatingWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = LayoutInflater.from(this);
        floatingView = inflater.inflate(R.layout.floating_control, null);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                // 允许浮窗可点击但不抢焦点
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        // 设置初始位置
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        // 仅把拖拽监听放在 dragHandle 上，避免拦截按钮点击
        View dragHandle = floatingView.findViewById(R.id.dragHandle);
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // 使用原来的拖拽逻辑，但限定为手柄区域
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // 记录初始位置
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        // 计算偏移量
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);

                        // 更新位置
                        params.x = initialX + deltaX;
                        params.y = initialY + deltaY;

                        // 确保不会移出屏幕（使用 display 尺寸）
                        int maxX = windowManager.getDefaultDisplay().getWidth() - floatingView.getWidth();
                        int maxY = windowManager.getDefaultDisplay().getHeight() - floatingView.getHeight();
                        params.x = Math.max(0, Math.min(params.x, Math.max(0, maxX)));
                        params.y = Math.max(0, Math.min(params.y, Math.max(0, maxY)));

                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });

        // 添加悬浮窗
        windowManager.addView(floatingView, params);

        // 初始化按钮（ImageButton）和状态文本
        recordButton = floatingView.findViewById(R.id.recordButton);
        statusText = floatingView.findViewById(R.id.statusText);

        // 初始化 UI 状态
        updateRecordButtonUI();

        recordButton.setOnClickListener(v -> toggleRecording());
    }


    private void toggleRecording() {
        if (VoipRecordService.getMediaProjection() == null) {
            Toast.makeText(this, "请先重新授权", Toast.LENGTH_SHORT).show();
            statusText.setText("后台清理后请重新授权");
            return;
        }

        switch (currentState) {
            case IDLE:
                // 从“空闲”切换到“正在启动”
                currentState = RecordingState.STARTING;
                // 立即更新UI以显示中间状态
                updateRecordButtonUI();
                startRecording();
                break;
            case RECORDING:
                // 从“录音中”切换到“正在停止”
                currentState = RecordingState.STOPPING;
                // 立即更新UI以显示中间状态
                updateRecordButtonUI();
                stopRecording();
                break;
            case STARTING:
            case STOPPING:
                // 在中间状态时，不执行任何操作，防止用户重复点击
                Toast.makeText(this, "正在处理，请稍候...", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private void updateRecordButtonUI() {
        if (recordButton == null || statusText == null) return;

        Drawable previousDrawable = recordButton.getDrawable();
        if (previousDrawable instanceof AnimatedVectorDrawable) {
            ((AnimatedVectorDrawable) previousDrawable).stop();
        }

        switch (currentState) {
            case IDLE:
                recordButton.setEnabled(true);
                recordButton.setBackgroundResource(R.drawable.bg_record_idle);
                recordButton.setImageResource(R.drawable.ic_mic);
                statusText.setText("开始录音");
                break;
            case RECORDING:
                recordButton.setEnabled(true);
                recordButton.setBackgroundResource(R.drawable.bg_record_active);
                recordButton.setImageResource(R.drawable.ic_stop_white);
                statusText.setText("录音中");
                break;
            case STARTING:
            case STOPPING:
                recordButton.setEnabled(false); // 禁用按钮防止重复点击
                recordButton.setBackgroundResource(R.drawable.bg_record_idle); // 中间状态使用空闲背景
                recordButton.setImageResource(R.drawable.avd_loading_animated);

                //// 启动动画
                Drawable drawable = recordButton.getDrawable();
                if (drawable instanceof AnimatedVectorDrawable) {
                    AnimatedVectorDrawable avd = (AnimatedVectorDrawable) drawable;
                    avd.stop();
                    avd.start();
                }

                // 根据状态设置文本
                statusText.setText(currentState == RecordingState.STARTING ? "启动中..." : "处理中...");
                break;
        }
    }

    private void startRecording() {
        // 启动录音服务
        Intent serviceIntent = new Intent(this, VoipRecordService.class);
        serviceIntent.putExtra("command", "start");
        // Android O+ 建议使用 startForegroundService，如果 VoipRecordService 会在 onStartCommand 里调用 startForeground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopRecording() {
        // 停止录音服务
        Intent serviceIntent = new Intent(this, VoipRecordService.class);
        serviceIntent.putExtra("command", "stop");
        startService(serviceIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
