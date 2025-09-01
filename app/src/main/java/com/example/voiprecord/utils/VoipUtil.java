package com.example.voiprecord.utils;


import com.example.voiprecord.VoipRecordService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class VoipUtil {

    public static byte[] createWavHeader(long pcmDataSize) throws IOException {
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

        return header.array();
    }

}
