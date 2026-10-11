import http from 'node:http';
import fs from 'node:fs';
import { exec, execFile } from 'node:child_process';
import { DatabaseSync } from 'node:sqlite';

import os from 'node:os';

const PORT = process.env.PORT || 8080;
const HOST = '0.0.0.0';
const USER_HOME = os.homedir();
const BASE = process.env.ANTIGRAVITY_DATA_DIR || `${USER_HOME}\\.gemini\\antigravity`;
const AGENTAPI_PATH = `${BASE}\\bin\\agentapi.bat`;
const SUMMARIES_DB = `${BASE}\\conversation_summaries.db`;
let currentId = '';

const sseClients = new Set();

// ---------------------------------------------------------------- helpers
const logDir = id => `${BASE}\\brain\\${id}\\.system_generated\\logs`;
function transcriptPath(id) {
  const full = `${logDir(id)}\\transcript_full.jsonl`;
  return fs.existsSync(full) ? full : `${logDir(id)}\\transcript.jsonl`;
}
function escapeForCmd(str) {
  return str.replace(/["\\]/g, '\\$&').replace(/\r?\n/g, ' ');
}
function send(res, data) {
  try { res.write(`data: ${JSON.stringify(data)}\n\n`); return true; } catch { return false; }
}
function broadcast(data) {
  for (const c of sseClients) if (!send(c, data)) sseClients.delete(c);
}
function stripQuotes(s) {
  if (typeof s !== 'string') return '';
  s = s.trim();
  if (s.length > 1 && s.startsWith('"') && s.endsWith('"')) s = s.slice(1, -1);
  return s;
}
function parseTime(s) {
  if (!s) return Date.now();
  const m = String(s).replace(' ', 'T').replace(/(\.\d{3})\d*/, '$1');
  const t = Date.parse(m);
  return Number.isNaN(t) ? Date.now() : t;
}

// ------------------------------------------------- transcript -> messages
const MOBILE_RE = /\[MOBILE USER PROMPT from Galaxy S23\]:\s*([\s\S]*?)\s*$/;

function entryToMessage(e) {
  if (!e) return null;
  const ts = parseTime(e.created_at);
  if (e.type === 'USER_INPUT' && e.content) {
    const m = /<USER_REQUEST>\s*([\s\S]*?)\s*<\/USER_REQUEST>/.exec(e.content);
    const text = (m ? m[1] : e.content).trim();
    return text ? { sender: 'user', content: text.slice(0, 6000), ts } : null;
  }
  if (e.type === 'SYSTEM_MESSAGE' && e.content) {
    const m = /content=\[MOBILE USER PROMPT(?: from [^\]]+)?\]:\s*([\s\S]*?)\s*<\/SYSTEM_MESSAGE>/.exec(e.content);
    if (m) return { sender: 'user', content: m[1].trim().slice(0, 6000), ts, fromMobile: true };
    return null;
  }
  if (e.type === 'PLANNER_RESPONSE' && e.content && String(e.content).trim()) {
    return { sender: 'assistant', content: String(e.content).trim().slice(0, 12000), ts };
  }
  return null;
}

function loadHistory(id, limit = 60) {
  const p = transcriptPath(id);
  if (!fs.existsSync(p)) return [];
  const out = [];
  for (const line of fs.readFileSync(p, 'utf8').split('\n')) {
    const t = line.trim();
    if (!t) continue;
    try {
      const m = entryToMessage(JSON.parse(t));
      if (m) out.push(m);
    } catch {}
  }
  return out.slice(-limit);
}

// ------------------------------------------------- conversations (sqlite)
function readConversations() {
  let db;
  try {
    db = new DatabaseSync(SUMMARIES_DB, { readOnly: true });
    const rows = db.prepare(
      `select conversation_id,title,preview,status,last_modified_time,workspace_uris
         from conversation_summaries
        where nesting_depth = 0 and killed = 0
        order by last_modified_time desc limit 120`).all();
    return rows.map(r => {
      let project = 'No project';
      try {
        const uris = JSON.parse(r.workspace_uris || '[]');
        if (uris[0]) project = decodeURIComponent(uris[0]).replace(/\/+$/, '').split('/').pop() || project;
      } catch {}
      const title = (r.title || r.preview || '').trim() || 'Untitled Conversation';
      return {
        id: r.conversation_id,
        title,
        project,
        running: r.status !== 'CASCADE_RUN_STATUS_IDLE',
        time: parseTime(r.last_modified_time),
      };
    });
  } catch (e) {
    return null;
  } finally {
    try { db && db.close(); } catch {}
  }
}

let convCache = [];
let lastConvJson = '';
function titleOf(id) {
  return convCache.find(c => c.id === id) || { title: 'Conversation', project: 'No project', running: false };
}

// ------------------------------------------------------ Antigravity background tasks
let antigravityTasks = [];
function refreshProcesses() {
  // Query child processes of Antigravity's language_server exclusively
  const ps = `
$lsp = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*language_server.exe*' -and $_.Name -eq 'language_server.exe' } | Select-Object -First 1 -ExpandProperty ProcessId
if ($lsp) {
  Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $lsp } | Select-Object ProcessId, Name, CommandLine | ConvertTo-Json -Compress
} else {
  "[]"
}
`;
  execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ps], { windowsHide: true, maxBuffer: 4 * 1024 * 1024 }, (err, out) => {
    if (err || !out) return;
    try {
      let arr = JSON.parse(out.trim() || '[]');
      if (!Array.isArray(arr)) arr = [arr];
      const seen = new Set();
      const list = [];
      for (const p of arr) {
        const cmd = (p.CommandLine || '').trim();
        if (!cmd) continue;
        // Ignore internal infrastructure processes like the bridge itself, reverse keeper, interactive shell
        if (/bridge_server|keep_reverse|test_lsp|conhost\.exe|"C:\\Program Files\\PowerShell\\7\\pwsh\.exe"$/i.test(cmd)) continue;

        let label = '';
        if (/start-discord\.ps1/i.test(cmd)) {
          label = 'Steam Scout Discord Bot';
        } else if (/bridge_mcp_ghidra\.py/i.test(cmd)) {
          label = 'Ghidra MCP Bridge (Port 8080)';
        } else {
          const m = cmd.match(/([\w\-.]+\.(?:mjs|js|cjs|ps1|py))\b/i);
          label = m ? m[1].trim() : cmd.slice(0, 60);
        }

        if (label && !seen.has(label)) {
          seen.add(label);
          list.push({ kind: 'task', text: label });
        }
      }
      antigravityTasks = list;
    } catch {}
  });
}

function buildTasks() {
  const tasks = [];
  for (const c of convCache) if (c.running) tasks.push({ kind: 'agent', text: `Agent working · ${c.title}` });
  for (const t of antigravityTasks) tasks.push(t);
  return tasks;
}

// -------------------------------------------------- thinking state logic
let pendingSince = 0;
let sawRunning = false;
let lastStatus = '';
function setStatus(status, message) {
  const key = status + '|' + (message || '');
  if (key === lastStatus) return;
  lastStatus = key;
  broadcast({ type: 'status', status, message: message || (status === 'thinking' ? 'Antigravity is thinking...' : '') });
}

let lastTasksJson = '';
function pollState() {
  const convs = readConversations();
  if (convs) {
    convCache = convs;
    const j = JSON.stringify(convs);
    if (j !== lastConvJson) {
      lastConvJson = j;
      broadcast({ type: 'conversations', current: currentId, items: convs });
    }
    const cur = convs.find(c => c.id === currentId);
    if (cur) {
      if (cur.running) {
        sawRunning = true;
        if (!lastStatus.startsWith('thinking')) setStatus('thinking', 'Antigravity is working...');
      }
      else if (sawRunning || (pendingSince && Date.now() - pendingSince > 20000) || !pendingSince) {
        pendingSince = 0; sawRunning = false; setStatus('idle');
      }
    }
  }
  const tasks = buildTasks();
  const tj = JSON.stringify(tasks);
  if (tj !== lastTasksJson) {
    lastTasksJson = tj;
    broadcast({ type: 'tasks', count: tasks.length, tasks: tasks.map(t => t.text), items: tasks });
  }
}

// -------------------------------------------------- live transcript tail
let offset = 0;
let tailPath = '';
function resetTail() {
  tailPath = transcriptPath(currentId);
  offset = fs.existsSync(tailPath) ? fs.statSync(tailPath).size : 0;
}
resetTail();

function tailTranscript() {
  try {
    const p = transcriptPath(currentId);
    if (p !== tailPath) { resetTail(); return; }
    if (!fs.existsSync(p)) return;
    const size = fs.statSync(p).size;
    if (size < offset) { offset = 0; }
    if (size === offset) return;
    const fd = fs.openSync(p, 'r');
    const buf = Buffer.alloc(size - offset);
    fs.readSync(fd, buf, 0, buf.length, offset);
    fs.closeSync(fd);
    const lastNl = buf.lastIndexOf(0x0a);
    if (lastNl < 0) return;
    offset += lastNl + 1;
    const text = buf.subarray(0, lastNl + 1).toString('utf8');
    for (const line of text.split('\n')) {
      const t = line.trim();
      if (!t) continue;
      let e; try { e = JSON.parse(t); } catch { continue; }
      const m = entryToMessage(e);
      if (m && !m.fromMobile) {
        broadcast({ type: 'message', sender: m.sender, message: m.content, timestamp: m.ts, conversationId: currentId });
        if (m.sender === 'user') { pendingSince = Date.now(); setStatus('thinking'); }
      }
      if (e.type === 'PLANNER_RESPONSE') {
        const calls = Array.isArray(e.tool_calls) ? e.tool_calls : [];
        if (calls.length) {
          const a = calls[0].args || {};
          const label = stripQuotes(a.toolAction) || stripQuotes(a.toolSummary) || calls[0].name;
          setStatus('thinking', label);
          broadcast({ type: 'tool', text: label, name: calls[0].name });
        } else if (m && m.sender === 'assistant') {
          pendingSince = 0; sawRunning = false; setStatus('idle');
        }
      }
    }
  } catch {}
}

// ------------------------------------------------------------ conversation
function historyEvent(id) {
  const info = titleOf(id);
  return {
    type: 'history',
    conversationId: id,
    title: info.title,
    project: info.project,
    messages: loadHistory(id),
  };
}

function selectConversation(id) {
  currentId = id;
  resetTail();
  pendingSince = 0; sawRunning = false; lastStatus = '';
  broadcast(historyEvent(id));
  broadcast({ type: 'conversations', current: currentId, items: convCache });
  const cur = convCache.find(c => c.id === id);
  setStatus(cur && cur.running ? 'thinking' : 'idle', cur && cur.running ? 'Antigravity is working...' : '');
}

// ---------------------------------------------------------------- server
function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}
function readBody(req, cb) {
  let body = '';
  req.on('data', c => { body += c; });
  req.on('end', () => { try { cb(JSON.parse(body || '{}')); } catch { cb(null); } });
}

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (url.pathname === '/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.write('retry: 3000\n\n');
    send(res, historyEvent(currentId));
    send(res, { type: 'conversations', current: currentId, items: convCache });
    const tasks = buildTasks();
    send(res, { type: 'tasks', count: tasks.length, tasks: tasks.map(t => t.text), items: tasks });
    const cur = convCache.find(c => c.id === currentId);
    send(res, { type: 'status', status: cur && cur.running ? 'thinking' : 'idle', message: 'Antigravity is working...' });
    sseClients.add(res);
    console.log(`[+] SSE client (${sseClients.size})`);
    const ping = setInterval(() => { try { res.write(': ping\n\n'); } catch { clearInterval(ping); } }, 15000);
    req.on('close', () => { clearInterval(ping); sseClients.delete(res); console.log(`[-] SSE client (${sseClients.size})`); });
    return;
  }

  if (url.pathname === '/message' && req.method === 'POST') {
    readBody(req, parsed => {
      if (!parsed) return json(res, 400, { error: 'Invalid JSON payload' });
      const text = parsed.text || parsed.content || '';
      const id = parsed.conversationId || currentId;
      console.log(`[>>] mobile -> ${id.slice(0, 8)}: "${text}"`);
      if (!text.trim()) return json(res, 400, { error: 'Message cannot be empty' });
      pendingSince = Date.now(); sawRunning = false;
      setStatus('thinking', 'Antigravity is thinking...');
      const prompt = `[MOBILE USER PROMPT]: ${text}`;
      exec(`"${AGENTAPI_PATH}" send-message ${id} "${escapeForCmd(prompt)}"`, (err, stdout, stderr) => {
        if (err) {
          console.error('[!] agentapi error:', err.message);
          setStatus('idle');
          return json(res, 500, { error: err.message, stderr });
        }
        console.log('[ok] dispatched');
        json(res, 200, { success: true });
      });
    });
    return;
  }

  if (url.pathname === '/select' && req.method === 'POST') {
    readBody(req, parsed => {
      if (!parsed || !/^[0-9a-f-]{36}$/i.test(parsed.id || '')) return json(res, 400, { error: 'bad id' });
      selectConversation(parsed.id);
      json(res, 200, { ok: true });
    });
    return;
  }

  if (url.pathname === '/new' && req.method === 'POST') {
    readBody(req, parsed => {
      const text = parsed && (parsed.prompt || '').trim();
      if (!text) return json(res, 400, { error: 'prompt required' });
      const prompt = `[MOBILE USER PROMPT from Galaxy S23]: ${text}`;
      exec(`"${AGENTAPI_PATH}" new-conversation "${escapeForCmd(prompt)}"`, (err, stdout, stderr) => {
        if (err) return json(res, 500, { error: err.message, stderr });
        const m = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i.exec(stdout);
        if (!m) return json(res, 500, { error: 'no conversation id in output', stdout });
        console.log('[new] conversation', m[0]);
        setTimeout(() => { pollState(); selectConversation(m[0]); }, 1500);
        json(res, 200, { ok: true, id: m[0] });
      });
    });
    return;
  }

  if (url.pathname === '/conversations') return json(res, 200, { current: currentId, items: convCache });
  if (url.pathname === '/tasks') { const t = buildTasks(); return json(res, 200, { count: t.length, tasks: t.map(x => x.text), items: t }); }

  if (url.pathname === '/health' || url.pathname === '/') {
    return json(res, 200, { status: 'ok', conversationId: currentId, sseClients: sseClients.size, time: new Date().toISOString() });
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not Found');
});

setInterval(tailTranscript, 700);
setInterval(pollState, 3000);
setInterval(refreshProcesses, 8000);
refreshProcesses();
convCache = readConversations() || [];
if (!currentId && convCache.length > 0) {
  // Default to the most recently modified active conversation
  currentId = convCache[0].id;
}
lastConvJson = JSON.stringify(convCache);

function getTailscaleIp() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal && net.address.startsWith('100.')) {
        return net.address;
      }
    }
  }
  return null;
}

server.listen(PORT, HOST, () => {
  const tsIp = getTailscaleIp();
  console.log('======================================================================');
  console.log('   ANTIGRAVITY MOBILE BRIDGE SERVER LIVE');
  console.log(`   Port: ${PORT} | Active Conversation: ${currentId}`);
  console.log(`   ${convCache.length} conversations indexed`);
  if (tsIp) {
    console.log(`   [🔒 Tailscale] Connect phone to: http://${tsIp}:${PORT}`);
  } else {
    console.log(`   [LAN] Connect phone to: http://<your-ip>:${PORT}`);
  }
  console.log('======================================================================');
});
