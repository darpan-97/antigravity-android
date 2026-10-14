import fs from 'fs';
import readline from 'readline';
import os from 'os';

const USER_HOME = os.homedir();
const BASE = process.env.ANTIGRAVITY_DATA_DIR || `${USER_HOME}/.gemini/antigravity`;

export async function getAntigravityTasks(convoId) {
  if (!convoId) return [];
  const file = `${BASE}/brain/${convoId}/.system_generated/logs/transcript_full.jsonl`;
  if (!fs.existsSync(file)) return [];

  const rl = readline.createInterface({
    input: fs.createReadStream(file),
    crlfDelay: Infinity
  });

  const active = new Map();

  for await (const line of rl) {
    if (!line) continue;
    try {
      const o = JSON.parse(line);
      // Tool launches or manage_task kills
      if (o.type === 'PLANNER_RESPONSE' && Array.isArray(o.tool_calls)) {
        for (const t of o.tool_calls) {
          if (t.name === 'manage_task' && t.args) {
            if (t.args.Action === 'kill' && t.args.TaskId) {
              active.delete(t.args.TaskId);
            }
          }
        }
      }
      if (o.content && typeof o.content === 'string') {
        // System message completion
        const m = o.content.match(/Task id ["']([^"']+)["'] (?:finished|was canceled)/);
        if (m) {
          active.delete(m[1]);
        }
        // Context checkpoint block
        if (o.content.includes('# Running Background Tasks')) {
          const regex = /"taskId":\s*"([^"]+)"[\s\S]*?"toolSummary":\s*"([^"]+)"[\s\S]*?"description":\s*"([^"]+)"/g;
          let match;
          while ((match = regex.exec(o.content)) !== null) {
            active.set(match[1], {
              id: match[1],
              summary: match[2],
              description: match[3]
            });
          }
        }
      }
    } catch {}
  }

  return Array.from(active.values());
}

if (process.argv[1]?.endsWith('parse_agy_tasks.mjs')) {
  getAntigravityTasks().then(tasks => console.log('Parsed tasks:', tasks));
}
