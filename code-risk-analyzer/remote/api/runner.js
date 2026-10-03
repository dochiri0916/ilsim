import { exchange, handle } from '../lib/store.js';

export default function runner(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });
  return handle(req, res, process.env.ASK_RUNNER_TOKEN, async (call) => ({ commands: await exchange(call, req.body?.status || {}) }));
}
