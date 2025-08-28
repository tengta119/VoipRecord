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
import android.view.View;
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
        btnRequestPermission = findViewById(R.id.btnRequestPermission);
        btnStartFloating = findViewById(R.id.btnStartFloating);
        btnStopFloating = findViewById(R.id.btnStopFloating);

        // 读取上次保存的用户名和服务器
        SharedPreferences prefs = getSharedPreferences("voip_config", MODE_PRIVATE);
        etUsername.setText(prefs.getString("username", ""));
        etServer.setText(prefs.getString("server", ""));

        btnRequestPermission.setOnClickListener(v -> checkAndRequestPermissions());

        btnStartFloating.setOnClickListener(v -> {
            String username = etUsername.getText().toString().trim();
            String server = etServer.getText().toString().trim();

            if (username.isEmpty() || server.isEmpty()) {
                Toast.makeText(this, "请填写用户名和服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存输入
            prefs.edit()
                    .putString("username", username)
                    .putString("server", server)
                    .apply();

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_CODE);
                return;
            }

            // 启动悬浮窗服务
            Intent serviceIntent = new Intent(this, FloatingControlService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
            Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show();
        });
        btnStopFloating.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent serviceIntent = new Intent(getApplicationContext(), FloatingControlService.class);
                stopService(serviceIntent);
                Toast.makeText(getApplicationContext(), "悬浮窗已停止", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void checkAndRequestPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        boolean needRequest = false;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS_CODE);
        } else {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
        }
        requestMediaProjection();
    }

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
            MediaProjection mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
            VoipRecordService.setMediaProjection(mediaProjection);
            Toast.makeText(this, "共享权限已授予", Toast.LENGTH_SHORT).show();
        }
    }
    private void requestMediaProjection() {
        MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = mediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(intent,2000);
    }
}
