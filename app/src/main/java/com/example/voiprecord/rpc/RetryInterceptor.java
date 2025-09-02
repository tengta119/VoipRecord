package com.example.voiprecord.rpc;

import androidx.annotation.NonNull;
import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;
import android.util.Log;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class RetryInterceptor implements Interceptor {


    private final int maxRetries;
    private int retryCount = 0;

    public RetryInterceptor(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    @NonNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = null;
        IOException lastException = null;

        // 循环进行重试
        while (retryCount < maxRetries) {
            try {
                // 尝试执行请求
                response = chain.proceed(request);
                // 如果请求成功 (HTTP 状态码 2xx)，直接返回响应
                if (response.isSuccessful()) {
                    return response;
                }
            } catch (IOException e) {
                // 捕获到 IO 异常，这是最常见的需要重试的场景（例如网络中断）
                lastException = e;
                Log.w(TAG, "请求失败 (IOException), 正在进行重试... (" + (retryCount + 1) + "/" + maxRetries + ")");
            } finally {
                // 注意：如果响应不成功（例如 500 服务器错误），也应该关闭它
                // 但在这里我们主要关注 IO 异常。如果 response 不为 null 但不成功，
                // 我们会在循环外返回它，让调用者处理。
                if (response != null && !response.isSuccessful()) {
                    response.close(); // 关闭不成功的响应体，避免资源泄露
                }
            }

            retryCount++;

            // 如果还可以重试，就等待一小段时间
            if (retryCount < maxRetries) {
                try {
                    // 简单的延迟策略：每次重试多等待 1 秒
                    Thread.sleep(2000L * retryCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("重试被中断", e);
                }
            }
        }

        // 如果所有重试都失败了，抛出最后一次的异常
        if (lastException != null) {
            throw lastException;
        }

        // 如果是因为非 IO 异常（如 5xx 错误）导致循环结束，返回最后的 response
        // 但在这里我们的逻辑是只要不成功就重试，所以这里实际上不会执行。
        // 为了健壮性，返回最后的 response 对象，即使它不成功。
        // 但在上面的逻辑中，我们已经处理并返回了成功的 response，所以这里可以简化为抛出异常。
        throw new IOException("请求在 " + maxRetries + " 次重试后仍然失败");
    }

}
