import { test } from 'node:test';
import assert from 'node:assert/strict';
import { authorize, enqueue, exchange, MAX_QUEUE, redis, validateCommand, view } from '../lib/store.js';

// An in-memory stand-in for the few Redis commands the store uses.
function memory() {
  const data = new Map();
  return async (...commands) => commands.map(([name, key, ...args]) => {
    const list = data.get(key) || [];
    switch (name) {
      case 'GET': return data.get(key) ?? null;
      case 'SET': data.set(key, args[0]); return 'OK';
      case 'INCR': data.set(key, String(Number(data.get(key) || 0) + 1)); return Number(data.get(key));
      case 'EXPIRE': return 1;
      case 'LLEN': return list.length;
      case 'RPUSH': data.set(key, [...list, ...args]); return list.length + args.length;
      case 'LRANGE': return list;
      case 'LPOP': data.set(key, list.slice(args[0])); return list.length ? list.slice(0, args[0]) : null;
      default: throw new Error(name);
    }
  });
}

const request = (secret) => ({ headers: { authorization: `Bearer ${secret}` } });

test('commands are validated', () => {
  assert.deepEqual(validateCommand({ type: 'request', text: ' 회원 탈퇴 ' }), { type: 'request', text: '회원 탈퇴' });
  assert.deepEqual(validateCommand({ type: 'approve', session: '20261003-120000' }), { type: 'approve', session: '20261003-120000' });
  for (const bad of [null, { type: 'shell', text: 'ls' }, { type: 'request', text: ' ' }, { type: 'request', text: 'x'.repeat(2001) },
    { type: 'approve', session: '../x' }, { type: 'approve' }]) {
    assert.throws(() => validateCommand(bad));
  }
});

test('queued commands reach the runner once, with its status shown to the phone', async () => {
  const call = memory();
  await enqueue(call, { type: 'request', text: 'a' });
  await enqueue(call, { type: 'approve', session: 's1' });
  assert.equal((await view(call)).queued.length, 2);
  const taken = await exchange(call, { busy: null, project: 'demo' });
  assert.deepEqual(taken.map((item) => item.type), ['request', 'approve']);
  assert.deepEqual(await exchange(call, {}), []);
  const shown = await view(call);
  assert.deepEqual(shown.queued, []);
  assert.ok(shown.status.seenAt);
});

test('queue is bounded', async () => {
  const call = memory();
  for (let i = 0; i < MAX_QUEUE; i += 1) await enqueue(call, { type: 'request', text: 'a' });
  await assert.rejects(enqueue(call, { type: 'request', text: 'a' }));
});

test('wrong secrets are counted and lock everyone out', async () => {
  const call = memory();
  const secret = 'correct-horse-battery';
  assert.equal(await authorize(request(secret), secret, call), 'ok');
  assert.equal(await authorize(request('wrong'), secret, call), 'denied');
  assert.equal(await authorize({ headers: {} }, secret, call), 'denied');
  for (let i = 0; i < 18; i += 1) await authorize(request('wrong'), secret, call);
  assert.equal(await authorize(request(secret), secret, call), 'locked');
});

test('a short or missing configured secret never authorizes', async () => {
  const call = memory();
  assert.equal(await authorize(request('short'), 'short', call), 'denied');
  assert.equal(await authorize(request(''), undefined, call), 'denied');
});

test('redis client sends a pipeline with the bearer token and surfaces errors', async () => {
  const sent = [];
  const fetcher = async (url, options) => {
    sent.push({ url, options });
    return { ok: true, json: async () => [{ result: 'OK' }] };
  };
  const call = redis({ KV_REST_API_URL: 'https://r.upstash.io/', KV_REST_API_TOKEN: 't' }, fetcher);
  assert.deepEqual(await call(['SET', 'k', 'v']), ['OK']);
  assert.equal(sent[0].url, 'https://r.upstash.io/pipeline');
  assert.equal(sent[0].options.headers.Authorization, 'Bearer t');
  const failing = redis({ UPSTASH_REDIS_REST_URL: 'https://r', UPSTASH_REDIS_REST_TOKEN: 't' },
    async () => ({ ok: true, json: async () => [{ error: 'WRONGTYPE' }] }));
  await assert.rejects(failing(['GET', 'k']));
  assert.throws(() => redis({}, fetcher));
});
