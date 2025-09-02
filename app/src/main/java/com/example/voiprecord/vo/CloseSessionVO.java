package com.example.voiprecord.vo;
import androidx.annotation.NonNull;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

/**
 * 用于映射 /api/v1/call/{session_id}/close 接口响应的 Java 数据类.
 */
public class CloseSessionVO {
    @SerializedName("message")
    private String message;

    @SerializedName("session_id")
    private String sessionId;

    @SerializedName("total_audio_chunks")
    private int totalAudioChunks;

    @SerializedName("channel_chunks")
    private Map<String, Integer> channelChunks; // 使用 Map 来表示 ch1, ch2 等动态键

    @SerializedName("images_received")
    private int imagesReceived;

    @SerializedName("session_duration")
    private String sessionDuration;

    // --- Getters and Setters ---
    // (为了简洁，此处省略，但在实际应用中建议添加)

    @NonNull
    @Override
    public String toString() {
        return "CloseSessionResponse{" +
                "message='" + message + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", totalAudioChunks=" + totalAudioChunks +
                ", channelChunks=" + channelChunks +
                ", imagesReceived=" + imagesReceived +
                ", sessionDuration='" + sessionDuration + '\'' +
                '}';
    }

    // 你可以添加 Getter 方法来访问私有字段
    public String getMessage() { return message; }
    public String getSessionId() { return sessionId; }
    public int getTotalAudioChunks() { return totalAudioChunks; }
    public Map<String, Integer> getChannelChunks() { return channelChunks; }
    public int getImagesReceived() { return imagesReceived; }
    public String getSessionDuration() { return sessionDuration; }
}
