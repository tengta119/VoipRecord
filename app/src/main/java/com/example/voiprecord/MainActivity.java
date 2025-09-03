package com.example.voiprecord;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.voiprecord.constant.LocalBroadcastRecord;
import com.example.voiprecord.utils.HistoryRecordUtil;

public class MainActivity extends AppCompatActivity {

    private static final int OVERLAY_PERMISSION_CODE = 1001;
    private static final int REQUEST_PERMISSIONS_CODE = 1002;

    // 最大缓存音频文件的大小
    public static final long MAX_FOLDER_SIZE_BYTES = 5L * 1024 * 1024 * 1024;
    // 清理内存的频率
    public static final int CLEAN_MEMORY_FREQUENCY = 10000;
    // 请求重试的次数
    public static final int MAXRETRIY = 10;
    private EditText etUsername, etServer;

    private ImageButton recordButton;
    private TextView statusText;

    private enum RecordingState {
        IDLE,       // 空闲
        STARTING,   // 正在启动
        RECORDING,  // 录音中
        STOPPING,    // 正在停止
        FAIL,       // 失败
    }
    private RecordingState currentState = RecordingState.IDLE;
    public static String IP = "http://audio.api.nycjy.cn";
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

    private final BroadcastReceiver recordingStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            switch (action) {
                case LocalBroadcastRecord.ACTION_RECORDING_STARTED:
                    currentState = RecordingState.RECORDING;
                    updateRecordButtonUI();
                    break;
                case LocalBroadcastRecord.ACTION_RECORDING_STOPPED:
                    currentState = RecordingState.IDLE;
                    updateRecordButtonUI();
                    break;
                case LocalBroadcastRecord.ACTION_RECORDING_FAIL:
                    currentState = RecordingState.FAIL;
                    updateRecordButtonUI();
                    break;
                default:
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUsername = findViewById(R.id.etUsername);
        etServer = findViewById(R.id.etServer);
        // 申请录音权限
        Button btnRequestPermission = findViewById(R.id.btnRequestPermission);
        // 启动悬浮窗
        Button btnStartFloating = findViewById(R.id.btnStartFloating);
        // 停止悬浮窗
        Button btnStopFloating = findViewById(R.id.btnStopFloating);

        // SharedPreferences 是安卓提供的一个轻量级存储方案，适合存放简单的键值对数据
        // 读取上次保存的用户名和服务器
        SharedPreferences prefs = getSharedPreferences("voip_config", MODE_PRIVATE);
        etUsername.setText(prefs.getString("username", ""));
        etServer.setText(prefs.getString("server", ""));

        // 点击此按钮会调用 checkAndRequestPermissions() 方法，开始权限请求流程。
        btnRequestPermission.setOnClickListener(v -> checkAndRequestPermissions());

        btnStartFloating.setOnClickListener(v -> {
            String username = etUsername.getText().toString().trim();
            // 检查用户名和服务器地址是否为空
            if (username.isEmpty()) {
                Toast.makeText(this, "请填写用户名", Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存输入
            prefs.edit()
                    .putString("username", username)
                    .putString("server", "127.0.0.1")
                    .apply();

            // 检查悬浮窗权限 (Settings.canDrawOverlays(this))。如果未授予，则会跳转到系统设置页面让用户手动开启。
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_CODE);
                return;
            }

            // 启动悬浮窗服务
            Intent serviceIntent = new Intent(this, FloatingControlService.class);
            if (currentState == RecordingState.IDLE) {
                serviceIntent.putExtra("command", "空闲中");
            } else {
                serviceIntent.putExtra("command", "录音中");
            }
            // 以一种现代、安全且向后兼容的方式，启动一个高优先级的后台服务（即“前台服务”）。
            ContextCompat.startForegroundService(this, serviceIntent);
            Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show();
        });

        btnStopFloating.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(getApplicationContext(), FloatingControlService.class);
            stopService(serviceIntent);
            Toast.makeText(getApplicationContext(), "悬浮窗已停止", Toast.LENGTH_SHORT).show();
        });

        recordButton = findViewById(R.id.recordButtonMain);
        statusText = findViewById(R.id.statusTextMain);
        // 初始化 UI 状态
        updateRecordButtonUI();

        recordButton.setOnClickListener(v -> toggleRecording());

        IntentFilter filter = new IntentFilter();
        filter.addAction(LocalBroadcastRecord.ACTION_RECORDING_STARTED);
        filter.addAction(LocalBroadcastRecord.ACTION_RECORDING_STOPPED);
        filter.addAction(LocalBroadcastRecord.ACTION_RECORDING_FAIL);
        LocalBroadcastManager.getInstance(this).registerReceiver(recordingStateReceiver, filter);

        // 初始化：获取系统音频管理器。
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        ModeChangeListener modeChangeListener = new ModeChangeListener();
        // 册了一个监听器，用于监视系统音频模式的变化
        audioManager.addOnModeChangedListener(getMainExecutor(), modeChangeListener);

        //删除缓存的音频文件文件
        HistoryRecordUtil.deleteFile();

        // 1. 找到触发跳转的按钮
        Button navigateButton = findViewById(R.id.btnNavigateToHistoryRecord);

        // 2. 为按钮设置点击监听器
        navigateButton.setOnClickListener(v -> {
            // 3. 创建一个 Intent（意图）来启动新 Activity
            // 参数一是当前上下文 (this)，参数二是目标 Activity 的类
            Intent intent = new Intent(MainActivity.this, HistoryRecord.class);

            // 4. 执行跳转
            startActivity(intent);
        });
    }

    private void toggleRecording() {
        etUsername = findViewById(R.id.etUsername);
        SharedPreferences prefs = getSharedPreferences("voip_config", MODE_PRIVATE);
        String username = etUsername.getText().toString().trim();
        if (!username.isEmpty()) {
            prefs.edit()
                    .putString("username", String.valueOf(etUsername));
        }

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
            case FAIL:
                // 从“空闲”切换到“正在启动”
                currentState = RecordingState.STARTING;
                // 立即更新UI以显示中间状态
                updateRecordButtonUI();
                startRecording();
                break;
            case STARTING:
            case STOPPING:
                // 在中间状态时，不执行任何操作，防止用户重复点击
                Toast.makeText(this, "正在处理，请稍候...", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private void startRecording() {
        // 启动录音服务
        Intent serviceIntent = new Intent(this, VoipRecordService.class);
        serviceIntent.putExtra("command", "start");
        // startForegroundService 有问题 debug todo
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
            case FAIL:
                recordButton.setEnabled(true);
                recordButton.setBackgroundResource(R.drawable.bg_record_idle);
                recordButton.setImageResource(R.drawable.ic_mic);
                statusText.setText("Wifi 连接失败，请重试");
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

    private void checkAndRequestPermissions() {
        // 权限数组，包含了 RECORD_AUDIO (录音) 和 WRITE_EXTERNAL_STORAGE (写外部存储)
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        boolean needRequest = false;
        // 遍历数组，检查每一项权限是否已经被授予。
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        // 只要有一项权限未被授予，就会调用 ActivityCompat.requestPermissions()，弹出一个系统对话框，请求用户授权
        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS_CODE);
        } else {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
        }

        // 请求屏幕捕捉权限
        requestMediaProjection();
    }

    // 当用户从其他活动（如权限设置页面或屏幕捕捉确认框）返回到 MainActivity 时，这个方法会被调用。
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_CODE) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "悬浮窗权限未授予", Toast.LENGTH_SHORT).show();
            }
        }else if(requestCode == 2000) {
            MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            // 这个 MediaProjection 对象是一个令牌 (Token)，是进行屏幕捕捉的关键。
            MediaProjection mediaProjection = null;
            if (data != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
            } else {
                Log.e(TAG, "MediaProjection is null");
            }
            // 这行代码通过一个静态方法，将获取到的 MediaProjection 令牌传递给了另一个服务 VoipRecordService
            VoipRecordService.setMediaProjection(mediaProjection);
            Toast.makeText(this, "共享权限已授予", Toast.LENGTH_SHORT).show();
        }
    }
    private void requestMediaProjection() {
        MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = mediaProjectionManager.createScreenCaptureIntent();

        // 启动一个系统级的确认对话框，询问用户是否允许该应用捕捉屏幕内容。2000 是一个请求码，用于在 onActivityResult 中识别是哪个请求返回了结果。
        startActivityForResult(intent,2000);
    }
}
