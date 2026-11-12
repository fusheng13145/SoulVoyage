// C 类禁止改动守卫：读取 docs/baseline/c-modules.txt，扫描工作树 / 暂存区 / 未跟踪变更
// 用法：node deploy/tools/check_c_guard.mjs [--allow <path>]...
// 退出码：0 = PASS；1 = BLOCKED（命中 C 类路径）；2 = 脚本/环境错误
// 只读脚本：仅调用 git 查询命令，不修改任何文件。
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';

const LIST = 'docs/baseline/c-modules.txt';

// ---- 1) 参数与清单 ----
const allow = [];
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--allow') {
    const v = argv[++i];
    if (!v) {
      console.error('用法错误：--allow 需要跟随一个路径');
      process.exit(2);
    }
    allow.push(normalize(v));
  } else if (argv[i] === '-h' || argv[i] === '--help') {
    console.log('用法：node deploy/tools/check_c_guard.mjs [--allow <path>]...');
    console.log('读取 docs/baseline/c-modules.txt，检测变更是否命中 C 类禁止改动模块。');
    process.exit(0);
  } else {
    console.error(`未知参数：${argv[i]}`);
    process.exit(2);
  }
}

if (!existsSync(LIST)) {
  console.error(`找不到 C 类清单：${LIST}`);
  process.exit(2);
}

const patterns = readFileSync(LIST, 'utf8')
  .split(/\r?\n/)
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith('#'))
  .map(normalize);

if (patterns.length === 0) {
  console.error(`C 类清单为空：${LIST}`);
  process.exit(2);
}

// ---- 2) 采集变更路径 ----
function gitZ(args) {
  const out = execFileSync('git', args, { encoding: 'utf8' });
  return out.split('\0').filter(Boolean);
}

function gitPorcelain() {
  const entries = gitZ(['status', '--porcelain', '-z']);
  const paths = [];
  for (let i = 0; i < entries.length; i++) {
    const e = entries[i];
    if (e.length < 4) continue;
    const x = e[0];
    paths.push(e.slice(3));
    // 重命名/复制：XY <new>\0<old>
    if (x === 'R' || x === 'C') {
      const old = entries[++i];
      if (old) paths.push(old);
    }
  }
  return paths;
}

let changed;
try {
  changed = [
    ...gitZ(['diff', '--name-only', '-z']),
    ...gitZ(['diff', '--cached', '--name-only', '-z']),
    ...gitPorcelain(),
  ].map(normalize);
} catch (err) {
  console.error(`git 查询失败：${err.message}`);
  process.exit(2);
}

const unique = [...new Set(changed)].sort();

// ---- 3) 匹配 ----
function normalize(p) {
  return p.replace(/\\/g, '/').replace(/^\.\//, '').trim();
}

function hit(file, pattern) {
  const dir = pattern.endsWith('/');
  const base = dir ? pattern.slice(0, -1) : pattern;
  return dir ? file === base || file.startsWith(base + '/') : file === base;
}

function allowed(file) {
  return allow.some((p) => hit(file, p));
}

const hits = [];
const waived = [];
for (const file of unique) {
  const pattern = patterns.find((p) => hit(file, p));
  if (!pattern) continue;
  (allowed(file) ? waived : hits).push({ file, pattern });
}

// ---- 4) 输出 ----
if (waived.length) {
  console.warn('WARN: 以下 C 类改动已通过 --allow 豁免（请确认已在专项批准单中登记）：');
  for (const { file, pattern } of waived) console.warn(`  - ${file}  (匹配 ${pattern})`);
}

if (hits.length) {
  console.error(`BLOCKED: 检测到 ${hits.length} 个 C 类禁止改动文件：`);
  for (const { file, pattern } of hits) console.error(`  - ${file}  (匹配 ${pattern})`);
  console.error('处理方式：回退这些改动；如属专项批准，请重新运行并显式 --allow <path>，同时在 Spec 变更登记中注明。');
  process.exit(1);
}

console.log(`PASS: 未检测到 C 类文件改动（扫描 ${unique.length} 个变更路径，清单 ${patterns.length} 条）。`);
