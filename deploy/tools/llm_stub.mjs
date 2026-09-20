// M10 真协议回放器：一个讲 OpenAI Chat Completions 协议的本机服务端。
// 存在的意义：MockLlmClient 是进程内回放，接真模型那天才会第一次走 HTTP。
// 这个桩把「真 HTTP + 真分块 SSE + 真 usage 字段 + 供应商侧故障」提前在本机走通，
// 让后端以 SV_LLM_PROVIDER=openai 起起来，验的是装配后的整条链路而不是单测里的客户端。
//
// 用法：node deploy/tools/llm_stub.mjs [port=8787]
// 后端：SV_LLM_PROVIDER=openai SV_LLM_BASE_URL=http://127.0.0.1:8787/v1 \
//       SV_LLM_API_KEY=sk-stub SV_LLM_MODEL=stub-companion-8k
// 控制面：
//   POST /__ctl  {"mode":"ok|reject|truncate|stall|invalid"}   —— 注入供应商侧故障（一次生效于后续请求）
//   GET  /__stats {"calls":n,"streamed":n,"tokensIn":n,"tokensOut":n,"byTemplate":{...}}
// 覆盖范围：两个交互式模板给自然文本，其余模板按 backend/src/main/resources/schemas 现场造合法形状；
// 认不出模板的提示词一律 400——宁可报错也不要让桩冒充"模型真的会这么答"。
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const PORT = Number(process.argv[2] || 8787);
let mode = 'ok';
const stats = { calls: 0, streamed: 0, tokensIn: 0, tokensOut: 0, byTemplate: {} };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const est = s => Math.max(1, Math.ceil([...s].length / 2.2));   // 桩的 token 估算：中文≈2.2 字/token

/** 按系统提示词的特征字判定模板（协议里没有模板名，真供应商同样只看文本） */
const MARKERS = [
  ['话搭子', 'companion_v1'],
  ['人际模拟训练中的 NPC', 'npc_v1'],
  ['情绪感知', 'emotion_v1'],
  ['溯源推理', 'trace_v1'],
  ['疏导干预', 'support_v1'],
  ['复盘教练', 'simulate_review_v1'],
  ['成长来信', 'growth_letter_v1'],
];
const SCHEMA_OF = {
  emotion_v1: 'emotion_result.json',
  trace_v1: 'trace_result.json',
  support_v1: 'support_plan.json',
  simulate_review_v1: 'simulate_review_result.json',
  growth_letter_v1: 'growth_letter.json',
};

function route(systemText) {
  for (const [marker, tpl] of MARKERS) if (systemText.includes(marker)) return tpl;
  return null;
}

/**
 * 契约驱动的最小可用实例：按 schema 现场造一份合法形状，
 * 这样"以 OpenAI 协议跑异步流水线"才是可复跑的，而不是只有交互式回合能走真协议。
 * 语义级校验（练习 id 闭集、kgSource 反查）仍会拒——那是模型的活，不是桩的活。
 */
const SCHEMA_DIR = new URL('../../backend/src/main/resources/schemas/', import.meta.url);
const schemaCache = new Map();
async function schema(name) {
  if (!schemaCache.has(name))
    schemaCache.set(name, JSON.parse(await readFile(fileURLToPath(new URL(name, SCHEMA_DIR)), 'utf8')));
  return schemaCache.get(name);
}

let seq = 0;
function inst(s) {
  if (s.const !== undefined) return s.const;
  if (s.enum) return s.enum[0];
  switch (s.type) {
    case 'integer':
    case 'number': {
      const lo = s.minimum ?? (s.maximum != null && s.maximum < 1 ? s.maximum : 1);
      return s.type === 'integer' ? Math.round(lo) : lo;
    }
    case 'boolean':
      return true;
    case 'array': {
      const n = Math.max(s.minItems ?? 0, 1);
      return Array.from({ length: Math.min(n, s.maxItems ?? n) }, () => inst(s.items || {}));
    }
    case 'object': {
      const o = {};
      for (const [k, sub] of Object.entries(s.properties || {})) o[k] = inst(sub);
      return o;
    }
    default: {
      if (s.pattern === '^ex_[a-z0-9_]+$') return 'ex_stub_' + ++seq;
      const text = '桩位文本';
      return s.minLength ? text.padEnd(s.minLength, '字') : text.slice(0, s.maxLength ?? text.length);
    }
  }
}

async function contentFor(template, userText) {
  if (template === 'companion_v1') {
    let mood = 'FOLLOW';
    try {
      mood = JSON.parse(userText).mood || 'FOLLOW';
    } catch {
      /* 用户消息不是 JSON 时按默认策略回 */
    }
    const reply =
      mood === 'LOW_ENERGY'
        ? '嗯，我在听。这种事摊上谁都不好受，不用急着好起来。'
        : mood === 'WARM_UP'
          ? '感觉你这边好像松了一点，挺好的，多和我说说。'
          : '然后呢？我想听你多讲一点。';
    return JSON.stringify({ reply });
  }
  if (template === 'npc_v1')
    // npc_reply.json 是闭集契约（additionalProperties:false），多给字段就是校验失败
    return JSON.stringify({ reply: '你先说，我听着。', emotionCue: '警惕但压着火' });
  return JSON.stringify(await inst(await schema(SCHEMA_OF[template])));
}

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json;charset=utf-8' });
  res.end(JSON.stringify(body));
};

/** 真流式：SSE 头 → 若干 content 增量 → finish_reason → 只带 usage 的收尾 chunk → [DONE] */
async function streamCompletion(res, content, promptTokens) {
  res.writeHead(200, { 'Content-Type': 'text/event-stream;charset=utf-8', 'Cache-Control': 'no-cache' });
  const send = obj => res.write(`data: ${JSON.stringify(obj)}\n\n`);
  await sleep(120);   // 首包延迟（真模型也有排队）
  const cps = [...content];
  for (let i = 0; i < cps.length; i += 6) {
    send({ choices: [{ index: 0, delta: { content: cps.slice(i, i + 6).join('') } }] });
    await sleep(15);
  }
  send({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] });
  send({ choices: [], usage: { prompt_tokens: promptTokens, completion_tokens: est(content) } });
  res.write('data: [DONE]\n\n');   // 协议字面量，不能被 JSON 化成带引号的字符串
  res.end();
}

createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/__stats') return json(res, 200, stats);
  if (req.method === 'POST' && req.url === '/__ctl') {
    const body = await read(req);
    mode = (JSON.parse(body || '{}').mode || 'ok').toString();
    return json(res, 200, { mode });
  }
  if (req.url !== '/v1/chat/completions') return json(res, 404, { error: { message: 'no route' } });

  const body = JSON.parse(await read(req));
  const system = body.messages?.[0]?.content || '';
  const user = body.messages?.[1]?.content || '';
  const template = route(system);
  stats.calls++;
  stats.byTemplate[template || 'unknown'] = (stats.byTemplate[template || 'unknown'] || 0) + 1;

  if (!template)
    return json(res, 400, { error: { message: 'stub 未覆盖该模板（异步流水线请用 mock provider）' } });
  if (mode === 'reject')
    return json(res, 429, { error: { message: 'stub: 上游限流', type: 'rate_limit_error' } });
  if (mode === 'stall') {
    await sleep(70000);   // 比 soulvoyage.llm.timeout 更久：验客户端超时闸门真的会掐
    return json(res, 200, { choices: [{ message: { content: '{}' } }] });
  }
  const content = mode === 'invalid' ? JSON.stringify({ hello: '不是任何契约要求的形状' }) : await contentFor(template, user);
  stats.tokensIn += est(system + user);

  if (body.stream) {
    stats.streamed++;
    stats.tokensOut += est(content);
    if (mode === 'truncate') {
      // 断在半路：没有 finish_reason、没有 usage、没有 [DONE]——最阴的一种失败
      res.writeHead(200, { 'Content-Type': 'text/event-stream;charset=utf-8' });
      res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: content.slice(0, 8) } }] })}\n\n`);
      await sleep(20);
      res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: content.slice(8, 16) } }] })}\n\n`);
      return res.end();
    }
    return streamCompletion(res, content, est(system + user));
  }
  return json(res, 200, {
    model: body.model,
    choices: [{ index: 0, message: { role: 'assistant', content } }],
    usage: { prompt_tokens: est(system + user), completion_tokens: est(content) },
  });
}).listen(PORT, '127.0.0.1', () => console.log(`LLM 协议桩已启动 http://127.0.0.1:${PORT}/v1`));

function read(req) {
  return new Promise(resolve => {
    let s = '';
    req.on('data', d => (s += d));
    req.on('end', () => resolve(s));
  });
}
