package com.example.voiprecord.utils;
import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import android.util.Log;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.example.voiprecord.vo.CloseSessionVO;
import com.example.voiprecord.vo.UserSessionVO;
import com.google.gson.Gson;
public class ApiClient {

    // 推荐做法：全局共享一个 OkHttpClient 实例
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS) // 连接超时
            .readTimeout(30, TimeUnit.SECONDS)    // 读取超时
            .writeTimeout(30, TimeUnit.SECONDS)   // 写入超时
            .build();


    /**
     * 根据 API 发起创建新会话的 POST 请求
     */
    public static UserSessionVO createNewCallSession(String baseUrl, String username) {


        // 2. 使用 FormBody.Builder 构建请求体
        // 这会自动处理 URL 编码和设置 Content-Type 为 application/x-www-form-urlencoded
        RequestBody formBody = new FormBody.Builder()
                .add("username", username) // 添加表单参数
                .build();

        // 3. 构建 Request 对象，指明 URL 和请求体
        String url = baseUrl + "/api/v1/call/new";
        Request request = new Request.Builder()
                .url(url)
                .post(formBody) // 指明是 POST 请求，并附上请求体
                .build();

        UserSessionVO userSessionVO;
        try (Response response = client.newCall(request).execute()) {

            String jsonStr = "";
            if (response.body() != null) {
                jsonStr = response.body().string();
            }
            // 2. 创建一个 Gson 实例
            Gson gson = new Gson();
            userSessionVO = gson.fromJson(jsonStr, UserSessionVO.class);
            Log.d(TAG, "userSession:" + userSessionVO.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return userSessionVO;
    }

    /**
     * 上传单个音频分片文件
     *
     * @param baseUrl    API的基础URL
     * @param sessionId  通话的 session_id
     * @param channelName 声道名, 例如 "ch0", "ch1"
     * @param chunkIndex  分块序号, 从 0 开始
     * @param audioFile   要上传的本地音频文件 (File对象)
     */
    public static void uploadAudioChunk(String baseUrl, String sessionId, String channelName, int chunkIndex, File audioFile){


        // 1. 遵循API要求，生成文件名
        String apiFilename = String.format("%s_%d.wav", channelName, chunkIndex);

        System.out.printf("准备上传: %s, 声道: %s, 序号: %d\n", audioFile.getAbsolutePath(), channelName, chunkIndex);

        // 2. 验证文件是否存在且可读
        if (!audioFile.exists() || !audioFile.canRead()) {
            Log.e(TAG, String.format("文件不存在或不可读: %s", audioFile.getAbsolutePath()));
        }

        // 3. 构建 multipart/form-data 请求体
        // Content-Type: multipart/form-data
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "audio", // 这是API要求的表单字段名
                        apiFilename,     // 这是API要求的文件名
                        RequestBody.create(audioFile, MediaType.parse("audio/wav"))
                )
                .build();

        // 4. 构建完整的请求URL
        // POST /api/v1/call/{session_id}/audio
        String url = String.format("%s/api/v1/call/%s/audio", baseUrl, sessionId);

        // 5. 创建Request对象
        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                // 如果需要添加认证等Header，可以在这里添加
                // .addHeader("Authorization", "Bearer your_token_here")
                .build();

        // 6. 发送请求并处理响应
        // 使用 try-with-resources 确保 Response 对象被正确关闭
        try (Response response = client.newCall(request).execute()) {
            // 获取响应体，同样需要处理关闭
            ResponseBody responseBody = response.body();
            String responseBodyString = (responseBody != null) ? responseBody.string() : "Response body is null";

            // 请求成功
            System.out.println("上传成功! 状态码: " + response.code());
            System.out.println("服务器响应: " + responseBodyString);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 上传截图图片到服务器（通过字节数组）
     *
     * @param baseUrl   API 的基础 URL
     * @param sessionId 会话 ID
     * @param bytes     要上传的图片文件的字节数组
     * @param filename  要上传的文件名 (例如 "screenshot.png")，用于服务器保存和判断MIME类型
     * @throws IOException 如果网络请求失败或服务器返回非成功状态码
     */
    public static void uploadScreenshot(String baseUrl, String sessionId, byte[] bytes, String filename) throws IOException {
        // 1. 根据传入的文件名确定 MIME 类型
        MediaType mediaType = MediaType.parse("application/octet-stream"); // 默认的二进制类型
        if (filename.toLowerCase().endsWith(".png")) {
            mediaType = MediaType.parse("image/png");
        } else if (filename.toLowerCase().endsWith(".jpg") || filename.toLowerCase().endsWith(".jpeg")) {
            mediaType = MediaType.parse("image/jpeg");
        }

        // 2. 构建请求体 (Request Body)
        //    使用 MultipartBody 来构建 multipart/form-data 格式的请求
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "image", // 这是 API 定义的参数名 (key)
                        filename, // 文件名
                        RequestBody.create(bytes, mediaType) // 使用字节数组创建文件内容
                )
                .build();

        // 3. 构建完整的请求 URL
        String url = baseUrl + "/api/v1/call/" + sessionId + "/img";
        System.out.println("请求 URL: " + url);

        // 4. 构建请求 (Request)
        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        // 5. 发起同步请求并处理响应
        //    使用 try-with-resources 确保 Response 对象被正确关闭
        try (Response response = client.newCall(request).execute()) {
            // 检查响应是否成功 (HTTP 状态码 200-299)
            if (!response.isSuccessful()) {
                // 打印更详细的错误信息
                String errorBody = response.body() != null ? response.body().string() : "无响应体";
                throw new IOException("请求失败: " + response.code() + " " + response.message() + ", 响应体: " + errorBody);
            }

            // 获取响应体并返回
            // 注意：response.body().string() 只能调用一次
            Log.d(TAG, "上传截图成功: " + response.body().string());
        }
    }

    /**
     * 以同步方式上报客户端健康状态.
     * <p>
     * 该方法会阻塞当前线程，直到收到服务器的响应或发生错误。
     *
     * @param baseUrl   API 的基础 URL, 例如 <a href="http://your-server.com/api/v1/client/health">...</a>
     * @param version  客户端版本号
     * @param username 使用人姓名
     */
    public static void postHealthStatusSync(String baseUrl, String version, String username) {
        // 2. 构建请求体 (Request Body)
        // Content-Type 是 application/x-www-form-urlencoded，所以使用 FormBody.
        RequestBody formBody = new FormBody.Builder()
                .add("version", version)
                .add("username", username)
                .build();

        String apiUrl = baseUrl + "/api/v1/client/health";
        // 3. 创建一个 Request 对象
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(formBody) // 指定为 POST 请求并附上请求体
                .build();

        // 4. 使用 client 发起同步请求
        // try-with-resources 语句可以确保 Response 对象在使用后被正确关闭
        try (Response response = client.newCall(request).execute()) {
            // 5. 处理响应
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            // 获取响应体，确保它不为 null
            ResponseBody responseBody = response.body();
            if (responseBody != null) {
                responseBody.string();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 以同步方式关闭一个通话会话.
     *
     * @param baseUrl   API 的基础 URL, 例如 "<a href="http://your-server.com">...</a>"
     * @param sessionId 要关闭的会话 ID
     * @return 解析后的 CloseSessionResponse 对象
     */
    public static CloseSessionVO closeCallSessionSync(String baseUrl, String sessionId){
        // 1. 构建完整的 URL，包含路径参数
        // 使用 HttpUrl.Builder 来安全地构建 URL，避免手动拼接字符串带来的错误
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(baseUrl))
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("v1")
                .addPathSegment("call")
                .addPathSegment(sessionId) // 动态添加 session_id
                .addPathSegment("close")
                .build();

        System.out.println("Requesting URL: " + url);

        // 2. 创建 Request 对象，指定为 GET 请求
        Request request = new Request.Builder()
                .url(url)
                .get() // 明确指定为 GET 请求 (默认也是 GET)
                .build();

        // 3. 发起同步请求并处理响应
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected HTTP code " + response.code() + " " + response.message());
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Received empty response body");
            }

            // 4. 使用 Gson 将 JSON 字符串解析为 Java 对象
            String jsonString = responseBody.string();
            Gson gson = new Gson();
            return gson.fromJson(jsonString, CloseSessionVO.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
