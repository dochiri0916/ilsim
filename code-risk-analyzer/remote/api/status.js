import { handle, view } from '../lib/store.js';

export default function status(req, res) {
  if (req.method !== 'GET') return res.status(405).json({ error: 'GET only' });
  return handle(req, res, process.env.ASK_PHONE_PASSWORD, (call) => view(call));
}
