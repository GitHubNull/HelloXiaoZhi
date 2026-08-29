"""
字幕对齐工具：将下载的字幕（SRT/ASS/VTT）与切分后的音频片段对齐，
生成 real_world_cases.json 测试用例（供 Kotlin 侧 RealWorldSpeechSuite 读取）。

对齐策略：
  - 按音频片段在完整视频中的时间窗口 [start, start+duration]，
    筛选与之相交的字幕条目
  - 合并窗口内的字幕文本（去除样式标签与换行）作为参考文本
  - 字幕文本清洗：去除 ASS 样式码、HTML 标签、全角标点归一化由评分侧处理

用法（项目根目录，先激活 .venv-asr）：
    python tools/asr/subtitle_aligner.py \
        --subtitle 字幕文件.srt \
        --audio-dir app/src/test/resources/asr/audio/real_world/news \
        --case-name 新闻_样本_001 \
        --start-ms 30000 --duration-ms 10000 \
        --out app/src/test/resources/asr/test_cases/real_world_cases.json
"""

import argparse
import json
import re
from pathlib import Path
from typing import List, Tuple


def parse_time_srt(ts: str) -> int:
    h, m, rest = ts.split(":")
    s, ms = rest.split(",")
    return int(h) * 3600000 + int(m) * 60000 + int(s) * 1000 + int(ms)


def parse_time_ass(ts: str) -> int:
    h, m, rest = ts.split(":")
    s, cs = rest.split(".")
    return int(h) * 3600000 + int(m) * 60000 + int(s) * 1000 + int(cs) * 10


def parse_time_vtt(ts: str) -> int:
    h, m, rest = ts.split(":")
    s, ms = rest.split(".")
    return int(h) * 3600000 + int(m) * 60000 + int(s) * 1000 + int(ms)


def clean_text(text: str) -> str:
    """清洗字幕文本：去样式码/标签，合并换行"""
    text = re.sub(r"\{[^}]*\}", "", text)      # ASS 样式码
    text = re.sub(r"<[^>]+>", "", text)         # HTML/VTT 标签
    text = re.sub(r"\\N", "", text)             # ASS 换行符
    text = re.sub(r"\s+", " ", text).strip()
    return text


def parse_subtitle(path: Path) -> List[Tuple[int, int, str]]:
    """解析字幕文件，返回 [(start_ms, end_ms, text), ...]"""
    content = path.read_text(encoding="utf-8", errors="ignore")
    entries = []

    if "[Script Info]" in content:
        # ASS
        for line in content.splitlines():
            if not line.startswith("Dialogue:"):
                continue
            parts = line.split(",", 9)
            if len(parts) < 10:
                continue
            try:
                start = parse_time_ass(parts[1].strip())
                end = parse_time_ass(parts[2].strip())
            except ValueError:
                continue
            entries.append((start, end, clean_text(parts[9])))
    elif content.lstrip().startswith("WEBVTT"):
        # VTT
        pattern = re.compile(
            r"(\d{2}:\d{2}:\d{2}\.\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}\.\d{3})\s*\n(.+?)(?=\n\s*\n|\Z)",
            re.DOTALL,
        )
        for m in pattern.finditer(content):
            entries.append(
                (parse_time_vtt(m.group(1)), parse_time_vtt(m.group(2)), clean_text(m.group(3)))
            )
    else:
        # SRT（默认）
        pattern = re.compile(
            r"(\d{2}:\d{2}:\d{2},\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2},\d{3})\s*\n(.+?)(?=\n\s*\n|\Z)",
            re.DOTALL,
        )
        for m in pattern.finditer(content):
            entries.append(
                (parse_time_srt(m.group(1)), parse_time_srt(m.group(2)), clean_text(m.group(3)))
            )

    entries.sort(key=lambda e: e[0])
    return entries


def align(entries: List[Tuple[int, int, str]], start_ms: int, duration_ms: int) -> str:
    """提取与时间窗口相交的字幕文本（片段起点以 start_ms 为音频 0 点）"""
    end_ms = start_ms + duration_ms
    texts = [text for s, e, text in entries if e > start_ms and s < end_ms and text]
    return "".join(texts)


def main():
    parser = argparse.ArgumentParser(description="字幕对齐并生成真实音频测试用例")
    parser.add_argument("--subtitle", required=True, help="字幕文件路径（SRT/ASS/VTT）")
    parser.add_argument("--audio-file", required=True,
                        help="音频片段相对资源根目录路径（如 audio/real_world/news/xxx.wav）")
    parser.add_argument("--case-name", required=True, help="测试用例名称")
    parser.add_argument("--start-ms", type=int, required=True,
                        help="片段在完整视频时间轴上的起始毫秒")
    parser.add_argument("--duration-ms", type=int, required=True, help="片段时长毫秒")
    parser.add_argument("--tags", default="", help="标签（逗号分隔）")
    parser.add_argument("--out", required=True, help="real_world_cases.json 输出路径")
    args = parser.parse_args()

    subtitle_path = Path(args.subtitle)
    entries = parse_subtitle(subtitle_path)
    print(f"[字幕] 解析 {subtitle_path.name}：{len(entries)} 条")

    expected = align(entries, args.start_ms, args.duration_ms)
    if not expected:
        print(f"[警告] 时间窗口 [{args.start_ms}, {args.start_ms + args.duration_ms}] 内无字幕")

    case = {
        "name": args.case_name,
        "description": f"真实语音片段（来自 {subtitle_path.stem}）",
        "audio_file": args.audio_file,
        "subtitle_file": str(subtitle_path),
        "expected_text": expected,
        "start_ms": 0,          # 片段音频本身从 0 开始
        "duration_ms": args.duration_ms,
        "tags": [t for t in args.tags.split(",") if t] + ["real_world"],
    }

    out = Path(args.out)
    cases = []
    if out.exists():
        try:
            cases = json.loads(out.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, ValueError):
            cases = []
    cases = [c for c in cases if c.get("name") != case["name"]]  # 同名覆盖
    cases.append(case)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[输出] {out}（共 {len(cases)} 条）")
    print(f"[参考文本] {expected}")


if __name__ == "__main__":
    main()
