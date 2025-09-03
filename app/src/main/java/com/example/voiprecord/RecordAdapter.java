package com.example.voiprecord;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.voiprecord.damain.FileRecordHistory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.RecordViewHolder> {

    private List<FileRecordHistory> recordList = new ArrayList<>();

    // 用于更新 Adapter 中的数据
    public void setData(List<FileRecordHistory> records) {
        this.recordList.clear();
        if (records != null) {
            this.recordList.addAll(records);
        }
        notifyDataSetChanged(); // 通知 RecyclerView 数据已改变，请刷新
    }

    @NonNull
    @Override
    public RecordViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 加载 list_item_record.xml 布局
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_record, parent, false);
        return new RecordViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordViewHolder holder, int position) {
        // 获取当前位置的数据项
        FileRecordHistory currentRecord = recordList.get(position);
        // 将数据绑定到 ViewHolder 的视图上
        holder.bind(currentRecord);
    }

    @Override
    public int getItemCount() {
        return recordList.size();
    }

    // ViewHolder 内部类，持有每一行视图中的控件
    class RecordViewHolder extends RecyclerView.ViewHolder {
        TextView tvUsername;
        TextView tvTimestamp;
        TextView tvDirection;
        TextView tvSessionId;

        public RecordViewHolder(@NonNull View itemView) {
            super(itemView);
            tvUsername = itemView.findViewById(R.id.tvUsername);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvDirection = itemView.findViewById(R.id.tvDirection);
            // 如果没有绑定，软件会崩溃
            tvSessionId = itemView.findViewById(R.id.tvSessionId);
        }

        // 绑定数据的方法
        public void bind(FileRecordHistory record) {
            tvUsername.setText(record.getUsername());
            tvTimestamp.setText(record.getTimestamp()); // 格式化时间戳
            tvDirection.setText("Direction: " + record.getDirection());
            tvSessionId.setText("SessionId: " + record.getSessionId());
        }

        // 一个辅助方法，将时间戳字符串转换为可读日期
        private String formatTimestamp(String timestampStr) {
            try {
                long timestamp = Long.parseLong(timestampStr); // 假设时间戳是秒，转换为毫秒
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                return sdf.format(new Date(timestamp));
            } catch (NumberFormatException e) {
                return timestampStr; // 如果格式化失败，返回原始字符串
            }
        }
    }
}