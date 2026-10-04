import copy
import importlib.util
import json
from pathlib import Path
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


select = load('model_select')
router = load('model_router')
BENCHMARKS = json.loads((select.CONFIG/'model-benchmarks.json').read_text(encoding='utf-8'))


def chain(routes, tier):
    return [(step['model'], step['effort']) for step in routes[tier]]


class ModelSelectTests(unittest.TestCase):
    def test_committed_routing_files_are_what_the_benchmarks_give(self):
        # given
        registry, policy = select.derive(BENCHMARKS)
        # then: hand edits drift from the numbers and are caught here
        self.assertEqual(registry, json.loads((select.CONFIG/'models.json').read_text(encoding='utf-8')))
        self.assertEqual(policy, json.loads((select.CONFIG/'routing-policy.json').read_text(encoding='utf-8')))
        router.validate_policy(policy, router.validate_registry(registry))

    def test_primary_is_the_cheapest_meeting_the_tier_then_stronger_then_other_provider(self):
        # when
        routes = select.routes(BENCHMARKS)
        # then
        self.assertEqual([('gpt-6.1-sol', 'medium'), ('gpt-6.1-sol', 'high'), ('claude-sonnet-5-5', 'xhigh')],
                         chain(routes, 'TIER_3'))
        for tier, steps in routes.items():
            minimum = BENCHMARKS['tierRequirements'][tier]['minIntelligenceIndex']
            self.assertTrue(all(step['index'] >= minimum for step in steps))
            self.assertGreater(steps[1]['index'], steps[0]['index'])
            self.assertNotEqual(steps[0]['adapter'], steps[-1]['adapter'])

    def test_forbidden_models_and_max_effort_are_never_selected_even_when_strongest(self):
        # given: only opus or max effort would meet the tier
        value = copy.deepcopy(BENCHMARKS)
        value['tierRequirements']['TIER_4']['minIntelligenceIndex'] = 53
        # when / then
        with self.assertRaisesRegex(ValueError, 'No allowed model meets TIER_4'):
            select.routes(value)
        self.assertFalse(any('opus' in option['model'] or option['effort'] == 'max'
                             for option in select.candidates(BENCHMARKS)))

    def test_unpublished_cost_is_bounded_by_the_next_higher_effort(self):
        # when
        options = {(option['model'], option['effort']): option for option in select.candidates(BENCHMARKS)}
        # then
        self.assertEqual((0.21, False), (options[('gpt-6.1-sol', 'low')]['cost'], options[('gpt-6.1-sol', 'low')]['costKnown']))
        self.assertEqual(0.07, options[('gpt-6-luna', 'xhigh')]['cost'])

    def test_a_cheaper_stronger_model_takes_over_when_the_numbers_change(self):
        # given: sonnet medium becomes cheaper than sol medium
        value = copy.deepcopy(BENCHMARKS)
        value['models']['review']['efforts']['high'] = {'intelligenceIndex': 49, 'costPerTaskUsd': 0.1}
        # when / then
        self.assertEqual(('claude-sonnet-5-5', 'high'), chain(select.routes(value), 'TIER_3')[0])

    def test_invalid_numbers_are_refused(self):
        value = copy.deepcopy(BENCHMARKS)
        value['models']['general']['efforts']['low']['intelligenceIndex'] = -1
        with self.assertRaisesRegex(ValueError, 'nonnegative'):
            select.validate(value)
        value = copy.deepcopy(BENCHMARKS)
        del value['tierRequirements']['TIER_1']
        with self.assertRaises(ValueError):
            select.validate(value)


if __name__ == '__main__':
    unittest.main()
