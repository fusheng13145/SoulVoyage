#!/usr/bin/env node
/**
 * H-07 发布门禁：只校验“密钥/域名/证书/小程序域名是否就绪”，不打印任何真实值。
 * 用法：
 *   node deploy/tools/check_release.mjs --env deploy/local.env \
 *     --miniprogram-env miniprogram/.env.production --tls-dir deploy/certs
 * 退出码：0=全部通过，1=存在未就绪项，2=参数/文件错误。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(here, '../..')

function arg(name, fallback) {
  const i = process.argv.indexOf(name)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}
function rel(p) {
  return path.relative(repo, p).replaceAll('\\', '/')
}
function readEnv(file) {
  if (!fs.existsSync(file)) return null
  const out = {}
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/)
    if (!m) continue
    let v = m[2].trim()
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      v = v.slice(1, -1)
    } else {
      v = v.replace(/\s+#.*$/, '').trim()
    }
    out[m[1]] = v
  }
  return out
}
const placeholder = v =>
  !v || /CHANGE_ME|REPLACE|example\.com|<[^>]*>/i.test(String(v).trim())
const secret = (v, min = 16) => !placeholder(v) && String(v).trim().length >= min

const envPath = path.resolve(repo, arg('--env', 'deploy/local.env'))
const miniPath = path.resolve(repo, arg('--miniprogram-env', 'miniprogram/.env.production'))

if (process.argv.includes('--help')) {
  console.log('用法: node deploy/tools/check_release.mjs [--env path] [--miniprogram-env path] [--tls-dir path]')
  process.exit(0)
}

const env = readEnv(envPath)
if (!env) {
  console.error(`FAIL 环境文件不存在或不可读: ${rel(envPath)}`)
  process.exit(2)
}
const mini = readEnv(miniPath) || {}
const tlsDir = path.resolve(repo, arg('--tls-dir', env.SV_TLS_DIR || 'deploy/certs'))
const checks = []
const check = (name, ok, detail = '') => checks.push({ name, ok, detail })

const domain = (env.SV_DOMAIN || '').trim()
check('SV_DOMAIN', !placeholder(domain) && /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(domain), '需为已备案域名')
check(
  'SV_CORS_ORIGINS',
  String(env.SV_CORS_ORIGINS || '')
    .split(',')
    .map(x => x.trim())
    .filter(Boolean)
    .every(x => x.startsWith('https://')) &&
    new RegExp(`^https://(www\\.)?${domain.replaceAll('.', '\\.')}(/|$)`).test(
      String(env.SV_CORS_ORIGINS || '').trim(),
    ),
  '必须为 https 且包含 SV_DOMAIN',
)

check('SV_LLM_PROVIDER', String(env.SV_LLM_PROVIDER || '').toLowerCase() !== 'mock' && !!env.SV_LLM_PROVIDER, '生产禁 mock')
check('SV_LLM_BASE_URL', !placeholder(env.SV_LLM_BASE_URL) && /^https:\/\//i.test(env.SV_LLM_BASE_URL || ''), '需为 https 地址')
check('SV_LLM_MODEL', !placeholder(env.SV_LLM_MODEL), '需填写模型名')
check('SV_LLM_API_KEY', secret(env.SV_LLM_API_KEY), '需填写真实密钥')

check('SV_ASR_PROVIDER', String(env.SV_ASR_PROVIDER || '').toLowerCase() === 'openai', '生产需 openai')
check('SV_ASR_API_KEY', secret(env.SV_ASR_API_KEY) || secret(env.SV_LLM_API_KEY), 'ASR 密钥或 LLM 密钥需就绪')

check('SV_WX_PROVIDER', String(env.SV_WX_PROVIDER || '').toLowerCase() === 'http', '生产需 http')
check('SV_WX_APPID', !placeholder(env.SV_WX_APPID), '需填写小程序 AppID')
check('SV_WX_SECRET', secret(env.SV_WX_SECRET), '需填写小程序 Secret')

const master = String(env.SV_MASTER_KEY || '').trim()
let masterOk = false
try {
  masterOk = !placeholder(master) && Buffer.from(master, 'base64').length === 32
} catch {
  masterOk = false
}
check('SV_MASTER_KEY', masterOk, '需为 base64 编码的 32 字节主密钥')
check('SV_JWT_SECRET', secret(env.SV_JWT_SECRET, 32), '长度需 ≥32')

check('SV_NEO4J_ENABLED', String(env.SV_NEO4J_ENABLED || '').toLowerCase() === 'true', '生产需开启图侧')
check('SV_NEO4J_URI', /^(bolt|neo4j):\/\//i.test(env.SV_NEO4J_URI || ''), '需为 bolt:// 或 neo4j://')
check('SV_NEO4J_PASS', !placeholder(env.SV_NEO4J_PASS), '需填写 Neo4j 密码')

for (const file of ['fullchain.pem', 'privkey.pem']) {
  const f = path.join(tlsDir, file)
  check(
    `TLS ${file}`,
    fs.existsSync(f) && fs.statSync(f).isFile() && fs.statSync(f).size > 0,
    `需存在于 ${rel(tlsDir)}`,
  )
}

check('MINI VITE_ONLINE_ORIGIN', /^https:\/\//i.test(mini.VITE_ONLINE_ORIGIN || '') && !placeholder(mini.VITE_ONLINE_ORIGIN), `需在 ${rel(miniPath)} 配置 https 域名`)

const failed = checks.filter(c => !c.ok)
console.log(`发布门禁: ${rel(envPath)}`)
for (const c of checks) {
  console.log(`${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.ok || !c.detail ? '' : `  (${c.detail})`}`)
}
console.log(`结果: ${checks.length - failed.length} PASS / ${failed.length} FAIL`)
process.exit(failed.length ? 1 : 0)
