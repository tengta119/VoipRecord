package com.example.voiprecord;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.voiprecord.damain.FileRecordHistory;
import com.example.voiprecord.utils.HistoryRecordUtil;

import java.util.List;

public class HistoryRecord extends AppCompatActivity {

    private RecyclerView recyclerView;
    private RecordAdapter recordAdapter;
    private TextView textViewEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_record);

        // 初始化视图
        textViewEmpty = findViewById(R.id.textViewEmpty);
        recyclerView = findViewById(R.id.recyclerViewRecords);
        ImageButton refreshButton = findViewById(R.id.btnRefresh);

        // 设置 RecyclerView
        setupRecyclerView();

        // 设置刷新按钮的点击事件
        refreshButton.setOnClickListener(v -> loadAndDisplayRecords());

        // 页面首次加载时获取数据
        loadAndDisplayRecords();
    }

    private void setupRecyclerView() {
        recordAdapter = new RecordAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this)); // 设置布局管理器
        recyclerView.setAdapter(recordAdapter); // 设置适配器
    }

    private void loadAndDisplayRecords() {
        Toast.makeText(this, "正在加载记录...", Toast.LENGTH_SHORT).show();

        // 调用之前创建的方法获取数据
        List<FileRecordHistory> records = HistoryRecordUtil.getAllFileRecords();

        // 检查数据是否为空
        if (records.isEmpty()) {
            recyclerView.setVisibility(View.GONE); // 隐藏列表
            textViewEmpty.setVisibility(View.VISIBLE); // 显示“没有记录”
        } else {
            recyclerView.setVisibility(View.VISIBLE); // 显示列表
            textViewEmpty.setVisibility(View.GONE); // 隐藏“没有记录”
            // 将数据设置到 Adapter 中
            recordAdapter.setData(records);
        }
    }
}