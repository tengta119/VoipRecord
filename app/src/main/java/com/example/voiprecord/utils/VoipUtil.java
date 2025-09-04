package com.example.voiprecord.utils;


import com.example.voiprecord.VoipRecordService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import android.graphics.Bitmap;
import android.media.Image;
import java.io.ByteArrayOutputStream;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
public class VoipUtil {


    /**
     * 将 ImageReader 获取的 Image 对象转换为 JPEG 格式的字节数组。
     *
     * @param image 从 ImageReader 获取的 Image 对象
     * @param width 图像的实际宽度
     * @param height 图像的实际高度
     * @return JPEG 格式的字节数组，如果 image 为 null 则返回 null
     */
    public static byte[] convertImageToJpegBytes(Image image, int width, int height) {
        if (image == null) {
            return null;
        }

        try {
            // 1. 从图像平面（Image Plane）中获取必要的详细信息
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();      // 每一行的总字节数（包含填充）
            int pixelStride = planes[0].getPixelStride();    // 每个像素的字节数 (例如 ARGB_8888 是 4)

            // 2. 创建一个具有正确最终尺寸的 Bitmap
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            // 3. 手动逐行复制数据，跳过每行末尾的填充（padding）
            //    这是最关键的一步，确保我们只读取有效的像素数据
            byte[] rowData = new byte[rowStride];
            int[] pixelData = new int[width];

            for (int y = 0; y < height; y++) {
                buffer.position(y * rowStride);
                buffer.get(rowData, 0, rowStride);

                for (int x = 0; x < width; x++) {
                    int pixel = 0;
                    int offset = x * pixelStride;
                    // 像素格式通常是 RGBA，但 Bitmap.Config.ARGB_8888 需要 ARGB 顺序
                    // 所以我们需要重新排列字节
                    byte r = rowData[offset];
                    byte g = rowData[offset + 1];
                    byte b = rowData[offset + 2];
                    byte a = rowData[offset + 3];

                    pixel |= (a & 0xff) << 24;
                    pixel |= (r & 0xff) << 16;
                    pixel |= (g & 0xff) << 8;
                    pixel |= (b & 0xff);

                    pixelData[x] = pixel;
                }
                bitmap.setPixels(pixelData, 0, width, 0, y, width, 1);
            }

            // 4. 将修正后的 Bitmap 压缩为 JPEG 格式
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);

            // 'jpegData' 现在正确地包含了截图的二进制数据
            return baos.toByteArray();

        } finally {
            // 5. 非常重要：关闭 image 对象以释放内存资源，让 ImageReader 可以接收下一张图片
            image.close();
        }
    }

    /**
     * 检查设备当前是否连接到 Wi-Fi 网络。
     * @param context Context
     * @return 如果连接到 Wi-Fi 则返回 true，否则返回 false。
     */
    public static boolean isWifiConnected(Context context) {
        // 获取系统连接管理器
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return false;
        }

        // 对于 Android 10 (API 29) 及以上版本
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 获取当前活跃网络的网络能力
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (capabilities == null) {
                return false;
            }
            // 检查网络传输类型是否为 Wi-Fi
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        // 对于旧版本
        else {
            android.net.NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            if (networkInfo == null || !networkInfo.isConnected()) {
                return false;
            }
            // 检查网络类型是否为 Wi-Fi
            return networkInfo.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    public static byte[] pcmToWav(long pcmDataSize, byte[] pcmData) {
        long totalDataLen = pcmDataSize + 36;
        int channels = 1; // Based on CHANNEL_IN_MONO
        long byteRate = VoipRecordService.SAMPLE_RATE * channels * (16 / 8); // 16 is for ENCODING_PCM_16BIT

        ByteBuffer header = ByteBuffer.allocate(44);
        header.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF/WAVE header
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt((int) totalDataLen);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16); // 16 for PCM
        header.putShort((short) 1); // Audio format 1=PCM
        header.putShort((short) channels);
        header.putInt(VoipRecordService.SAMPLE_RATE);
        header.putInt((int) byteRate);
        header.putShort((short) (channels * (16 / 8))); // Block align
        header.putShort((short) 16); // Bits per sample
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt((int) pcmDataSize);
        byte[] wavHead = header.array();

        byte[] wavData = new byte[wavHead.length + pcmData.length];
        System.arraycopy(wavHead, 0, wavData, 0, wavHead.length);
        System.arraycopy(pcmData, 0, wavData, wavHead.length, pcmData.length);

        return wavData;
    }

}
