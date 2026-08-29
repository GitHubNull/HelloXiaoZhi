# ASR 测试工具使用说明

简体中文语音识别（ASR）测试与评估体系的配套工具链：
真实音频获取（B 站等开放平台）、字幕对齐、合成音频生成。

评估框架本体位于 `app/src/test/java/org/oxff/helloxiaozhi/asr/`（纯 JVM 单元测试，不依赖设备）：

| 命令 | 说明 |
| --- | --- |
| `gradlew asrTest` | 运行全部 ASR 评估测试（含真实音频），生成报告到 `app/build/asr-reports/` |
| `gradlew asrFetchResources` | 下载/更新真实音频测试资源（需先搭建下方 Python 环境） |
| `gradlew testDebugUnitTest` | 全量单元测试（ASR 评估 + 既有业务测试） |

## 环境搭建

### 1. 创建 Python 虚拟环境

```bash
# 使用 uv 创建虚拟环境
uv venv .venv-asr

# 激活虚拟环境
# Windows:
.venv-asr\Scripts\activate
# Linux/Mac:
source .venv-asr/bin/activate

# 安装依赖
uv pip install -r tools/asr/requirements.txt
```

### 2. 提取 B 站会话信息

B 站视频下载需要用户会话信息（Cookie）。请按照以下步骤操作：

1. 打开浏览器，登录 B 站账号
2. 打开开发者工具（F12），切换到 Network 标签
3. 播放任意视频，找到任意请求
4. 右键请求，选择 "Copy" -> "Copy as cURL" 或 "Copy as fetch"
5. 将复制的内容保存到文件（如 `tmp/B站请求报文文件/request1.txt`）
6. 运行提取工具：

```bash
python tools/asr/extract_session.py \
    --request-file tmp/B站请求报文文件/request1.txt \
    --output tools/asr/bilibili_session.json
```

**安全提示**：
- `bilibili_session.json` 包含敏感凭证，请勿提交到版本控制系统
- 建议将 `bilibili_session.json` 添加到 `.gitignore`

## 音频下载与处理（推荐：一键脚本）

`fetch_real_world_resources.py` 串联了完整流程：下载音频 → 转 16kHz 单声道 WAV → 切分片段 → 字幕对齐生成用例：

```bash
.venv-asr\Scripts\python.exe tools/asr/fetch_real_world_resources.py ^
    --url "https://www.bilibili.com/video/BVxxxxxxxx" ^
    --video-id interview_001 ^
    --category interview ^
    --segments "32200:8000:访谈_片段01;608400:8000:访谈_片段02"
```

- 会话凭证自动从 `tools/asr/bilibili_session.json` 加载（转为 Netscape cookies.txt 传入，登录态生效）
- 片段写入 `app/src/test/resources/asr/audio/real_world/<分类>/`
- 用例清单写入 `app/src/test/resources/asr/test_cases/real_world_cases.json`（Kotlin 侧 RealWorldSpeechSuite 自动加载）
- 不指定 `--segments` 时，自动选取前 3 段有字幕覆盖的 8 秒窗口（无字幕时必须显式指定）
- 完成后清理中间文件：删除 `_downloads/` 与 `*_full.wav`（体积大且含敏感 cookies）

### B 站 AI 字幕获取（yt-dlp 拿不到字幕时使用）

B 站的 `ai-zh` AI 字幕有时不被 yt-dlp 识别，可用官方 player API 直接拉取并转 SRT：

```bash
.venv-asr\Scripts\python.exe tools/asr/fetch_bilibili_subtitle.py ^
    --aid 117172965739809 --cid 41365210067 ^
    --out app\src\test\resources\asr\audio\real_world\interview\interview_001.srt
```

aid/cid 可从视频页面源码或下载日志中获取。拿到 SRT 后用 `subtitle_aligner.py` 重新对齐参考文本：

```bash
.venv-asr\Scripts\python.exe tools/asr/subtitle_aligner.py ^
    --subtitle 字幕文件.srt ^
    --audio-file "audio/real_world/interview/interview_001_访谈_片段01.wav" ^
    --case-name "访谈_片段01" ^
    --start-ms 32200 --duration-ms 8000 ^
    --tags interview,interview_001 ^
    --out "app/src/test/resources/asr/test_cases/real_world_cases.json"
```

### 合成音频生成（无需网络）

`generate_synthetic_assets.py` 生成合成测试 WAV（标准/句首轻声等）与 `synthetic_cases.json`，
与 Kotlin 侧 `ChineseSpeechSynthesizer` 口径一致，供无网络环境复现：

```bash
.venv-asr\Scripts\python.exe tools/asr/generate_synthetic_assets.py
```

## 噪声建模与幻觉词复现（空调外机等背景噪声）

针对实体机常见的“没人说话却识别出‘嗯’‘对’”问题，`NoisyAudioSource` 内置
**空调外机噪声模型**（`NoiseType.AC_OUTDOOR`）：

- 风机/压缩机哼声：50Hz 基频 + 100Hz 谐波（低频为主的连续哼鸣）
- 气流宽带噪声：一阶低通白噪声（截止约 300Hz）
- 压缩机启停包络：8 秒周期（5 秒运行 / 3 秒衰减到 25%），切换带 320ms 斜坡，
  避免超出真实物理特征的电平突变；相位与滤波状态跨帧连续，可确定性复现

`NoiseRobustnessSuite` 内置 4 个空调外机场景：

| 场景 | 电平 | 验证目标 |
| --- | --- | --- |
| 语音叠加 0.05 / 0.10 | 噪声低于语音 | 噪声背景下识别链路完整性 |
| 纯噪声_不应触发 | 0.03（RMS≈ 0.005） | 客户端 VAD 门控：噪声不上行则永远不会产生幻觉词 |
| 强电平_误触发幻觉词 | 0.15（RMS≈ 0.024） | 复现误触发链路：状态机开门上行纯噪声 → 服务器回吐幻觉词 |

**幻觉词注入机制**：场景标签 `hallucinationStt:<词>`（如 `hallucinationStt:嗯`）会让
`MockAsrServer` 在指纹未命中时回吐该词，模拟真实 ASR 引擎把背景噪声误识别为短词；
若客户端门控有效（未触发上行），该词永远不会被收到——低分 + `NOISE_SENSITIVITY`
瓶颈诊断即为风险量化结果（评分是评估输出，不会阻断构建）。

**接入真实空调噪声录音**（可选，比合成更贴近现场）：

1. 在 B 站搜索“空调外机 噪音/录音”等关键词，找到纯噪声视频（无人说话）；
2. 用一键脚本下载并切出 8–10 秒片段（分类建议用 `ambient`）：
   ```bash
   .venv-asr\Scripts\python.exe tools/asr/fetch_real_world_resources.py ^
       --url "https://www.bilibili.com/video/BVxxxxxxxx" ^
       --video-id ambient_001 --category ambient --segments "0:8000:空调外机实测"
   ```
3. 在 `NoiseRobustnessSuite` 中把某个场景的输入换成 `realWorldInput(...)` 即可，
   其余评分/幻觉词机制不变。

## 高级用法：audio_pipeline.py（编程式管道）

### 下载视频

```python
from tools.asr.audio_pipeline import AudioPipeline
from pathlib import Path

# 创建管道（使用会话信息）
pipeline = AudioPipeline(session_file=Path('tools/asr/bilibili_session.json'))

# 下载 B 站视频
audio_file = pipeline.download_video(
    'https://www.bilibili.com/video/BV1VYtw6iEuq',
    'interview_001'
)
```

### 提取音频片段

```python
# 提取 10 秒片段（从第 60 秒开始）
segment = pipeline.extract_audio_segment(
    audio_file,
    start_ms=60000,
    duration_ms=10000,
    output_name='interview_001_segment_1'
)
```

### 解析字幕

```python
# 解析 SRT 字幕文件
subtitles = pipeline.parse_subtitle(Path('subtitle.srt'))

# subtitles 格式：[(start_ms, end_ms, text), ...]
for start_ms, end_ms, text in subtitles:
    print(f"{start_ms}ms - {end_ms}ms: {text}")
```

### 创建测试用例

```python
# 创建测试用例配置
test_case = pipeline.create_test_case(
    segment,
    subtitles[0][2],  # 使用第一条字幕的文本
    'interview_001_seg1',
    tags=['interview', 'real_world']
)

# 保存测试用例
import json
with open('app/src/test/resources/asr/test_cases/real_world_cases.json', 'w') as f:
    json.dump([test_case], f, indent=2, ensure_ascii=False)
```

## 推荐测试内容

| 平台 | 内容类型 | 推荐原因 | 字幕可用性 |
|------|----------|----------|------------|
| B 站 | 访谈节目（如《圆桌派》） | 对话自然、语速适中 | 多数有 CC 字幕 |
| B 站 | 播客/电台节目 | 语音清晰、背景安静 | 部分有字幕 |
| B 站 | 新闻播报 | 标准普通话、发音清晰 | 通常有字幕 |
| YouTube | 中文播客 | 内容多样、质量高 | 自动生成字幕可用 |
| 喜马拉雅 | 有声书/广播剧 | 专业配音、音质好 | 部分有文本 |

## 内容选择标准

1. **语音清晰度**：发音标准、背景噪声低
2. **字幕可用性**：有准确的时间轴字幕（SRT/ASS 格式）
3. **内容长度**：单片段 5-30 秒，适合测试
4. **语速适中**：约 3-5 字/秒，符合正常对话
5. **多样性**：覆盖不同说话人、口音、话题

## 常见问题

### Q: 下载视频时提示 "Sign in to confirm you're not a bot"？

A: 这是因为 B 站检测到异常访问。请确保：
1. 已正确提取并设置会话信息（SESSDATA）
2. 会话信息未过期
3. 不要频繁下载，避免触发风控

### Q: 下载的音频没有声音？

A: 请检查：
1. 视频本身是否有音频流
2. 是否选择了正确的音频格式（bestaudio）
3. 使用 ffprobe 检查音频文件：`ffprobe audio_file.wav`

### Q: 字幕解析失败？

A: 目前仅支持 SRT 格式。如果是 ASS/VTT 格式，请先转换为 SRT：
```bash
ffmpeg -i subtitle.ass subtitle.srt
```

## 评分与基线说明

- **评分是评估输出，不是通过门槛**：`asrTest` 不会因低分失败，只验证执行链路完整性；
  评分结果见 `app/build/asr-reports/asr_report.html`（中文表格）与 `asr_summary.txt`（瓶颈诊断）
- **真实音频基线**：已入库的 `访谈_片段01/02`（来自 BV1VYtw6iEuq，AI 字幕对齐）构成首个基线；
  每次运行自动归档到 `asr-reports/history/`，`AsrReportHistory` 提供与基线的趋势对比（回归检测）
- **瓶颈诊断**：`BottleneckAnalyzer` 按规则识别阈值不当/噪声敏感/句首丢失/网络延迟/增益异常等瓶颈，
  给出对应业务常量（如 `THRESHOLD_SPEAKING`）的调整建议；合成音频走原始 PCM（JVM 无 Opus JNI），
  因此 `OPUS_QUALITY_DEGRADATION` 类诊断仅提示真机验证方向，非本框架缺陷

## 目录结构

```
tools/asr/
├── fetch_real_world_resources.py  # 一键下载/切分/对齐真实音频资源（推荐入口）
├── fetch_bilibili_subtitle.py     # B 站 AI 字幕获取（player API → SRT）
├── subtitle_aligner.py            # 字幕对齐，生成/更新 real_world_cases.json
├── generate_synthetic_assets.py   # 合成音频与用例生成（离线可复现）
├── audio_pipeline.py              # 音频下载与处理管道（编程式）
├── extract_session.py             # B 站会话信息提取工具（从请求报文提取）
├── bilibili_session.json          # B 站会话信息（敏感，不入库）
├── requirements.txt               # Python 依赖
└── README.md                      # 本文件
```
