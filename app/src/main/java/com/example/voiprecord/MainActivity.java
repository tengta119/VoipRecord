package com.example.voiprecord;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int OVERLAY_PERMISSION_CODE = 1001;
    private static final int REQUEST_PERMISSIONS_CODE = 1002;

    private EditText etUsername, etServer;
    private Button btnRequestPermission, btnStartFloating, btnStopFloating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUsername = findViewById(R.id.etUsername);
        etServer = findViewById(R.id.etServer);
        // 申请录音权限
        btnRequestPermission = findViewById(R.id.btnRequestPermission);
        // 启动悬浮窗
        btnStartFloating = findViewById(R.id.btnStartFloating);
        // 停止悬浮窗
        btnStopFloating = findViewById(R.id.btnStopFloating);

        // SharedPreferences 是安卓提供的一个轻量级存储方案，适合存放简单的键值对数据
        // 读取上次保存的用户名和服务器
        SharedPreferences prefs = getSharedPreferences("voip_config", MODE_PRIVATE);
        etUsername.setText(prefs.getString("username", ""));
        etServer.setText(prefs.getString("server", ""));

        // 点击此按钮会调用 checkAndRequestPermissions() 方法，开始权限请求流程。
        btnRequestPermission.setOnClickListener(v -> checkAndRequestPermissions());

        btnStartFloating.setOnClickListener(v -> {
            String username = etUsername.getText().toString().trim();
            String server = etServer.getText().toString().trim();
            // 检查用户名和服务器地址是否为空
            if (username.isEmpty() || server.isEmpty()) {
                Toast.makeText(this, "请填写用户名和服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存输入
            prefs.edit()
                    .putString("username", username)
                    .putString("server", server)
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
            // 以一种现代、安全且向后兼容的方式，启动一个高优先级的后台服务（即“前台服务”）。
            ContextCompat.startForegroundService(this, serviceIntent);
            Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show();
        });

        btnStopFloating.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(getApplicationContext(), FloatingControlService.class);
            stopService(serviceIntent);
            Toast.makeText(getApplicationContext(), "悬浮窗已停止", Toast.LENGTH_SHORT).show();
        });

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
            MediaProjection mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
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
