from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


check = load('model_check')
ask = load('ask')


class ModelCheckTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.routing = ask.default_routing({})
        self.calls = []

    def caller(self, failing=()):
        def call(config, workspace, prompt, schema, validate):
            self.calls.append(config)
            self.assertTrue(Path(workspace).is_dir())
            if config['model'] in failing:
                return {'status': 'FAILED', 'failureKind': 'INVOCATION', 'reason': 'CLI invocation failed'}
            return {'status': 'COMPLETED', 'output': validate({'status': 'COMPLETED'}), 'usage': {'input': 10}}
        return call

    def run_main(self, call, routing=None):
        with redirect_stdout(io.StringIO()) as output:
            code = check.main(call, routing or self.routing)
        return code, output.getvalue()

    def test_every_used_model_is_probed_once_at_its_cheapest_effort(self):
        # when
        code, output = self.run_main(self.caller())
        # then
        self.assertEqual(0, code)
        self.assertEqual({'gpt-6-luna': 'low', 'gpt-6.1-sol': 'medium', 'claude-sonnet-5-5': 'medium'},
                         {config['model']: config['reasoningEffort'] for config in self.calls})
        self.assertEqual(3, len(self.calls))
        self.assertIn('모든 모델이 응답했습니다', output)

    def test_a_failed_primary_is_reported_with_the_tiers_it_affects(self):
        # given: the cheap model was retired
        code, output = self.run_main(self.caller(failing={'gpt-6-luna'}))
        # then: TIER_1 has no route left, TIER_2 still works through its fallback
        self.assertEqual(1, code)
        self.assertIn('focused (gpt-6-luna): 실패 - FAILED INVOCATION: CLI invocation failed', output)
        self.assertIn('TIER_1: 쓸 수 있는 모델이 없습니다', output)
        self.assertIn('TIER_2: 첫 모델이 실패해 fallback으로만 동작합니다 (1/2)', output)
        self.assertNotIn('TIER_3', output)
        self.assertIn('models.json의 model 값을 고치세요', output)

    def test_forbidden_models_are_refused_before_any_call(self):
        # given
        registry = json.loads(Path(self.routing['registry']).read_text())
        registry['models']['review']['model'] = 'claude-opus-5-5'
        path = self.root/'models.json'
        path.write_text(json.dumps(registry))
        # when
        code, output = self.run_main(self.caller(), dict(self.routing, registry=str(path)))
        # then
        self.assertEqual((2, []), (code, self.calls))
        self.assertIn('금지된 고비용 모델', output)

    def test_ask_runs_the_check_without_a_request(self):
        original = ask._module

        def module(name):
            loaded = original(name)
            if name == 'model_check':
                loaded.main = lambda: 7
            return loaded
        ask._module = module
        self.addCleanup(setattr, ask, '_module', original)
        self.assertEqual(7, ask.main(['--check-models']))


if __name__ == '__main__':
    unittest.main()
