import importlib.util
from pathlib import Path
import shutil
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('golden_benchmark', SCRIPTS/'golden_benchmark.py')
golden = importlib.util.module_from_spec(spec)
spec.loader.exec_module(golden)

REGISTRY = {'models': {'general': {'adapter': 'codex', 'model': 'gpt-6.1-sol', 'reasoningEfforts': ['low', 'medium']},
                       'banned': {'adapter': 'claude', 'model': 'claude-opus-5-5', 'reasoningEfforts': ['high']}}}

FIXES = {
    'boundary': '''public class Discount {
    public static long apply(long amount) {
        if (amount < 0) throw new IllegalArgumentException("negative amount");
        if (amount >= 10000) return amount - amount / 10;
        return amount;
    }
}
''',
    'money': '''public class Split {
    public static long[] share(long total, int people) {
        if (people <= 0 || total < 0) throw new IllegalArgumentException("invalid split");
        long[] shares = new long[people];
        for (int i = 0; i < people; i++) shares[i] = total / people + (i < total % people ? 1 : 0);
        return shares;
    }
}
''',
    'dedup': '''import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PaymentHandler {
    private final Set<String> processed = ConcurrentHashMap.newKeySet();
    private final AtomicLong charged = new AtomicLong();

    public boolean handle(String paymentId, long amount) {
        if (!processed.add(paymentId)) return false;
        charged.addAndGet(amount);
        return true;
    }

    public long charged() { return charged.get(); }
}
''',
    'window': '''import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class RateLimiter {
    private final int limit;
    private final long windowMillis;
    private final Map<String, Deque<Long>> calls = new HashMap<>();

    public RateLimiter(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    public synchronized boolean allow(String caller, long now) {
        Deque<Long> recent = calls.computeIfAbsent(caller, key -> new ArrayDeque<>());
        while (!recent.isEmpty() && recent.peekFirst() <= now - windowMillis) recent.removeFirst();
        if (recent.size() >= limit) return false;
        recent.addLast(now);
        return true;
    }
}
'''}


@unittest.skipUnless(shutil.which('java'), 'java not installed')
class GoldenBenchmarkTests(unittest.TestCase):
    def test_every_task_fails_as_written_and_passes_with_a_correct_fix(self):
        for task in golden.tasks():
            with self.subTest(task['name']):
                self.assertFalse(golden.check(task, (task['folder']/task['file']).read_text()))
                self.assertTrue(golden.check(task, FIXES[task['name']]))

    def test_routes_are_scored_by_the_hidden_checks_not_by_the_model_answer(self):
        # given: a model that fixes two tasks and returns the other two unchanged
        prompts = []

        def call(config, workspace, prompt, schema, validate):
            prompts.append(prompt)
            task = next(task for task in golden.tasks() if task['description'] in prompt)
            content = FIXES[task['name']] if task['name'] in ('boundary', 'money') else (task['folder']/task['file']).read_text()
            return {'status': 'COMPLETED', 'output': validate({'content': content}), 'usage': {'input': 10, 'output': 5}}
        # when
        results = golden.benchmark(['general/medium'], REGISTRY, call)
        # then
        value = results['general/medium']
        self.assertEqual((2, 4, 0.5), (value['passed'], value['total'], value['passRate']))
        self.assertEqual({'input': 40, 'output': 20}, value['usage'])
        self.assertTrue(all('Check.java' not in prompt for prompt in prompts))
        self.assertIn('2/4 통과', golden.report(results))

    def test_forbidden_or_unknown_routes_are_refused(self):
        with self.assertRaisesRegex(ValueError, 'Forbidden'):
            golden.route_config(REGISTRY, 'banned/high')
        with self.assertRaisesRegex(ValueError, 'Unknown route'):
            golden.route_config(REGISTRY, 'general/xhigh')


if __name__ == '__main__':
    unittest.main()
