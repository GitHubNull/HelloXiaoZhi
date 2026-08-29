"""
ASR 测试音频获取与处理管道。
从开放平台下载视频，提取音频，切分为测试片段，并对齐字幕。
"""

import yt_dlp
import ffmpeg
from pydub import AudioSegment
from pathlib import Path
import json
import re
from typing import Optional, Dict


class BilibiliSessionExtractor:
    """从 B 站请求报文中提取会话信息"""
    
    @staticmethod
    def extract_from_request_file(request_file: Path) -> Dict[str, str]:
        """
        从 HTTP 请求报文文件中提取 B 站会话信息。
        
        提取关键 Cookie：
        - SESSDATA: 用户会话凭证（必需）
        - bili_jct: CSRF Token（必需）
        - DedeUserID: 用户 ID
        - buvid3: 设备标识
        
        返回：包含会话信息的字典
        """
        content = request_file.read_text(encoding='utf-8')
        
        # 提取 Cookie 行
        cookie_match = re.search(r'cookie: (.+?)(?:\n|$)', content, re.IGNORECASE)
        if not cookie_match:
            raise ValueError("未找到 Cookie 信息")
        
        cookie_str = cookie_match.group(1)
        cookies = {}
        for item in cookie_str.split('; '):
            if '=' in item:
                key, value = item.split('=', 1)
                cookies[key] = value
        
        # 验证必需字段
        required = ['SESSDATA', 'bili_jct']
        missing = [k for k in required if k not in cookies]
        if missing:
            raise ValueError(f"缺少必需的 Cookie 字段: {missing}")
        
        return {
            'SESSDATA': cookies['SESSDATA'],
            'bili_jct': cookies['bili_jct'],
            'DedeUserID': cookies.get('DedeUserID', ''),
            'buvid3': cookies.get('buvid3', ''),
        }
    
    @staticmethod
    def save_session(session: Dict[str, str], output_file: Path):
        """保存会话信息到 JSON 文件"""
        output_file.write_text(json.dumps(session, indent=2), encoding='utf-8')
    
    @staticmethod
    def load_session(session_file: Path) -> Dict[str, str]:
        """从 JSON 文件加载会话信息"""
        return json.loads(session_file.read_text(encoding='utf-8'))


class AudioPipeline:
    """音频下载与处理管道"""
    
    def __init__(
        self,
        output_dir: str = "app/src/test/resources/asr/audio",
        session_file: Optional[Path] = None
    ):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.session = None
        if session_file and session_file.exists():
            self.session = BilibiliSessionExtractor.load_session(session_file)
    
    def set_session(self, session: Dict[str, str]):
        """设置 B 站会话信息"""
        self.session = session
    
    def download_video(self, url: str, video_id: str) -> Path:
        """
        下载视频（仅音频流）。
        
        支持平台：B 站、YouTube、喜马拉雅等
        推荐内容类型：访谈、播客、演讲、新闻播报（语音清晰、有字幕）
        
        对于 B 站视频，需要先设置会话信息（set_session）
        """
        ydl_opts = {
            'format': 'bestaudio/best',
            'outtmpl': str(self.output_dir / f'{video_id}.%(ext)s'),
            'postprocessors': [{
                'key': 'FFmpegExtractAudio',
                'preferredcodec': 'wav',
                'preferredquality': '192',
            }],
        }
        
        # 如果是 B 站视频且已设置会话，添加 Cookie
        if self.session and 'bilibili.com' in url:
            cookie_str = '; '.join([f'{k}={v}' for k, v in self.session.items()])
            ydl_opts['http_headers'] = {
                'Cookie': cookie_str,
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Referer': 'https://www.bilibili.com',
            }
        
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
        return self.output_dir / f'{video_id}.wav'
    
    def extract_audio_segment(
        self,
        audio_file: Path,
        start_ms: int,
        duration_ms: int,
        output_name: str
    ) -> Path:
        """从音频文件中提取指定片段"""
        audio = AudioSegment.from_wav(str(audio_file))
        segment = audio[start_ms:start_ms + duration_ms]
        
        # 转换为 16kHz 单声道 16bit（符合小智协议标准）
        segment = segment.set_frame_rate(16000).set_channels(1).set_sample_width(2)
        
        output_path = self.output_dir / f'{output_name}.wav'
        segment.export(str(output_path), format='wav')
        return output_path
    
    def parse_subtitle(self, subtitle_file: Path) -> list:
        """
        解析字幕文件（SRT/ASS 格式）。
        返回：[(start_ms, end_ms, text), ...]
        """
        subtitles = []
        content = subtitle_file.read_text(encoding='utf-8')
        
        # SRT 格式解析
        srt_pattern = r'(\d+)\n(\d{2}:\d{2}:\d{2},\d{3}) --> (\d{2}:\d{2}:\d{2},\d{3})\n(.+?)(?=\n\d+\n|\Z)'
        matches = re.findall(srt_pattern, content, re.DOTALL)
        
        for match in matches:
            start_str, end_str, text = match[1], match[2], match[3]
            start_ms = self._time_to_ms(start_str)
            end_ms = self._time_to_ms(end_str)
            # 清理字幕文本（去除 HTML 标签、多余空白）
            text = re.sub(r'<[^>]+>', '', text).strip()
            subtitles.append((start_ms, end_ms, text))
        
        return subtitles
    
    def _time_to_ms(self, time_str: str) -> int:
        """将 SRT 时间格式转换为毫秒"""
        h, m, s_ms = time_str.split(':')
        s, ms = s_ms.split(',')
        return int(h) * 3600000 + int(m) * 60000 + int(s) * 1000 + int(ms)
    
    def create_test_case(
        self,
        audio_segment: Path,
        subtitle_text: str,
        case_name: str,
        tags: list = None
    ) -> dict:
        """
        创建测试用例元数据。
        返回：测试用例 JSON 配置
        """
        return {
            'name': case_name,
            'audio_file': str(audio_segment.relative_to(self.output_dir)),
            'expected_text': subtitle_text,
            'duration_ms': len(AudioSegment.from_wav(str(audio_segment))),
            'tags': tags or [],
            'source': 'real_world'
        }


# 使用示例
if __name__ == '__main__':
    # 示例 1：从请求报文提取会话信息
    # extractor = BilibiliSessionExtractor()
    # session = extractor.extract_from_request_file(
    #     Path('tmp/B站请求报文文件/request1.txt')
    # )
    # extractor.save_session(session, Path('tools/asr/bilibili_session.json'))
    
    # 示例 2：使用会话信息下载视频
    # pipeline = AudioPipeline(session_file=Path('tools/asr/bilibili_session.json'))
    # audio_file = pipeline.download_video(
    #     'https://www.bilibili.com/video/BV1VYtw6iEuq',
    #     'interview_001'
    # )
    
    # 示例 3：提取音频片段
    # segment = pipeline.extract_audio_segment(
    #     audio_file, start_ms=60000, duration_ms=10000,
    #     output_name='interview_001_segment_1'
    # )
    
    # 示例 4：解析字幕并创建测试用例
    # subtitles = pipeline.parse_subtitle(Path('subtitle.srt'))
    # test_case = pipeline.create_test_case(
    #     segment, subtitles[0][2], 'interview_001_seg1',
    #     tags=['interview', 'real_world']
    # )
    pass
