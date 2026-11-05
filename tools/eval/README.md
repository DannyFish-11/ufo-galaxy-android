# 手机端测

设计见 V2 仓 `docs/DEVICE_SELF_DESCRIPTION_DESIGN.md` 6.3。手机本地循环是「截图 → 端侧视觉模型规划 → 定位 → 无障碍执行」，现在用的是 MAI-UI-2B（Q4_K_M 的 GGUF，App 自己拉起 `llama-server`）。`LocalLoopScenarioRunner` 全是替身，测的是流程，测不到模型做得对不对；这里测模型。

评测方法参考面壁的 AgentCPM-GUI（[OpenBMB/AgentCPM-GUI](https://github.com/OpenBMB/AgentCPM-GUI) 的 `eval/` 目录；中文 App 基准 CAGUI，[论文](https://arxiv.org/abs/2506.01391)）。**判定细则照抄他们的代码**（`Judge.kt` 开头写明出处，Apache-2.0），这样我们的数字能直接和公开数字比。

| 层 | 测什么 | 数据 | 指标 | 在哪跑 |
|---|---|---|---|---|
| L1 定位 | 截图 + 一句描述（按功能描述，或按界面上的文字）→ 点在哪 | CAGUI 定位部分：Fun2Point（`cap.jsonl`）、Text2Point（`ocr.jsonl`） | 点落在目标控件框里的比例 | 电脑，这里 |
| L2 单步动作 | 指令 + 当前截图 → 下一步动作 | CAGUI 智能体部分（中文 App 的多步任务） | TM（动作类型对）、EM（类型和参数都对），各类型分开记；任务成功率、进度 | 电脑，这里 |
| L3 端到端 | 真机上从一句话做到底 | 20 个左右中文日常任务 | 成功率、步数、卡住次数、做不了时有没有照实停下 | 真机，见 V2 `docs/REAL_DEVICE_CHECKLIST.md` |
| 端侧开销 | 同一批用例在真机上跑 | 按机型记 | 每步时延、模型加载时间、峰值内存、30 分钟耗电和温度 | 真机 |

## 跑的是 App 自己的链路

用 JVM 跑 App **自己的** `VlmPlanner` / `VlmGroundingEngine`：同一套提示词和解析，连电脑上的 `llama-server`，载同一份 GGUF。构建时从 `app/` 源码里原样拷进这几个纯 Kotlin 文件编译（`build.gradle.kts` 里列着），不另写一份评测代码。

截图也按 App 的做法压：
- 定位：长边压到 720（App 的 `EdgeExecutor` 默认），回来的点换算回原图再判；
- 规划：长边由 `VisionContextBudget` 从服务端上下文窗口（4096）反推，装不下就不送图。

L2 和面壁的评测一样：每一步只给指令和当前截图，不给前几步。规划器出一份计划，取第一步；tap、scroll 再交给定位引擎定点，和 App 的 `EdgeExecutor` 一样。

## 动作格式换算

我们的格式是 `action_type` + `parameters`，面壁的是紧凑 JSON（`POINT`、`to`、`TYPE`、`PRESS`、`STATUS`，坐标按 0–1000 归一）。评测里加一层换算（`Convert.kt`），不改 App：

| 我们 | 面壁 | 说明 |
|---|---|---|
| `tap` + 定位的点 | `{"POINT":[x,y]}` | 定位失败按 (0,0) 记，和他们解析坐标失败时一样：类型算对、位置不对 |
| `scroll` + `direction` | `{"POINT":[..],"to":"up"}` | 我们的方向说内容往哪走，他们说手指往哪划：down ↔ up，up ↔ down，左右不变（见 App 的 `HardwareKeyListener.dispatchScroll`） |
| `type` + `text` | `{"TYPE":"..."}` | 没给 `text` 时用 intent，App 执行时也是这样退回的 |
| `back` / `home` | `{"PRESS":"BACK"}` / `HOME` | |
| `finish` | `{"STATUS":"finish"}` | |
| `open_app` | 没有 | 他们靠点桌面图标打开应用；我们这一步在 L2 里判不上，照实记 |

每一步的预测另写一份他们的格式（`all.jsonl`），可以拿他们的 `run_eval_agent.py` 再判一遍对照。

## 准备

1. **模型**：App 下载的同一份 MAI-UI-2B GGUF（Q4_K_M）和 mmproj。
2. **起服务**（llama.cpp）：

   ```bash
   llama-server -m MAI-UI-2B.Q4_K_M.gguf --mmproj mmproj-f16.gguf -c 4096 --port 8080
   ```

3. **数据**：`bash tools/eval/get_data.sh <放数据的目录>`（要 `git` 和 `huggingface-cli`）。它取：
   - 定位的标注（`cap.jsonl`、`ocr.jsonl`）：在 AgentCPM-GUI 仓的 `eval/grounding_eval/dataset/code/`；
   - 定位的截图和智能体部分：Hugging Face 上的 `openbmb/CAGUI`。

## 跑

```bash
# L1:按功能描述 / 按界面文字
./gradlew -p tools/eval run --args="l1 --data <数据>/AgentCPM-GUI/eval/grounding_eval/dataset/code/cap.jsonl --root <数据>/AgentCPM-GUI/eval --out <结果目录>"
./gradlew -p tools/eval run --args="l1 --data <数据>/AgentCPM-GUI/eval/grounding_eval/dataset/code/ocr.jsonl --root <数据>/AgentCPM-GUI/eval --out <结果目录>"

# L2:中文 App 多步任务的单步动作
./gradlew -p tools/eval run --args="l2 --data <数据>/chinese_app_test/test --out <结果目录>"
```

- 加 `--limit 50` 先跑一小批看看。`--endpoint` 换服务地址。
- 结果目录里有每一条的预测（`l1_*.jsonl`、`all.jsonl`）和汇总（`*_summary.json`）。
- 跑完把汇总记进 V2 仓 `docs/eval/phone_gui.md`：日期、模型（含量化）、各项指标、和上一轮比。

## 选端侧模型

同一套数据也用来选端侧模型：MAI-UI-2B、面壁更小的端侧多模态模型，以及将来的候选。
- 用我们提示词的模型：直接换 GGUF 再跑。
- 用面壁动作空间（紧凑 JSON）的模型：`Convert.fromCompact` 按他们的 `action_map` 读，判定同一套。

## 我们自己的轨迹

L2 还要接从我们手机本地循环录下来的轨迹。App 的循环记录（`LocalLoopTrace`）按设计不存截图，所以录轨迹要另开一个入口：在真机上让人做一遍（或挑模型做对了的），存成和 CAGUI 一样的格式（`<子集>/<任务>/<任务>.json` 加 `<任务>_<步>.jpeg`），放进数据目录就能跑。这个录制入口还没做。

## 本仓 CI

`./gradlew -p tools/eval test` 只跑评测脚本自己的单测：
- 判定细则：照他们的代码一步步算出的期望值；
- 动作换算；
- 对着一个 llama-server 替身跑 L1、L2 整条链。

真模型的评测在电脑上手动跑，不进 CI。
