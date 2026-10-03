// Upstash Redis over its REST API (what Vercel's Redis integration provisions); no dependencies.
import { createHash, timingSafeEqual } from 'node:crypto';

const STATUS = 'ask:status';
const QUEUE = 'ask:commands';
const FAILURES = 'ask:auth-failures';
export const MAX_QUEUE = 20;
export const MAX_TEXT = 2000;
const MAX_FAILURES = 20;
const FAILURE_WINDOW = 600;

export function redis(env = process.env, fetcher = globalThis.fetch) {
  const url = env.KV_REST_API_URL || env.UPSTASH_REDIS_REST_URL;
  const token = env.KV_REST_API_TOKEN || env.UPSTASH_REDIS_REST_TOKEN;
  if (!url || !token) throw new Error('Redis is not configured');
  return async (...commands) => {
    const response = await fetcher(url.replace(/\/$/, '') + '/pipeline', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(commands),
    });
    if (!response.ok) throw new Error(`Redis error ${response.status}`);
    const results = await response.json();
    const failed = results.find((item) => item.error);
    if (failed) throw new Error('Redis error');
    return results.map((item) => item.result);
  };
}

function same(given, expected) {
  if (typeof given !== 'string' || typeof expected !== 'string' || expected.length < 12) return false;
  const digest = (value) => createHash('sha256').update(value).digest();
  return timingSafeEqual(digest(given), digest(expected));
}

function bearer(req) {
  const header = req.headers?.authorization || '';
  return header.startsWith('Bearer ') ? header.slice(7) : '';
}

// Every wrong secret counts toward one shared limit, so guessing stops after a few tries per window.
export async function authorize(req, secret, call) {
  const [failures] = await call(['GET', FAILURES]);
  if (Number(failures || 0) >= MAX_FAILURES) return 'locked';
  if (same(bearer(req), secret)) return 'ok';
  await call(['INCR', FAILURES], ['EXPIRE', FAILURES, FAILURE_WINDOW, 'NX']);
  return 'denied';
}

export function validateCommand(body) {
  if (!body || typeof body !== 'object') throw new Error('명령 형식이 잘못됐습니다');
  if (body.type === 'request') {
    const text = typeof body.text === 'string' ? body.text.trim() : '';
    if (!text || text.length > MAX_TEXT) throw new Error(`요청은 1~${MAX_TEXT}자여야 합니다`);
    return { type: 'request', text };
  }
  if (body.type === 'approve') {
    if (typeof body.session !== 'string' || !/^[\w-]{1,40}$/.test(body.session)) throw new Error('승인할 작업이 없습니다');
    return { type: 'approve', session: body.session };
  }
  throw new Error('알 수 없는 명령입니다');
}

export async function enqueue(call, command, now = Date.now()) {
  const [length] = await call(['LLEN', QUEUE]);
  if (Number(length) >= MAX_QUEUE) throw new Error('대기 중인 명령이 너무 많습니다');
  const value = { ...command, id: `${now}-${Math.random().toString(36).slice(2, 8)}`, at: new Date(now).toISOString() };
  await call(['RPUSH', QUEUE, JSON.stringify(value)]);
  return value;
}

export async function view(call) {
  const [status, queued] = await call(['GET', STATUS], ['LRANGE', QUEUE, 0, -1]);
  return { status: status ? JSON.parse(status) : null, queued: (queued || []).map((item) => JSON.parse(item)) };
}

// The runner reports its status and takes every queued command in one round trip.
export async function exchange(call, status, now = Date.now()) {
  const value = JSON.stringify({ ...status, seenAt: new Date(now).toISOString() });
  if (value.length > 20000) throw new Error('상태가 너무 큽니다');
  // LPOP with a count takes the commands atomically, so one pushed meanwhile waits for the next round.
  const [, commands] = await call(['SET', STATUS, value], ['LPOP', QUEUE, MAX_QUEUE]);
  return (commands || []).map((item) => JSON.parse(item));
}

export async function handle(req, res, secret, action) {
  try {
    const call = redis();
    const verdict = await authorize(req, secret, call);
    if (verdict !== 'ok') {
      res.status(verdict === 'locked' ? 429 : 401).json({ error: verdict === 'locked' ? '잠시 후 다시 시도하세요' : '비밀번호가 틀렸습니다' });
      return;
    }
    res.status(200).json(await action(call));
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
}
