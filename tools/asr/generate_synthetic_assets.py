"""
合成测试音频素材生成器。

生成预录测试音频（16kHz / 16bit / 单声道 WAV）与测试用例配置，
供 Kotlin 侧 WavFileAudioSource / SubtitleParser 使用。

生成的音频为「电平包络贴近真实中文语音」的模拟信号（有声调起伏、
句首轻声、字间停顿），与 Kotlin 侧 ChineseSpeechSynthesizer 口径一致。

用法（项目根目录，先激活 .venv-asr）：
    python tools/asr/generate_synthetic_assets.py
"""

import json
import math
import struct
from pathlib import Path

SAMPLE_RATE = 16000
CHAR_MS = 200          # 单字时长
PAUSE_MS = 60          # 字间停顿
TAIL_SILENCE_MS = 1500  # 句末静音（触发状态机静音计时）
BASE_AMPLITUDE = 0.35

# 轻声字集合（对齐 Kotlin 侧）
NEUTRAL_CHARS = set("吗呢吧的了啊么")

RESOURCES = Path("app/src/test/resources/asr")

# 预定义用例：(文件名, 文本, 句首轻声强度, 输出子目录, 标签)
CASES = [
    ("standard_001_nihao", "你好", 0.8, "standard", ["basic"]),
    ("standard_002_tianqi", "今天天气怎么样", 1.0, "standard", ["basic"]),
    ("standard_003_question", "我想问一下附近有什么好吃的", 1.0, "standard", ["basic", "long"]),
    ("softness_001_nihao_040", "你好", 0.4, "chinese_softness", ["softness"]),
    ("softness_002_jintian_035", "今天天气怎么样", 0.35, "chinese_softness", ["softness"]),
    ("softness_003_woshuo_030", "我说的对吗", 0.3, "chinese_softness", ["softness"]),
]


def tone_of(char: str) -> int:
    """按字符哈希稳定映射声调（0-3 为四声，4 为轻声）"""
    if char in NEUTRAL_CHARS:
        return 4
    return ord(char) % 5


def freq_factor(tone: int, progress: float) -> float:
    if tone == 0:
        return 1.3
    if tone == 1:
        return 1.0 + 0.4 * progress
    if tone == 2:
        return 0.9 - 0.2 * math.sin(math.pi * progress)
    if tone == 3:
        return 1.4 - 0.5 * progress
    return 0.8


def synth_speech(text: str, initial_softness: float = 1.0) -> list:
    """合成模拟语音，返回 16bit PCM 采样列表"""
    chars = [c for c in text if not c.isspace()]
    char_samples = CHAR_MS * SAMPLE_RATE // 1000
    pause_samples = PAUSE_MS * SAMPLE_RATE // 1000
    tail_samples = TAIL_SILENCE_MS * SAMPLE_RATE // 1000

    total = len(chars) * char_samples + (len(chars) - 1) * pause_samples + tail_samples
    pcm = [0] * total

    pos = 0
    phase = 0.0
    f0 = 150
    for index, c in enumerate(chars):
        tone = tone_of(c)
        softness = initial_softness if index == 0 else 1.0
        amplitude = BASE_AMPLITUDE * softness * (0.5 if tone == 4 else 1.0)

        for i in range(char_samples):
            progress = i / char_samples
            attack_decay = max(0.0, min(1.0, progress / 0.1, (1 - progress) / 0.1))
            freq = f0 * freq_factor(tone, progress)
            phase += 2 * math.pi * freq / SAMPLE_RATE
            sample = amplitude * attack_decay * math.sin(phase)
            pcm[pos + i] = int(max(-1.0, min(1.0, sample)) * 32767)
        pos += char_samples
        if index < len(chars) - 1:
            pos += pause_samples
    return pcm


def save_wav(pcm: list, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = struct.pack("<%dh" % len(pcm), *pcm)
    header = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, SAMPLE_RATE,
                                   SAMPLE_RATE * 2, 2, 16)
    header += b"data" + struct.pack("<I", len(data))
    path.write_bytes(header + data)


def main():
    cases = []
    for name, text, softness, subdir, tags in CASES:
        pcm = synth_speech(text, softness)
        rel = f"audio/{subdir}/{name}.wav"
        save_wav(pcm, RESOURCES / rel)
        duration_ms = len(pcm) * 1000 // SAMPLE_RATE
        cases.append({
            "name": name,
            "description": f"合成语音「{text}」（句首轻声强度 {softness}）",
            "audio_file": rel,
            "expected_text": text,
            "duration_ms": duration_ms,
            "tags": tags + ["synthetic"],
            "source": "synthetic",
        })
        print(f"[生成] {rel} ({duration_ms}ms, 文本「{text}」)")

    out = RESOURCES / "test_cases" / "synthetic_cases.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[生成] {out}（{len(cases)} 条用例）")


if __name__ == "__main__":
    main()
