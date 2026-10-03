import importlib.util
import json
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location('model_router', Path(__file__).resolve().parents[1]/'model_router.py')
router = importlib.util.module_from_spec(spec)
spec.loader.exec_module(router)


class ModelRouterTests(unittest.TestCase):
    def setUp(self):
        self.registry = {'version': 1, 'models': {
            'general': {'adapter': 'codex', 'model': 'approved-coding-model', 'status': 'ACTIVE',
                        'supportedTiers': ['TIER_2', 'TIER_3'], 'reasoningEfforts': ['medium', 'high']},
            'alternative': {'adapter': 'claude', 'model': 'approved-review-model', 'status': 'ACTIVE',
                            'supportedTiers': ['TIER_3'], 'reasoningEfforts': ['high']}}}
        self.policy = {'version': 1, 'tiers': {
            'TIER_2': {'modelId': 'general', 'reasoningEffort': 'medium'},
            'TIER_3': {'modelId': 'alternative', 'reasoningEffort': 'high'}}}

    def select(self, tier='TIER_3'):
        registry = router.validate_registry(self.registry)
        policy = router.validate_policy(self.policy, registry)
        return router.select(registry, policy, tier, 30)

    def test_tier_selects_adapter_model_and_effort_from_data(self):
        # given
        self.policy['tiers']['TIER_3'] = {'modelId': 'general', 'reasoningEffort': 'high'}
        # when
        config, decision = self.select()
        # then
        self.assertEqual({'provider': 'codex', 'model': 'approved-coding-model',
                          'allowedModels': ['approved-coding-model'], 'reasoningEffort': 'high',
                          'timeoutSeconds': 30}, config)
        self.assertEqual({'requiredTier': 'TIER_3', 'modelId': 'general', 'provider': 'codex',
                          'model': 'approved-coding-model', 'reasoningEffort': 'high'}, decision)

    def test_policy_can_switch_adapter_without_changing_tier_input(self):
        # given
        before = json.loads(json.dumps(self.registry))
        # when
        config, decision = self.select()
        # then
        self.assertEqual('claude', config['provider'])
        self.assertEqual('approved-review-model', decision['model'])
        self.assertEqual(before, self.registry)
        self.assertEqual('TIER_3', decision['requiredTier'])

    def test_inactive_lifecycle_states_are_never_selected(self):
        for status in ('DISCOVERED', 'CANDIDATE', 'BENCHMARKED', 'SHADOW', 'CANARY', 'DEPRECATED', 'RETIRED'):
            with self.subTest(status=status):
                # given
                self.registry['models']['alternative']['status'] = status
                # when / then
                with self.assertRaises(ValueError):
                    self.select()

    def test_missing_tier_and_incompatible_profile_do_not_fall_back(self):
        for tier in ('TIER_1', 'TIER_4', 'UNKNOWN', None):
            with self.subTest(tier=tier), self.assertRaises(ValueError):
                self.select(tier)
        self.policy['tiers']['TIER_3']['reasoningEffort'] = 'max'
        with self.assertRaises(ValueError):
            self.select()
        self.policy['tiers']['TIER_3'] = {'modelId': 'general', 'reasoningEffort': 'high'}
        self.registry['models']['general']['supportedTiers'] = ['TIER_2']
        with self.assertRaises(ValueError):
            self.select()

    def test_invalid_registry_entries_are_rejected(self):
        for key, value in (('adapter', 'unknown'), ('model', '--unsafe'), ('status', 'UNKNOWN'),
                           ('supportedTiers', []), ('supportedTiers', ['TIER_2', 'TIER_2']),
                           ('supportedTiers', ['TIER_5']), ('reasoningEfforts', []),
                           ('reasoningEfforts', ['medium', 'medium']), ('reasoningEfforts', ['--unsafe']),
                           ('credential', 'not-allowed')):
            with self.subTest(key=key, value=value):
                # given
                registry = json.loads(json.dumps(self.registry))
                registry['models']['general'][key] = value
                # when / then
                with self.assertRaises(ValueError):
                    router.validate_registry(registry)
        for registry in ({}, {'version': True, 'models': self.registry['models']},
                         {'version': 2, 'models': self.registry['models']}, {'version': 1, 'models': {}},
                         {'version': 1, 'models': {'../bad': self.registry['models']['general']}}):
            with self.subTest(registry=registry), self.assertRaises(ValueError):
                router.validate_registry(registry)

    def test_invalid_policy_and_unknown_references_are_rejected(self):
        registry = router.validate_registry(self.registry)
        for policy in ({}, {'version': True, 'tiers': self.policy['tiers']}, {'version': 1, 'tiers': {}},
                       {'version': 1, 'tiers': {'TIER_3': {'modelId': 'missing', 'reasoningEffort': 'high'}}},
                       {'version': 1, 'tiers': {'TIER_3': {'modelId': 'general'}}},
                       {'version': 1, 'tiers': {'TIER_5': self.policy['tiers']['TIER_2']}},
                       {'version': 1, 'tiers': self.policy['tiers'], 'fallback': 'unsafe'}):
            with self.subTest(policy=policy), self.assertRaises(ValueError):
                router.validate_policy(policy, registry)

    def test_routing_settings_require_both_stages_and_explicit_paths(self):
        # given
        config = {'registry': '/tmp/models.json', 'policy': '/tmp/policy.json',
                  'requiredTiers': {'test-writing': 'TIER_2', 'implementation': 'TIER_3'}}
        # when
        accepted = router.settings(config)
        # then
        self.assertEqual(300, accepted['timeoutSeconds'])
        self.assertEqual(config['requiredTiers'], accepted['requiredTiers'])
        for invalid in (dict(config, registry=''), dict(config, registry='models.json'),
                        dict(config, requiredTiers={'test-writing': 'TIER_2'}),
                        dict(config, requiredTiers={'test-writing': 'TIER_5', 'implementation': 'TIER_3'}),
                        dict(config, timeoutSeconds=True), dict(config, timeoutSeconds=0),
                        dict(config, credential='not-allowed')):
            with self.subTest(config=invalid), self.assertRaises(ValueError):
                router.settings(invalid)

    def test_automatic_capability_and_attempt_limit_are_explicit_opt_ins(self):
        # given
        config = {'registry': '/tmp/models.json', 'policy': '/tmp/policy.json', 'capability': {}, 'maxAttempts': 3}
        # when
        accepted = router.settings(config)
        # then
        self.assertEqual('jev-latest', accepted['capability']['model'])
        self.assertEqual(0.8, accepted['capability']['minConfidence'])
        self.assertEqual(3, accepted['maxAttempts'])
        for invalid in (dict(config, requiredTiers={'test-writing': 'TIER_2', 'implementation': 'TIER_3'}),
                        dict(config, maxAttempts=True), dict(config, maxAttempts=4), dict(config, maxAttempts=0),
                        dict(config, capability={'endpoint': 'https://other.invalid'}),
                        dict(config, capability={'model': 'other-model'}), dict(config, capability={'timeoutSeconds': 0}),
                        dict(config, capability={'minConfidence': True}), dict(config, capability={'minConfidence': float('nan')}),
                        dict(config, capability={'minConfidence': 0.1})):
            with self.subTest(config=invalid), self.assertRaises(ValueError):
                router.settings(invalid)

    def test_fallback_chain_selects_only_registered_increasing_efforts(self):
        # given
        self.registry['models']['general']['reasoningEfforts'].extend(['xhigh', 'max'])
        self.policy['tiers']['TIER_2']['fallbacks'] = [
            {'modelId': 'general', 'reasoningEffort': 'high'}, {'modelId': 'general', 'reasoningEffort': 'xhigh'}]
        registry = router.validate_registry(self.registry)
        policy = router.validate_policy(self.policy, registry)
        # when
        decisions = [router.select(registry, policy, 'TIER_2', 30, attempt=step)[1] for step in range(3)]
        # then
        self.assertEqual(['medium', 'high', 'xhigh'], [value['reasoningEffort'] for value in decisions])
        with self.assertRaises(ValueError): router.select(registry, policy, 'TIER_2', 30, attempt=3)
        for fallback in ({'modelId': 'general', 'reasoningEffort': 'medium'},
                         {'modelId': 'general', 'reasoningEffort': 'low'},
                         {'modelId': 'general', 'reasoningEffort': 'max'},
                         {'modelId': 'missing', 'reasoningEffort': 'high'},
                         {'modelId': 'alternative', 'reasoningEffort': 'high'}):
            with self.subTest(fallback=fallback):
                self.policy['tiers']['TIER_2']['fallbacks'] = [fallback]
                with self.assertRaises(ValueError): router.validate_policy(self.policy, registry)

    def test_distinct_profiles_cannot_repeat_the_same_underlying_model_and_effort(self):
        # given
        self.registry['models']['alias'] = dict(self.registry['models']['general'])
        self.policy['tiers']['TIER_2']['fallbacks'] = [{'modelId': 'alias', 'reasoningEffort': 'medium'}]
        # when / then
        with self.assertRaises(ValueError): router.validate_policy(self.policy, router.validate_registry(self.registry))


if __name__ == '__main__':
    unittest.main()
