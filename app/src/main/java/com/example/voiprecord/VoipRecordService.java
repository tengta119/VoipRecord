package com.example.voiprecord;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.voiprecord.VO.UserSession;
import com.example.voiprecord.utils.VoipUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class VoipRecordService extends Service {
    private static final String TAG = "VoipRecordingService";
    public static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_FACTOR = 4;

    private AudioRecord micRecord;
    private AudioRecord playbackRecord;
    private Thread micThread;
    private Thread playbackThread;
    private volatile boolean isRecording = false; // 使用 volatile 保证线程可见性
    private static MediaProjection mMediaProjection;
    private File micOutputFile;
    private File playbackOutputFile;
    private static final int NOTIFICATION_ID = 10001;
    private String currentCommand = "";
    private String serverAddress = "";
    private String username = "unknown";

    // 截图相关变量
    private Thread screenshotThread;
    private static final int SCREENSHOT_PORT = 8003;
    private static final int SCREENSHOT_INTERVAL = 10000; // 10秒
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    // 新增：重连延迟时间 (5秒)
    private static final int RECONNECT_DELAY_MS = 5000;

    public static final String ACTION_RECORDING_STARTED = "com.example.voiprecord.RECORDING_STARTED";
    public static final String ACTION_RECORDING_STOPPED = "com.example.voiprecord.RECORDING_STOPPED";

    // 音频块的录制时长
    public static int AUDIO_CHUNK_INTERVAL_MS = 15000 / 4;
    // 截屏的时间
    public static int IMAGE_FREQUENCY = 15000 / 4;
    // Session
    public static String USERSESSIONID;
    private void startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "voip_record_channel",
                    "语音通话录音",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("语音通话录音服务运行中");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, "voip_record_channel")
                .setContentTitle("语音通话录音")
                .setContentText("正在后台录音...")
                .setSmallIcon(R.drawable.ic_mic)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE);
    }

    public static void setMediaProjection(MediaProjection mediaProjection) {
        mMediaProjection = mediaProjection;
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("command")) {
            currentCommand = intent.getStringExtra("command");
        }

        if ("start".equals(currentCommand)) {
            SharedPreferences prefs = getSharedPreferences("voip_config", MODE_PRIVATE);
            serverAddress = prefs.getString("server", "");
            username = prefs.getString("username", "unknown");

            startForegroundService();
            if (mMediaProjection != null) {
                startRecording();
            }
        } else if ("stop".equals(currentCommand)) {
            stopRecording();
        }
        return START_STICKY;
    }

    private void initScreenshot() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "MediaProjection stopped by user/system. Stopping service.");
                stopRecording();
            }
        }, null);

        if (imageReader == null && virtualDisplay == null) {
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
            Log.w(TAG, "Recording already in progress.");
            return;
        }

        if (!serverAddress.isEmpty()) {
            initScreenshot();
        }

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = minBufferSize * BUFFER_SIZE_FACTOR;

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "缺少录音权限", Toast.LENGTH_SHORT).show();
                return;
            }

            micRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AUDIO_FORMAT).setSampleRate(SAMPLE_RATE).setChannelMask(CHANNEL_CONFIG).build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();

            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mMediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build();

            playbackRecord = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AUDIO_FORMAT).setSampleRate(SAMPLE_RATE).setChannelMask(CHANNEL_CONFIG).build())
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            if (micRecord.getState() != AudioRecord.STATE_INITIALIZED || playbackRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed.");
                Toast.makeText(this, "录音初始化失败", Toast.LENGTH_SHORT).show();
                return;
            }

            micRecord.startRecording();
            playbackRecord.startRecording();
            isRecording = true;

            // 线程自己管理连接
            micThread = new Thread(() -> recordAndSendAudio(micRecord, "ch0"));
            playbackThread = new Thread(() -> recordAndSendAudio(playbackRecord, "ch1"));
            screenshotThread = new Thread(this::captureAndSendScreenshots);
            CompletableFuture<UserSession> completableFuture = CompletableFuture.supplyAsync(() -> {
                UserSession userSession = ApiClient.createNewCallSession(MainActivity.IP, username);
                AUDIO_CHUNK_INTERVAL_MS = userSession.getAudioChunkSize() * 1000;
                IMAGE_FREQUENCY = userSession.getImageFrequency() * 1000;
                USERSESSIONID = userSession.getSessionId();

                return userSession;
            });
            completableFuture.get();
            micThread.start();
            playbackThread.start();
            if (!serverAddress.isEmpty()) {
                screenshotThread.start();
            }

            Log.i(TAG, "Recording started");
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_RECORDING_STARTED));

        } catch (Exception e) {
            Log.e(TAG, "Recording start failed", e);
            stopSelf();
        }
    }

    /**
     * 新增：带重连逻辑的连接函数
     * @param host      服务器地址
     * @param port      服务器端口
     * @param streamRef 用于持有并返回创建的 OutputStream
     * @return Socket 连接对象，如果录音停止则返回 null
     */
    private Socket connectWithRetry(String host, int port, AtomicReference<OutputStream> streamRef) {
        while (isRecording) {
            try {
                Log.i(TAG, "Attempting to connect to " + host + ":" + port);
                Socket socket = new Socket(host, port);
                OutputStream outputStream = socket.getOutputStream();
                sendUsername(outputStream, username); // 发送用户名
                streamRef.set(outputStream); // 将 OutputStream 存入 AtomicReference
                Log.i(TAG, "Successfully connected to " + host + ":" + port);
                return socket;
            } catch (IOException e) {
                Log.w(TAG, "Connection to " + host + ":" + port + " failed. Retrying in " + RECONNECT_DELAY_MS + "ms.", e);
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // 恢复中断状态
                    Log.w(TAG, "Reconnect delay interrupted. Stopping connection attempts.");
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 新增：发送用户名的辅助方法
     */
    private void sendUsername(OutputStream out, String username) throws IOException {
        byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        out.write(usernameBytes.length);
        out.write(usernameBytes);
        out.flush();
        Log.d(TAG, "Sent username: " + username);
    }

    /**
     * 重构：录制并发送音频的线程任务
     */
    private void recordAndSendAudio(AudioRecord audioRecord, String direction) {
        final int readBufferSize = 4096;
        byte[] readBuffer = new byte[readBufferSize];

        int count = 0;
        while (isRecording) {

            // Step 1: 累计 AUDIO_CHUNK_INTERVAL_MS 的音频数据
            ByteArrayOutputStream pcmChunk = new ByteArrayOutputStream();
            long startTime = System.currentTimeMillis();
            while (isRecording && (System.currentTimeMillis() - startTime < AUDIO_CHUNK_INTERVAL_MS)) {
                int read = audioRecord.read(readBuffer, 0, readBufferSize);
                if (read > 0) {
                    pcmChunk.write(readBuffer, 0, read);
                }

            }

            if (!isRecording || pcmChunk.size() == 0) {
                continue;
            }

            // Step 2: 将音频数据转换为 wav 格式
            byte[] pcmData = pcmChunk.toByteArray();
            byte[] wavData;
            try {
                byte[] header = VoipUtil.createWavHeader(pcmData.length);
                wavData = new byte[header.length + pcmData.length];
                System.arraycopy(header, 0, wavData, 0, header.length);
                System.arraycopy(pcmData, 0, wavData, header.length, pcmData.length);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create WAV header for " + direction, e);
                continue;
            }

            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), count + "_voip_up_" + direction + "_" + username + ".wav");
            try(FileOutputStream fosChunk = new FileOutputStream(file)) {
                fosChunk.write(wavData);
            } catch (IOException e) {
                Log.e(TAG, "File write error: " + file.getName(), e);
            }
            ApiClient.uploadAudioChunk(MainActivity.IP, USERSESSIONID, direction, count, file);
            count++;
            Log.i(TAG, "Sent " + direction + " chunk: " + file.getName());
        }

        Log.i(TAG, direction + " thread finished.");
    }

    /**
     * 重构：捕获并发送截图的线程任务
     */
    private void captureAndSendScreenshots() {
        Socket socket = null;
        OutputStream netStream = null;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        while (isRecording) {
            try {
                // 检查网络连接
                if (socket == null || !socket.isConnected() || socket.isClosed()) {
                    AtomicReference<OutputStream> streamRef = new AtomicReference<>();
                    socket = connectWithRetry(serverAddress, SCREENSHOT_PORT, streamRef);
                    netStream = streamRef.get();
                    if (socket == null) break;
                }

                Thread.sleep(SCREENSHOT_INTERVAL);

                Image image = imageReader.acquireLatestImage();
                if (image == null) continue;

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;

                Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                image.close();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                byte[] jpegData = baos.toByteArray();

                // 发送数据
                if (netStream != null) {
                    ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
                    sizeBuffer.putInt(jpegData.length);
                    netStream.write(sizeBuffer.array());
                    netStream.write(jpegData);
                    netStream.flush();
                    Log.d(TAG, "Sent screenshot: " + jpegData.length + " bytes");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Screenshot thread interrupted.");
                break;
            } catch (Exception e) {
                Log.e(TAG, "Screenshot send error. Connection lost. Attempting to reconnect.", e);
                closeSocket(socket);
                socket = null;
                netStream = null;
            }
        }
        closeSocket(socket);
        Log.i(TAG, "Screenshot thread finished.");
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if(mMediaProjection != null){
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    private void stopRecording() {
        if (!isRecording) return;
        Log.i(TAG, "Stopping recording...");

        isRecording = false; // **核心**：先设置标志位，让所有循环都能退出

        // 中断线程，特别是当它们在 sleep 或 I/O 操作中阻塞时
        if (micThread != null) micThread.interrupt();
        if (playbackThread != null) playbackThread.interrupt();
        if (screenshotThread != null) screenshotThread.interrupt();

        // 等待线程结束（可选但推荐）
        try {
            if (micThread != null) micThread.join(1000);
            if (playbackThread != null) playbackThread.join(1000);
            if (screenshotThread != null) screenshotThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Thread join interrupted during stop.", e);
        }

        if (micRecord != null) {
            micRecord.stop();
            micRecord.release();
            micRecord = null;
        }
        if (playbackRecord != null) {
            playbackRecord.stop();
            playbackRecord.release();
            playbackRecord = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }


        Log.i(TAG, "Recording stopped successfully.");
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_RECORDING_STOPPED));
    }


    /**
     * 新增：安全关闭 Socket 的辅助方法
     */
    private void closeSocket(Socket socket) {
        if (socket != null) {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket.", e);
            }
        }
    }
}