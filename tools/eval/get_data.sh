#!/usr/bin/env bash
# 手机端测的数据(见 tools/eval/README.md):
#   - 定位的标注:AgentCPM-GUI 仓 eval/grounding_eval/dataset/code/{cap,ocr}.jsonl;
#   - 定位的截图与智能体部分:Hugging Face 上的 openbmb/CAGUI。
# 用法:bash tools/eval/get_data.sh <放数据的目录>
set -euo pipefail

DEST="${1:?用法:bash tools/eval/get_data.sh <放数据的目录>}"
mkdir -p "$DEST"
cd "$DEST"

if [ ! -d AgentCPM-GUI ]; then
  git clone --depth 1 https://github.com/OpenBMB/AgentCPM-GUI
fi

command -v huggingface-cli >/dev/null || { echo "要 huggingface-cli:pip install -U huggingface_hub" >&2; exit 1; }

# 智能体部分(L2):和面壁 eval_data/readme.md 一样放成 chinese_app_test/test/<子集>/<任务>/
mkdir -p chinese_app_test
huggingface-cli download openbmb/CAGUI --repo-type dataset --include "CAGUI_agent/**" \
  --local-dir chinese_app_test --local-dir-use-symlinks False --resume-download
[ -d chinese_app_test/CAGUI_agent ] && mv chinese_app_test/CAGUI_agent chinese_app_test/test

# 定位部分(L1)的截图:放到标注里写的 grounding_eval/dataset/images/ 下
huggingface-cli download openbmb/CAGUI --repo-type dataset --include "CAGUI_grounding/**" \
  --local-dir cagui_grounding --local-dir-use-symlinks False --resume-download
echo "定位截图在 $DEST/cagui_grounding;按 cap.jsonl / ocr.jsonl 里的 image 路径放进 $DEST/AgentCPM-GUI/eval/grounding_eval/dataset/images/"
echo "(以数据集页面的目录结构为准,对不上时看 AgentCPM-GUI 仓 eval/grounding_eval/README.md)"
