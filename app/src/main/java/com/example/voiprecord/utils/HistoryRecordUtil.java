package com.example.voiprecord.utils;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import android.os.Environment;
import android.util.Log;

import com.example.voiprecord.MainActivity;
import com.example.voiprecord.damain.FileRecordHistory;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class HistoryRecordUtil {

    public static void deleteFile() {
        // 这里的逻辑和方案一中的 checkAndCleanVoipFolder 方法完全一样
        Thread deleteRecordFile = new Thread(() -> {
            while (true) {

                try {
                    // 这里的逻辑和方案一中的 checkAndCleanVoipFolder 方法完全一样
                    File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!directory.exists()) {
                        return;
                    }

                    FileFilter pcmFileFilter = file -> file.isFile() && file.getName().endsWith(".wav");
                    File[] files = directory.listFiles(pcmFileFilter);
                    if (files == null || files.length == 0) {
                        return;
                    }

                    List<File> wavFiles = new ArrayList<>();
                    long currentTotalSize = 0;
                    for (File file : files) {
                        currentTotalSize += file.length();
                        wavFiles.add(file);
                    }

                    if (currentTotalSize >= MainActivity.MAX_FOLDER_SIZE_BYTES) {
                        wavFiles.sort((a, b) -> {
                            Integer numa = Integer.parseInt(a.getName().split("=")[0]);
                            Integer numb = Integer.parseInt(b.getName().split("=")[0]);
                            return numa.compareTo(numb);
                        });
                        Log.d(TAG, "缓存的音频: " + wavFiles);
                        while (currentTotalSize >= MainActivity.MAX_FOLDER_SIZE_BYTES && !wavFiles.isEmpty()) {
                            File oldestFile = wavFiles.get(0);
                            long oldestFileSize = oldestFile.length();
                            if (oldestFile.delete()) {
                                currentTotalSize -= oldestFileSize;
                                wavFiles.remove(0);
                            } else {
                                Log.e(TAG, "Failed to delete file: " + oldestFile.getAbsolutePath());
                            }
                        }
                    }
                    Log.d(TAG, "清理任务执行完毕。");
                } catch (Exception e) {
                    Log.e(TAG, "清理任务发生错误", e);
                }

                try {
                    Thread.sleep(MainActivity.CLEAN_MEMORY_FREQUENCY);
                } catch (InterruptedException e) {
                    Log.e(TAG, "清理任务被中断", e);
                }
            }
        });
        deleteRecordFile.start();
    }

    /**
     * 扫描Download目录，获取所有符合命名规则的音频文件，并将其转换为FileRecordHistory对象列表。
     * @return FileRecordHistory对象的列表，如果目录不存在或没有符合的文件，则返回空列表。
     */
    public static List<FileRecordHistory> getAllFileRecords() {
        List<FileRecordHistory> records = new ArrayList<>();

        // 1. 获取外部存储的Download目录
        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

        // 检查目录是否存在且可读
        if (!directory.exists() || !directory.isDirectory()) {
            Log.e(TAG, "Download directory does not exist or is not a directory.");
            return records; // 返回空列表
        }

        // 2. 创建一个文件过滤器，只筛选出符合命名规则的.wav文件
        // 规则: 文件名必须包含 "_voip_up_" 并且以 ".wav" 结尾
        FileFilter voipFileFilter = file -> file.isFile() &&
                file.getName().contains("_voip_up_") &&
                file.getName().endsWith(".wav");

        File[] files = directory.listFiles(voipFileFilter);

        // 检查文件列表是否为空
        if (files == null || files.length == 0) {
            Log.d(TAG, "No matching .wav files found.");
            return records; // 返回空列表
        }
        List<String> fileNames =  Arrays.stream(files).map(File::getName).collect(Collectors.toList());
        String[] split = fileNames.get(0).split("=");
        fileNames.sort((a, b) -> {
            Long numa = Long.parseLong(a.split("=")[0]);
            Long numb = Long.parseLong(a.split("=")[0]);
            return numb.compareTo(numa);
        });
        fileNames = fileNames.subList(0, Math.min(fileNames.size(), 50));
        // 3. 遍历文件数组，解析文件名并创建对象
        for (String fileName : fileNames) {
            // 使用 try-catch 块来防止因文件名格式不匹配导致的崩溃
            try {
                // 文件名格式: timestamp=count=_voip_up_=direction=username=SessionId=.wav
                String[] parts = fileName.split("=");

                // 检查分割后的部分数量是否足够，防止数组越界
                if (parts.length >= 5) {
                    // 创建实体类对象
                    FileRecordHistory record = new FileRecordHistory();

                    // 根据文件名格式填充对象属性
                    record.setTimestamp(parts[0]);
                    record.setCount(parts[1]);
                    // parts[2] 是固定的 "_voip_up_"，我们跳过
                    record.setDirection(parts[3]);
                    record.setUsername(parts[4]);

                    // sessionId 在文件名中没有提供，所以保持为 null 或设置默认值
                    record.setSessionId(parts[5]);

                    // 将创建的对象添加到列表中
                    records.add(record);
                } else {
                    Log.w(TAG, "Skipping file with incorrect format: " + fileName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing file name: " + fileName, e);
            }
        }

        Log.d(TAG, "Found and parsed " + records.size() + " file records.");
        return records;
    }
}
