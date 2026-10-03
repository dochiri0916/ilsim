import { enqueue, handle, validateCommand } from '../lib/store.js';

export default function command(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });
  return handle(req, res, process.env.ASK_PHONE_PASSWORD, (call) => enqueue(call, validateCommand(req.body)));
}
