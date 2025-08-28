import socket
import threading
import numpy as np
import pyaudio
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation
from collections import deque
import struct
import time
import queue

# 服务器配置
SERVER_HOST = '0.0.0.0'
UPLINK_PORT = 8001   # 上行音频端口
DOWNLINK_PORT = 8002  # 下行音频端口
SAMPLE_RATE = 16000
CHANNELS = 1
FORMAT = pyaudio.paInt16
CHUNK = 4096  # 每次处理的音频块大小

# 音频播放设置
uplink_audio_queue = queue.Queue(maxsize=20)  # 上行播放队列
downlink_audio_queue = queue.Queue(maxsize=20)  # 下行播放队列
current_play_source = "downlink"  # 默认播放下行音频
MAX_WAVEFORM_POINTS = 2000  # 波形图显示的点数
current_username = "Unknown User"  # 当前用户名

class AudioStreamServer:
    def __init__(self):
        self.fig, (self.ax1, self.ax2) = plt.subplots(2, 1, figsize=(10, 8))
        self.setup_plots()
        self.uplink_data = deque(maxlen=MAX_WAVEFORM_POINTS)
        self.downlink_data = deque(maxlen=MAX_WAVEFORM_POINTS)
        self.animation = FuncAnimation(self.fig, self.update_plot, interval=50, blit=True)
        self.running = True
        
        # 创建PyAudio实例
        self.p = pyaudio.PyAudio()
        self.stream = self.p.open(
            format=FORMAT,
            channels=CHANNELS,
            rate=SAMPLE_RATE,
            output=True,
            frames_per_buffer=CHUNK,
            start=False
        )
        
        # 绑定键盘事件
        self.fig.canvas.mpl_connect('key_press_event', self.on_key_press)
        
        # 启动服务器线程
        threading.Thread(target=self.start_server, args=(UPLINK_PORT, "uplink"), daemon=True).start()
        threading.Thread(target=self.start_server, args=(DOWNLINK_PORT, "downlink"), daemon=True).start()
        threading.Thread(target=self.audio_playback, daemon=True).start()
        threading.Thread(target=self.console_info, daemon=True).start()

    def setup_plots(self):
        """初始化波形图"""
        self.ax1.set_title(f'Uplink Audio Waveform ({current_username})')
        self.ax1.set_ylim(-32768, 32767)
        self.ax1.set_xlim(0, MAX_WAVEFORM_POINTS)
        self.line1, = self.ax1.plot([], [], 'b-', lw=1)
        
        self.ax2.set_title(f'Downlink Audio Waveform ({current_username})')
        self.ax2.set_ylim(-32768, 32767)
        self.ax2.set_xlim(0, MAX_WAVEFORM_POINTS)
        self.line2, = self.ax2.plot([], [], 'r-', lw=1)
        
        # 添加播放源指示文本
        self.source_text = self.fig.text(
            0.02, 0.02, 
            f"Playing: {current_play_source.upper()} (Press U/D to switch)",
            fontsize=12
        )
        
        plt.tight_layout()

    def update_plot(self, frame):
        """更新波形图"""
        self.line1.set_data(range(len(self.uplink_data)), self.uplink_data)
        self.line2.set_data(range(len(self.downlink_data)), self.downlink_data)
        return self.line1, self.line2

    def start_server(self, port, stream_type):
        """启动TCP服务器接收音频流"""
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind((SERVER_HOST, port))
            s.listen()
            print(f"Listening for {stream_type} on {SERVER_HOST}:{port}")
            
            while self.running:
                conn, addr = s.accept()
                print(f"Connected by {addr} for {stream_type}")
                threading.Thread(
                    target=self.handle_client,
                    args=(conn, stream_type),
                    daemon=True
                ).start()

    def handle_client(self, conn, stream_type):
        """处理客户端连接"""
        global current_username
        
        try:
            # 首先接收用户名长度 (4字节)
            username_len_data = conn.recv(4)
            if not username_len_data:
                return
                
            username_len = struct.unpack('>I', username_len_data)[0]
            
            # 接收用户名
            username_data = conn.recv(username_len)
            if not username_data:
                return
                
            username = username_data.decode('utf-8')
            current_username = username
            print(f"Received username: {username}")
            
            # 更新图表标题
            self.ax1.set_title(f'Uplink Audio Waveform ({username})')
            self.ax2.set_title(f'Downlink Audio Waveform ({username})')
            
            # 接收音频数据
            while self.running:
                data = conn.recv(CHUNK)
                if not data:
                    break
                
                # 将二进制数据转换为16位整数
                audio_data = np.frombuffer(data, dtype=np.int16)
                
                # 更新波形数据
                if stream_type == "uplink":
                    self.uplink_data.extend(audio_data)
                    # 将上行音频放入队列
                    try:
                        uplink_audio_queue.put_nowait(data)
                    except queue.Full:
                        pass
                else:  # downlink
                    self.downlink_data.extend(audio_data)
                    # 将下行音频放入队列
                    try:
                        downlink_audio_queue.put_nowait(data)
                    except queue.Full:
                        pass
                        
        except Exception as e:
            print(f"Error handling {stream_type} client: {e}")
        finally:
            conn.close()
            print(f"{stream_type} connection closed")

    def audio_playback(self):
        """音频播放线程"""
        self.stream.start_stream()
        print("Audio playback started")
        print("Press 'U' to play uplink, 'D' to play downlink")
        
        while self.running:
            try:
                # 根据当前播放源选择队列
                if current_play_source == "uplink":
                    data = uplink_audio_queue.get(timeout=1.0)
                else:
                    data = downlink_audio_queue.get(timeout=1.0)
                    
                self.stream.write(data)
            except queue.Empty:
                continue
            except Exception as e:
                print(f"Playback error: {e}")
    
    def on_key_press(self, event):
        """处理键盘事件"""
        global current_play_source
        
        if event.key == 'u' or event.key == 'U':
            current_play_source = "uplink"
            print(f"Switched to uplink audio")
        elif event.key == 'd' or event.key == 'D':
            current_play_source = "downlink"
            print(f"Switched to downlink audio")
            
        # 更新播放源指示
        self.source_text.set_text(
            f"Playing: {current_play_source.upper()} (Press U/D to switch)"
        )
        plt.draw()

    def console_info(self):
        """控制台信息输出"""
        while self.running:
            time.sleep(5)
            print(f"User: {current_username} | "
                  f"Playing: {current_play_source} | "
                  f"Uplink points: {len(self.uplink_data)} | "
                  f"Downlink points: {len(self.downlink_data)} | "
                  f"Uplink queue: {uplink_audio_queue.qsize()} | "
                  f"Downlink queue: {downlink_audio_queue.qsize()}")

    def run(self):
        """启动服务"""
        try:
            plt.show()
        except KeyboardInterrupt:
            self.running = False
            self.stream.stop_stream()
            self.stream.close()
            self.p.terminate()
            print("Server stopped")

if __name__ == "__main__":
    server = AudioStreamServer()
    server.run()