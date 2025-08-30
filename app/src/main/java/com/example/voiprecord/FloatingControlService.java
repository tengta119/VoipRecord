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
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class FloatingControlService extends Service {
    private WindowManager windowManager;
    private View floatingView;
    private TextView statusText;
    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    WindowManager.LayoutParams params;
    private static final int NOTIFICATION_ID = 10001;
    private static final String TAG = "FloatingControlService";

    private AudioManager audioManager;
    private ModeChangeListener modeChangeListener;

    // 在 Service 类中，你不能像在 Activity 中那样直接调用 findViewById()。Service 本身没有与之关联的UI布局，所以直接调用它会返回 null。
    //你需要从你已经加载（inflate）并添加到 WindowManager 的 floatingView 上去查找这个 ImageView。
    private ImageView recordingAnimationView;
    private AnimatedVectorDrawable recordingAnimation;

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
            } else if (mode == AudioManager.MODE_NORMAL) {
                currentState = RecordingState.STOPPING;
                updateRecordButtonUI();
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

        // ---注册广播接收器，监听 VoipRecordService 的广播 ---
        IntentFilter filter = new IntentFilter();
        filter.addAction(VoipRecordService.ACTION_RECORDING_STARTED);
        filter.addAction(VoipRecordService.ACTION_RECORDING_STOPPED);
        LocalBroadcastManager.getInstance(this).registerReceiver(recordingStateReceiver, filter);

        // --- 在这里一次性找到所有视图 ---
        statusText = floatingView.findViewById(R.id.statusText);
        recordingAnimationView = floatingView.findViewById(R.id.recording_animation_view);

        // --- 获取动画Drawable并检查 ---
        Drawable drawable = recordingAnimationView.getDrawable();
        if (drawable instanceof AnimatedVectorDrawable) {
            recordingAnimation = (AnimatedVectorDrawable) drawable;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String command = intent.getStringExtra("command");
        if (command != null && command.equals("空闲中")) {
            currentState = RecordingState.IDLE;
            updateRecordButtonUI();
        } else if (command != null && command.equals("录音中")) {
            updateRecordButtonUI();
            currentState = RecordingState.RECORDING;
        }
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

        statusText = floatingView.findViewById(R.id.statusText);

    }

    private void updateRecordButtonUI() {
        if (statusText == null) return;

        switch (currentState) {
            case IDLE:
                statusText.setText("空闲中");
                recordingAnimation.stop(); // <--- 停止动画
                recordingAnimationView.setVisibility(View.GONE); // <--- 隐藏动画视图
                break;
            case RECORDING:
                statusText.setText("录音中");
                recordingAnimationView.setVisibility(View.VISIBLE); // <--- 显示动画视图
                recordingAnimation.start(); // <--- 开始动画
                break;
        }
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
