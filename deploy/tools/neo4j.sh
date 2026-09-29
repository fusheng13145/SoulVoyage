#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# SoulVoyage · Neo4j 本地开发辅助脚本
#
# 背景（2026-09-29 实测）：官方 bin/neo4j.bat 与 neo4j-admin.bat 均以
#   Powershell -File bin/*.ps1 包装，在本机 PowerShell 5.1 下抛
#   "已添加了具有相同键的项"（Neo4j-Management 模块 hashtable 键冲突），
#   两条命令皆不可用；cypher-shell.bat 为纯 bat 包装，不受影响。
#   故启动与建钥一律绕过官方脚本，直接调 Java 入口（已验证可用）。
#
# 用法：
#   deploy/tools/neo4j.sh start     # 前台启动（Ctrl-C 停止）；日志 logs/neo4j.log
#   deploy/tools/neo4j.sh seed      # 导入 deploy/neo4j/seed.cypher（幂等，可重复跑）
#   deploy/tools/neo4j.sh cy       # 打开 cypher-shell 交互终端
#   deploy/tools/neo4j.sh status    # 查看端口与节点计数
#   deploy/tools/neo4j.sh setpass   # 首次建钥（须在首次启动前执行）
#
# 环境变量：NEO4J_HOME（默认 D:/neo4j-community-5.26.12）
#           SV_NEO4J_PASS（密码；setpass 之外的操作需要）
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

NEO4J_HOME="${NEO4J_HOME:-D:/neo4j-community-5.26.12}"
export NEO4J_HOME   # AdminTool 要求该变量存在（--home-dir 仅启动入口认）
NEO4J_URI="${SV_NEO4J_URI:-bolt://127.0.0.1:7687}"
NEO4J_USER="${SV_NEO4J_USER:-neo4j}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SEED_SRC="$PROJECT_ROOT/deploy/neo4j/seed.cypher"
SEED_DST="$NEO4J_HOME/import/seed.cypher"   # 规避 cypher-shell 读中文路径

CS="$NEO4J_HOME/bin/cypher-shell.bat"

die() { echo "ERROR: $*" >&2; exit 1; }

require_home() {
  [ -d "$NEO4J_HOME" ] || die "NEO4J_HOME 不存在: $NEO4J_HOME"
  [ -f "$NEO4J_HOME/lib/neo4j-command-line-5.26.12.jar" ] || \
    echo "WARN: 未找到预期 jar，NEO4J_HOME 版本可能与脚本假设不一致" >&2
}

require_pass() {
  [ -n "${SV_NEO4J_PASS:-}" ] || die "请先导出 SV_NEO4J_PASS（见 deploy/local.env）"
}

cmd_start() {
  require_home
  echo "启动 Neo4j（NEO4J_HOME=$NEO4J_HOME）…"
  # 5.26 的 CommunityEntryPoint 要求 --home-dir；官方脚本即在此处补参
  cd "$NEO4J_HOME" || die "无法进入 $NEO4J_HOME"
  exec java -cp "lib/*" -Dfile.encoding=UTF-8 \
       org.neo4j.server.CommunityEntryPoint --home-dir "$NEO4J_HOME"
}

cmd_setpass() {
  require_home
  require_pass
  cd "$NEO4J_HOME" || die "无法进入 $NEO4J_HOME"
  java -cp "lib/*" -Dfile.encoding=UTF-8 \
       org.neo4j.cli.AdminTool dbms set-initial-password "$SV_NEO4J_PASS"
}

cmd_seed() {
  require_home
  require_pass
  [ -f "$SEED_SRC" ] || die "种子文件不存在: $SEED_SRC"
  cp "$SEED_SRC" "$SEED_DST"
  echo "导入 $(basename "$SEED_SRC")（$(wc -l < "$SEED_DST") 行）…"
  cmd //c "$(cygpath -w "$CS") -a $NEO4J_URI -u $NEO4J_USER -p $SV_NEO4J_PASS -f $(cygpath -w "$SEED_DST")"
  echo "导入完成，当前图谱："
  cmd_status_query
}

cmd_cy() {
  require_pass
  cmd //c "$(cygpath -w "$CS") -a $NEO4J_URI -u $NEO4J_USER -p $SV_NEO4J_PASS" 
}

cmd_status_query() {
  cmd //c "$(cygpath -w "$CS") -a $NEO4J_URI -u $NEO4J_USER -p $SV_NEO4J_PASS \"MATCH (n) RETURN labels(n)[0] AS label, count(*) AS cnt ORDER BY cnt DESC\""
}

cmd_status() {
  require_home
  echo "== 端口 =="
  netstat -ano 2>/dev/null | grep -E ":(7687|7474)\s.*LISTEN" || echo "（未监听：服务未启动）"
  if [ -n "${SV_NEO4J_PASS:-}" ]; then
    echo "== 图谱计数 =="
    cmd_status_query
  else
    echo "（未设 SV_NEO4J_PASS，跳过计数查询）"
  fi
}

case "${1:-}" in
  start)   cmd_start ;;
  setpass) cmd_setpass ;;
  seed)    cmd_seed ;;
  cy)      cmd_cy ;;
  status)  cmd_status ;;
  *) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 1 ;;
esac
