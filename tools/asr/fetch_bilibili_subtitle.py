"""
B 站 AI 字幕获取工具：通过 player API 拉取视频官方/AI 字幕（JSON），
转为 SRT 保存到资源目录，供 subtitle_aligner / fetch_real_world_resources 对齐使用。

背景：yt-dlp 对 B 站 ai-zh 字幕的识别不稳定，直接走官方 player API 更可靠。

用法（项目根目录）：
    .venv-asr\\Scripts\\python.exe tools/asr/fetch_bilibili_subtitle.py \
        --aid 117172965739809 --cid 41365210067 \
        --out app/src/test/resources/asr/audio/real_world/interview/interview_001.srt
"""

import argparse
import json
import sys
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
SESSION_FILE = ROOT / "tools" / "asr" / "bilibili_session.json"

HEADERS_BASE = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://www.bilibili.com",
}


def get_json(url: str, cookie: str):
    headers = dict(HEADERS_BASE)
    if cookie:
        headers["Cookie"] = cookie
    if url.startswith("//"):
        url = "https:" + url
    req = Request(url, headers=headers)
    return json.loads(urlopen(req, timeout=30).read().decode("utf-8"))


def ms_to_srt_ts(ms: int) -> str:
    h, ms = divmod(ms, 3600000)
    m, ms = divmod(ms, 60000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def json_to_srt(body: list) -> str:
    lines = []
    for i, item in enumerate(body):
        start = int(item["from"] * 1000)
        end = int(item["to"] * 1000)
        if end <= start:
            end = start + 1000
        lines.append(f"{i + 1}\n{ms_to_srt_ts(start)} --> {ms_to_srt_ts(end)}\n{item['content']}\n")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="获取 B 站视频 AI 字幕并转 SRT")
    parser.add_argument("--aid", required=True, help="视频 aid")
    parser.add_argument("--cid", required=True, help="分 P cid")
    parser.add_argument("--lan", default="ai-zh", help="字幕语言（默认 ai-zh）")
    parser.add_argument("--out", required=True, help="SRT 输出路径")
    parser.add_argument("--session", default=str(SESSION_FILE))
    args = parser.parse_args()

    cookie = ""
    session_path = Path(args.session)
    if session_path.exists():
        session = json.loads(session_path.read_text(encoding="utf-8"))
        cookie = "; ".join(f"{k}={v}" for k, v in session.items())

    player = get_json(
        f"https://api.bilibili.com/x/player/wbi/v2?aid={args.aid}&cid={args.cid}", cookie
    )
    subtitles = (player.get("data") or {}).get("subtitle", {}).get("subtitles", [])
    if not subtitles:
        print("[错误] 该视频没有可用字幕（需要登录态或视频无字幕）")
        sys.exit(1)

    target = next((s for s in subtitles if s["lan"] == args.lan), subtitles[0])
    print(f"[字幕] 选择 {target['lan']}（{target.get('lan_doc', '')}）")

    body = get_json(target["subtitle_url"], cookie).get("body", [])
    if not body:
        print("[错误] 字幕内容为空")
        sys.exit(1)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json_to_srt(body), encoding="utf-8")
    print(f"[完成] {out}（{len(body)} 条）")


if __name__ == "__main__":
    main()
