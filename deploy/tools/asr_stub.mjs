// M11 真协议回放器（对齐 llm_stub.mjs 的立场）：一个讲 OpenAI Audio Transcriptions 协议的本机服务端。
// 存在的意义：MockAsrClient 是进程内回放，接真模型那天才会第一次走 multipart HTTP。
// 这个桩把「真 multipart 上行 + 真音频字节 + 真 JSON 下行 + 供应商侧故障」提前在本机走通，
// 让后端以 SV_ASR_PROVIDER=openai 起起来，验的是装配后的整条链路而不是单测里的客户端。
//
// 用法：node deploy/tools/asr_stub.mjs [port=8788]
// 后端：SV_ASR_PROVIDER=openai SV_ASR_BASE_URL=http://127.0.0.1:8788/v1 \
//       SV_ASR_API_KEY=sk-stub SV_ASR_MODEL=stub-asr-1
// 控制面：
//   POST /__ctl  {"mode":"ok|reject|empty|dropfile"}   —— 注入供应商侧故障（对后续请求生效）
//   GET  /__stats {"calls":n,"bytes":n,"seconds":n,"byModel":{...},"lastType":"audio/webm"}
// 判据口径：转写文本里带上"这次真收了多少字节 / 用的哪个 model"，
// 冒烟只要读到这串就能证明音频确实跨了一次 HTTP，而不是桩在原地冒充。
import { createServer } from 'node:http';

const PORT = Number(process.argv[2] || 8788);
let mode = 'ok';
const stats = { calls: 0, bytes: 0, seconds: 0, byModel: {}, lastType: '', lastFields: '' };
const sleep = ms => new Promise(r => setTimeout(r, ms));

const read = req =>
  new Promise(resolve => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
  });

/**
 * 极简 multipart/form-data 解析（只为协议对答，不做通用实现）：
 * 按 boundary 切段，段头取 name/filename/content-type，段体保留原始字节——音频绝不能按文本处理。
 */
function parseMultipart(buf, boundary) {
  const delim = Buffer.from('--' + boundary);
  const fields = {};
  let i = buf.indexOf(delim);
  while (i >= 0) {
    const next = buf.indexOf(delim, i + delim.length);
    if (next < 0) break;
    const segment = buf.subarray(i + delim.length, next);
    const sep = segment.indexOf('\r\n\r\n');
    if (sep > 0) {
      const head = segment.subarray(0, sep).toString('utf8');
      let body = segment.subarray(sep + 4);
      if (body.length >= 2 && body[body.length - 2] === 0x0d && body[body.length - 1] === 0x0a)
        body = body.subarray(0, body.length - 2);
      const name = /name="([^"]*)"/.exec(head)?.[1];
      if (name)
        fields[name] = {
          body,
          filename: /filename="([^"]*)"/.exec(head)?.[1] || '',
          type: /content-type:\s*([^\r\n;]*)/i.exec(head)?.[1]?.trim().toLowerCase() || '',
        };
    }
    i = next;
  }
  return fields;
}

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json;charset=utf-8' });
  res.end(JSON.stringify(body));
};

createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/__stats') return json(res, 200, stats);
  if (req.method === 'POST' && req.url === '/__ctl') {
    const raw = await read(req);
    mode = (JSON.parse(raw.toString('utf8') || '{}').mode || 'ok').toString();
    return json(res, 200, { mode });
  }
  if (req.url !== '/v1/audio/transcriptions') return json(res, 404, { error: { message: 'no route' } });

  if (!/^Bearer /i.test(req.headers.authorization || ''))
    return json(res, 401, { error: { message: 'missing api key' } });
  if (mode === 'reject') return json(res, 503, { error: { message: 'upstream busy' } });

  const raw = await read(req);
  const boundary = /boundary=(?:"([^"]+)"|([^;\s]+))/i.exec(req.headers['content-type'] || '')?.slice(1).find(Boolean);
  const fields = boundary ? parseMultipart(raw, boundary) : {};
  const file = fields.file;
  const model = fields.model?.body.toString('utf8') || 'unknown';
  if (!file || file.body.length === 0)
    // dropfile 模式专门用来证明"后端确实在按协议发音频字节"
    return mode === 'dropfile'
      ? json(res, 200, { text: '桩：根本没收到音频' })
      : json(res, 400, { error: { message: 'no audio part' } });
  if (!file.type.startsWith('audio/')) return json(res, 400, { error: { message: 'not audio' } });

  stats.calls++;
  stats.bytes += file.body.length;
  stats.byModel[model] = (stats.byModel[model] || 0) + 1;
  stats.lastType = file.type;
  // 上行只该带标准字段（file/model）：出现私有字段说明我们在标准端点上夹带了自定义协议
  stats.lastFields = Object.keys(fields)
    .filter(k => k !== 'file')
    .sort()
    .join(',');
  await sleep(150); // 真模型也有排队，顺手让前端的"正在转写…"看得见

  if (mode === 'empty') return json(res, 200, { text: '' }); // 供应商"听完了但没转出来"
  // 时长由服务端自己判：OpenAI 的转写端点没有"客户端申报时长"这个字段，
  // 所以这里按体积粗估（opus ~32kbps），客户端若真塞了 durationMs 也照收不误。
  const declared = Math.round((Number(fields.durationMs?.body.toString('utf8')) || 0) / 1000)
  const seconds = Math.max(1, declared || Math.round((file.body.length * 8) / 32000));
  stats.seconds += seconds;
  return json(res, 200, {
    text: `今天先把这段录下来，回头再顺一顺。（桩转写 ${file.body.length} 字节·${model}·约 ${seconds} 秒）`,
    model,
    language: 'zh',
    duration: seconds,
  });
}).listen(PORT, () => console.log(`asr stub http://127.0.0.1:${PORT}/v1 (mode=${mode})`));
