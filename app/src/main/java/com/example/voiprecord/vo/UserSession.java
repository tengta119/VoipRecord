package com.example.voiprecord.vo;
import com.google.gson.annotations.SerializedName;
public class UserSession {
    // @SerializedName 注解告诉 Gson JSON 中的 "session_id" 字段
    // 应该映射到这个名为 sessionId 的 Java 字段上。
    @SerializedName("session_id")
    private String sessionId;

    @SerializedName("audio_chunk_size")
    private int audioChunkSize;

    @SerializedName("image_frequency")
    private int imageFrequency;

    // --- Getters and Setters (可选但推荐) ---
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getAudioChunkSize() {
        return audioChunkSize;
    }

    public void setAudioChunkSize(int audioChunkSize) {
        this.audioChunkSize = audioChunkSize;
    }

    public int getImageFrequency() {
        return imageFrequency;
    }

    public void setImageFrequency(int imageFrequency) {
        this.imageFrequency = imageFrequency;
    }

    // --- 重写 toString() 方法以便于打印和调试 ---
    @Override
    public String toString() {
        return "ApiResponse{" +
                "sessionId='" + sessionId + '\'' +
                ", audioChunkSize=" + audioChunkSize +
                ", imageFrequency=" + imageFrequency +
                '}';
    }


}
