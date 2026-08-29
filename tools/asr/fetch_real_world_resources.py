"""
真实音频测试资源下载与更新工具（ASR 测试评估体系 · 资源管理）。

功能：
  1. 从 tools/asr/bilibili_session.json 加载 B 站会话（由 extract_session.py 生成）
  2. 用 yt-dlp 下载开放平台视频（仅音频流 + 平台字幕）
  3. 用 ffmpeg 转为 16kHz / 单声道 / 16bit WAV（小智协议标准格式）
  4. 按字幕对齐切分测试片段，生成 real_world_cases.json
     （供 Kotlin 侧 RealWorldSpeechSuite 自动加载）

用法（项目根目录）：
    # 使用 uv 虚拟环境
    .venv-asr\\Scripts\\python.exe tools/asr/fetch_real_world_resources.py \
        --url "https://www.bilibili.com/video/BVxxxxxxxx" \
        --video-id interview_001 \
        --category interview \
        --segments "60000:8000:访谈_片段1;90000:8000:访谈_片段2"

参数说明：
  --url         视频地址（B 站 / YouTube 等 yt-dlp 支持的平台）
  --video-id    视频标识（用作输出文件名前缀）
  --category    分类目录：interview / podcast / news / conversation
  --segments    切分清单，格式 "起始毫秒:时长毫秒:用例名"，分号分隔；
                省略时自动取前 3 段有字幕覆盖的 8 秒窗口
  --session     B 站会话 JSON 路径（默认 tools/asr/bilibili_session.json）

说明：
  - 推荐选择访谈/播客/新闻类视频：语音清晰、有官方字幕便于自动评分
  - 下载产物写入 app/src/test/resources/asr/audio/real_world/<category>/
  - 用例清单写入 app/src/test/resources/asr/test_cases/real_world_cases.json
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

try:
    import yt_dlp
except ImportError:
    print("[错误] 缺少依赖：请先执行 `uv sync` 或安装 requirements.txt 中的依赖")
    sys.exit(1)

ROOT = Path(__file__).resolve().parents[2]
RESOURCE_ROOT = ROOT / "app" / "src" / "test" / "resources" / "asr"
AUDIO_ROOT = RESOURCE_ROOT / "audio" / "real_world"
CASES_FILE = RESOURCE_ROOT / "test_cases" / "real_world_cases.json"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from subtitle_aligner import parse_subtitle, align  # noqa: E402


def load_session(session_file: Path):
    if not session_file.exists():
        return None
    try:
        return json.loads(session_file.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, ValueError):
        return None


def write_cookies_txt(session, cookies_file: Path):
    """将会话 JSON 转为 Netscape cookies.txt（yt-dlp cookiefile 格式，登录态生效）"""
    lines = ["# Netscape HTTP Cookie File"]
    for name, value in session.items():
        # domain, includeSubdomains, path, secure, expiry, name, value
        lines.append(f".bilibili.com\tTRUE\t/\tFALSE\t0\t{name}\t{value}")
    cookies_file.write_text("\n".join(lines) + "\n", encoding="utf-8")


def download(url: str, video_id: str, session, workdir: Path):
    """下载音频与字幕，返回 (音频文件, 字幕文件或 None)"""
    ydl_opts = {
        "format": "bestaudio/best",
        "outtmpl": str(workdir / f"{video_id}.%(ext)s"),
        "writesubtitles": True,
        "writeautomaticsub": True,
        "subtitlelangs": ["zh-CN", "zh", "zh-Hans", "ai-zh"],
        "postprocessors": [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": "wav",
        }],
        "quiet": False,
        "noprogress": True,
    }
    if session and "bilibili.com" in url:
        cookies_file = workdir / "cookies.txt"
        write_cookies_txt(session, cookies_file)
        ydl_opts["cookiefile"] = str(cookies_file)
        ydl_opts["http_headers"] = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer": "https://www.bilibili.com",
        }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        ydl.download([url])

    audio = workdir / f"{video_id}.wav"
    subtitle = next(
        (p for p in sorted(workdir.glob(f"{video_id}*"))
         if p.suffix.lower() in (".srt", ".ass", ".vtt", ".json3")),
        None,
    )
    return audio, subtitle


def to_16k_mono(src: Path, dst: Path):
    """ffmpeg 转 16kHz 单声道 16bit WAV"""
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("[错误] 未找到 ffmpeg，无法转换音频格式")
        sys.exit(1)
    subprocess.run(
        [ffmpeg, "-y", "-i", str(src), "-ar", "16000", "-ac", "1", "-sample_fmt", "s16", str(dst)],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def auto_segments(entries, count=3, window_ms=8000, gap_ms=2000):
    """无显式切分清单时，自动选取有字幕覆盖的时间窗口"""
    segments = []
    cursor = 0
    for start, end, text in entries:
        if not text.strip():
            continue
        if start < cursor:
            continue
        segments.append((start, window_ms, f"真实语音_片段{len(segments) + 1:02d}"))
        cursor = start + window_ms + gap_ms
        if len(segments) >= count:
            break
    return segments


def main():
    parser = argparse.ArgumentParser(description="下载真实音频测试资源并生成测试用例")
    parser.add_argument("--url", required=True)
    parser.add_argument("--video-id", required=True)
    parser.add_argument("--category", default="interview",
                        choices=["interview", "podcast", "news", "conversation"])
    parser.add_argument("--segments", default="", help="起始毫秒:时长毫秒:用例名，分号分隔")
    parser.add_argument("--session", default=str(ROOT / "tools" / "asr" / "bilibili_session.json"))
    args = parser.parse_args()

    session = load_session(Path(args.session))
    if "bilibili.com" in args.url and not session:
        print("[警告] 未找到 B 站会话文件，将以游客身份下载（可能受限）")

    category_dir = AUDIO_ROOT / args.category
    category_dir.mkdir(parents=True, exist_ok=True)
    workdir = AUDIO_ROOT / "_downloads"
    workdir.mkdir(parents=True, exist_ok=True)

    print(f"[1/4] 下载视频音频与字幕: {args.url}")
    raw_audio, subtitle_file = download(args.url, args.video_id, session, workdir)
    if not raw_audio.exists():
        print(f"[错误] 音频文件未生成: {raw_audio}")
        sys.exit(1)

    print(f"[2/4] 转换为 16kHz 单声道 WAV")
    full_wav = category_dir / f"{args.video_id}_full.wav"
    to_16k_mono(raw_audio, full_wav)

    entries = []
    if subtitle_file:
        entries = parse_subtitle(subtitle_file)
        print(f"[3/4] 字幕解析: {subtitle_file.name}（{len(entries)} 条）")
        shutil.copy(subtitle_file, category_dir / f"{args.video_id}{subtitle_file.suffix}")
    else:
        print("[3/4] 未获取到字幕：片段参考文本将为空（需手工补充后才能自动评分）")

    if args.segments:
        seg_list = []
        for item in args.segments.split(";"):
            start_ms, duration_ms, name = item.split(":", 2)
            seg_list.append((int(start_ms), int(duration_ms), name.strip()))
    else:
        seg_list = auto_segments(entries)
        if not seg_list:
            print("[错误] 无字幕且未指定 --segments，无法切分")
            sys.exit(1)

    print(f"[4/4] 切分 {len(seg_list)} 个片段并生成用例")
    cases = []
    if CASES_FILE.exists():
        try:
            cases = json.loads(CASES_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, ValueError):
            cases = []

    for start_ms, duration_ms, name in seg_list:
        seg_wav = category_dir / f"{args.video_id}_{re.sub(r'[^\w\u4e00-\u9fff-]', '_', name)}.wav"
        subprocess.run(
            [str(shutil.which("ffmpeg")), "-y", "-i", str(full_wav),
             "-ss", f"{start_ms / 1000:.3f}", "-t", f"{duration_ms / 1000:.3f}",
             "-ar", "16000", "-ac", "1", "-sample_fmt", "s16", str(seg_wav)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        expected = align(entries, start_ms, duration_ms) if entries else ""
        rel_audio = seg_wav.relative_to(RESOURCE_ROOT).as_posix()
        cases = [c for c in cases if c.get("name") != name]
        cases.append({
            "name": name,
            "description": f"真实语音片段（{args.category}，来自 {args.video_id}）",
            "audio_file": rel_audio,
            "subtitle_file": str(subtitle_file) if subtitle_file else "",
            "expected_text": expected,
            "start_ms": 0,
            "duration_ms": duration_ms,
            "tags": [args.category, args.video_id],
        })
        print(f"  - {name}: {rel_audio} | 参考文本: {expected[:40]}{'…' if len(expected) > 40 else ''}")

    CASES_FILE.parent.mkdir(parents=True, exist_ok=True)
    CASES_FILE.write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[完成] 用例清单: {CASES_FILE}（共 {len(cases)} 条）")
    print("[提示] 运行 `gradlew asrTest` 执行含真实音频的完整评估")


if __name__ == "__main__":
    main()
