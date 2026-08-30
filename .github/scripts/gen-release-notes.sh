#!/usr/bin/env bash
# 从 Git 提交历史生成 Markdown 更新日志（按 Conventional Commits 前缀中文分组）。
# 用法：gen-release-notes.sh <当前标签>
#   - 取「上一个标签..当前标签」范围内的提交；无上一个标签时输出全量历史
#   - 未匹配 Conventional Commits 前缀的提交归入「其他」
#   - 文末附 GitHub Full Changelog 对比链接
set -euo pipefail

CURRENT_TAG="${1:-}"
if [[ -z "$CURRENT_TAG" ]]; then
  CURRENT_TAG="$(git describe --tags --abbrev=0 2>/dev/null || echo HEAD)"
fi

# 上一个标签（排除当前标签自身）；若无则回溯到首个提交
PREV_TAG="$(git describe --tags --abbrev=0 "${CURRENT_TAG}^" 2>/dev/null || true)"
if [[ -n "$PREV_TAG" ]]; then
  RANGE="${PREV_TAG}..${CURRENT_TAG}"
  COMPARE_URL_LINE=""
  if [[ -n "${GITHUB_REPOSITORY:-}" ]]; then
    COMPARE_URL_LINE="**完整变更**: https://github.com/${GITHUB_REPOSITORY}/compare/${PREV_TAG}...${CURRENT_TAG}"
  fi
else
  RANGE="${CURRENT_TAG}"   # 首个标签：输出全部历史
  COMPARE_URL_LINE=""
fi

# 未传标签（workflow_dispatch 兜底）：取最近 20 条
if [[ "$CURRENT_TAG" == "HEAD" ]]; then
  RANGE="-20"
fi

declare -A TYPE_GROUPS=(
  [feat]="### 新功能"
  [fix]="### Bug 修复"
  [perf]="### 性能优化"
  [refactor]="### 重构"
  [docs]="### 文档"
  [test]="### 测试"
  [build]="### 构建"
  [ci]="### CI"
  [chore]="### 杂项"
  [style]="### 样式"
  [revert]="### 回滚"
)
ORDER=(feat fix perf refactor docs test build ci chore style revert)

# 读入提交：格式 "<short-sha>|<subject>"
LINES=()
mapfile -t LINES < <(git log --no-merges --pretty=format:'%h|%s' "$RANGE")

declare -A BUCKETS
OTHERS=()

for line in "${LINES[@]}"; do
  [[ -z "$line" ]] && continue
  sha="${line%%|*}"
  msg="${line#*|}"
  # 解析前缀：type(scope)?!: desc（正则存入变量，避免内联转义问题）
  RE='^([a-zA-Z]+)(\([^)]+\))?!?: (.+)$'
  if [[ "$msg" =~ $RE ]]; then
    type="${BASH_REMATCH[1],,}"
    scope="${BASH_REMATCH[2]}"
    desc="${BASH_REMATCH[3]}"
    item="- ${scope:+**${scope//[()]/}**: }${desc} (${sha})"
    if [[ -n "${TYPE_GROUPS[$type]:-}" ]]; then
      BUCKETS[$type]+="${item}"$'\n'
    else
      OTHERS+=("- ${msg} (${sha})")
    fi
  else
    OTHERS+=("- ${msg} (${sha})")
  fi
done

{
  for t in "${ORDER[@]}"; do
    if [[ -n "${BUCKETS[$t]:-}" ]]; then
      echo "${TYPE_GROUPS[$t]}"
      echo
      echo -n "${BUCKETS[$t]}"
      echo
    fi
  done
  if (( ${#OTHERS[@]} > 0 )); then
    echo "### 其他"
    echo
    printf '%s\n' "${OTHERS[@]}"
    echo
  fi
  if [[ -n "$COMPARE_URL_LINE" ]]; then
    echo "$COMPARE_URL_LINE"
  fi
}
