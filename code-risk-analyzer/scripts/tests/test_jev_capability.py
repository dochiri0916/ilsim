import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1]/(name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


agent = module('agent_runner')
router = module('model_router')
jev = module('jev_capability')


class JevCapabilityTests(unittest.TestCase):
    def setUp(self):
        self.config = router.capability_settings({})
        self.state = {'stage': 'implementation', 'requirements': {'REQ-1': 'Boundary accepted'},
                      'allowedFiles': ['src/main/Service.java'], 'contextFiles': {}}
        self.environment = patch.dict(os.environ, {'TYPESAFE_API_KEY': 'private-test-key'})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def answer(self, choice, labels):
        probabilities = {label: (0.96 if label == choice else 0.04/(len(labels)-1)) for label in labels}
        return {'type': 'choice', 'choice': choice, 'probabilities': probabilities,
                'confidence': (0.96 - 1/len(labels))/(1 - 1/len(labels))}

    def response(self, tier='TIER_2', risk='LOW'):
        return {'model': 'jev-1.13.0', 'answers': {
            'requiredTier': self.answer(tier, ['TIER_1', 'TIER_2', 'TIER_3', 'TIER_4', 'UNKNOWN']),
            'risk': self.answer(risk, ['LOW', 'MEDIUM', 'HIGH', 'UNKNOWN'])},
            'usage': {'input_tokens': 100, 'output_tokens': 20}}

    def judge(self, response=None, error=None):
        opener = Mock()
        opener.open.side_effect = error
        opener.open.return_value.__enter__ = Mock(return_value=io.BytesIO(json.dumps(response or self.response()).encode()))
        opener.open.return_value.__exit__ = Mock(return_value=False)
        with patch.object(jev, 'build_opener', return_value=opener):
            result = jev.judge(self.config, self.state, agent.parse_json)
        return result, opener

    def test_one_request_batches_tier_and_risk_without_model_choices_or_key_artifacts(self):
        # given / when
        result, opener = self.judge()
        # then
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual('TIER_2', result['requiredTier'])
        request = opener.open.call_args.args[0]
        self.assertEqual('https://api.typesafe.ai/v1/systemone', request.full_url)
        self.assertEqual('Bearer private-test-key', request.get_header('Authorization'))
        body = json.loads(request.data)
        self.assertEqual(self.state, body['state'])
        self.assertEqual({'requiredTier', 'risk'}, set(body['questions']))
        self.assertTrue(all(question['type'] == 'choice' for question in body['questions'].values()))
        self.assertEqual('jev-latest', body['model'])
        self.assertNotIn('private-test-key', json.dumps(result))
        self.assertNotIn('gpt', request.data.decode().lower())
        self.assertEqual('jev-1.13.0', result['modelSnapshot'])
        self.assertEqual(64, len(result['requestSha256']))

    def test_high_risk_sets_deterministic_tier_floor(self):
        # given / when
        result, _ = self.judge(self.response(tier='TIER_1', risk='HIGH'))
        # then
        self.assertEqual('TIER_4', result['requiredTier'])
        self.assertEqual('TIER_1', result['answers']['requiredTier']['choice'])
        self.assertEqual('HIGH', result['risk'])

    def test_confident_high_risk_goes_to_the_top_tier_even_when_the_tier_is_uncertain(self):
        # given: risk is surely HIGH, the raw tier is spread out
        response = self.response(risk='HIGH')
        response['answers']['requiredTier'].update(
            probabilities={'TIER_1': 0.1, 'TIER_2': 0.5, 'TIER_3': 0.3, 'TIER_4': 0.1, 'UNKNOWN': 0.0}, confidence=0.375)
        # when
        result, _ = self.judge(response)
        # then
        self.assertEqual(('COMPLETED', 'TIER_4'), (result['status'], result['requiredTier']))
        # given: the risk itself is uncertain
        answer = response['answers']['risk']
        answer.update(probabilities={'LOW': 0.2, 'MEDIUM': 0.2, 'HIGH': 0.6, 'UNKNOWN': 0.0}, confidence=0.4667)
        # when / then
        self.assertEqual('LOW_CONFIDENCE', self.judge(response)[0]['failureKind'])

    def test_unknown_or_low_confidence_stops_without_guessing(self):
        for name in ('requiredTier', 'risk'):
            for uncertain in ('UNKNOWN', 'LOW_CONFIDENCE'):
                with self.subTest(question=name, uncertain=uncertain):
                    # given
                    response = self.response()
                    if uncertain == 'UNKNOWN':
                        response['answers'][name] = self.answer('UNKNOWN', list(response['answers'][name]['probabilities']))
                    else:
                        answer = response['answers'][name]
                        size = len(answer['probabilities'])
                        answer['probabilities'] = dict.fromkeys(answer['probabilities'], 1/size)
                        answer['confidence'] = 0.0
                    # when
                    result, _ = self.judge(response)
                    # then
                    self.assertEqual('NEEDS_REVIEW', result['status'])
                    self.assertNotIn('requiredTier', result)

    def test_inconsistent_nonfinite_and_incomplete_answers_are_rejected(self):
        for mutation in ('missing', 'wrong_choice', 'sum', 'boolean', 'confidence', 'nonfinite', 'snapshot', 'usage'):
            with self.subTest(mutation=mutation):
                # given
                response = self.response()
                answer = response['answers']['requiredTier']
                if mutation == 'missing': del answer['probabilities']['UNKNOWN']
                elif mutation == 'wrong_choice': answer['choice'] = 'TIER_4'
                elif mutation == 'sum': answer['probabilities']['TIER_2'] = 0.1
                elif mutation == 'boolean': answer['confidence'] = True
                elif mutation == 'confidence': answer['confidence'] = 0.1
                elif mutation == 'nonfinite': answer['probabilities']['TIER_2'] = float('nan')
                elif mutation == 'snapshot': response['model'] = 'unregistered-snapshot'
                else: response['usage']['input_tokens'] = -1
                # when / then
                result, _ = self.judge(response)
                self.assertEqual('INVALID_OUTPUT', result['failureKind'])
                self.assertNotIn('answers', result)

    def test_rounded_api_confidence_is_valid_but_does_not_round_up_the_acceptance_gate(self):
        # given
        response = self.response()
        response['answers']['requiredTier'].update(
            probabilities={'TIER_1': 0.162, 'TIER_2': 0.838, 'TIER_3': 0.0, 'TIER_4': 0.0, 'UNKNOWN': 0.0}, confidence=0.80)
        # when
        result, _ = self.judge(response)
        # then
        self.assertEqual('LOW_CONFIDENCE', result['failureKind'])
        self.assertLess(result['confidence'], 0.8)
        self.assertEqual('jev-1.13.0', result['modelSnapshot'])

    def test_http_and_transport_errors_are_sanitized_and_never_retried(self):
        for code, kind in ((401, 'AUTHENTICATION'), (402, 'BILLING'), (429, 'RATE_LIMITED'),
                           (529, 'SERVICE_UNAVAILABLE'), (422, 'INVALID_REQUEST'), (302, 'HTTP_ERROR')):
            with self.subTest(code=code):
                # given
                error = HTTPError(jev.ENDPOINT, code, 'private-test-key', {}, io.BytesIO(b'private-test-key'))
                # when
                result, opener = self.judge(error=error)
                # then
                self.assertEqual(kind, result['failureKind'])
                self.assertEqual('NEEDS_REVIEW', result['status'])
                opener.open.assert_called_once()
                self.assertNotIn('private-test-key', json.dumps(result))
        for error in (socket.timeout('private-test-key'), URLError('private-test-key')):
            result, opener = self.judge(error=error)
            self.assertEqual('TRANSPORT', result['failureKind'])
            opener.open.assert_called_once()
            self.assertNotIn('private-test-key', json.dumps(result))

    def test_missing_key_and_excess_context_stop_before_network(self):
        # given
        with patch.dict(os.environ, {'TYPESAFE_API_KEY': ''}), patch.object(jev, 'build_opener') as network:
            # when
            result = jev.judge(self.config, self.state, agent.parse_json)
            # then
            self.assertEqual('MISSING_CREDENTIAL', result['failureKind'])
            network.assert_not_called()
        self.state['contextFiles']['large'] = 'x' * jev.MAX_BYTES
        with patch.object(jev, 'build_opener') as network:
            result = jev.judge(self.config, self.state, agent.parse_json)
            self.assertEqual('INPUT_LIMIT', result['failureKind'])
            network.assert_not_called()

    def test_redirects_never_forward_authorization(self):
        # given
        request = jev.Request(jev.ENDPOINT, headers={'Authorization': 'Bearer private-test-key'})
        # when / then
        self.assertIsNone(jev.NoRedirect().redirect_request(request, None, 302, 'redirect', {}, 'https://other.invalid'))

    def score_response(self, tier=1, risk=0):
        answers = {}
        for name, selected in (('requiredTier', tier), ('risk', risk)):
            criteria = jev.QUESTIONS_V2[name]['criteria']
            answers[name] = {'type': 'score', 'score': selected, 'confidence': 1.0,
                             'legend': {str(index): text for index, text in enumerate(criteria)},
                             'probabilities': {str(index): float(index == selected) for index in range(len(criteria))}}
        answers['sufficiency'] = {'type': 'choice', 'choice': 'SUFFICIENT', 'confidence': 1.0,
                                  'probabilities': {'SUFFICIENT': 1.0, 'UNKNOWN': 0.0}}
        return {'model': 'jev-1.13.0', 'answers': answers, 'usage': {'input_tokens': 100, 'output_tokens': 20}}

    def test_version_two_splits_ordered_scores_and_sufficiency_with_bounded_context(self):
        # given
        self.config = router.capability_settings({'questionVersion': 2})
        self.state['contextFiles'] = {'a.java': 'code'*2000}
        # when
        result, opener = self.judge(self.score_response(tier=1, risk=2))
        # then
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual('TIER_4', result['requiredTier'])
        self.assertEqual('HIGH', result['risk'])
        body = json.loads(opener.open.call_args.args[0].data)
        self.assertEqual(self.state['requirements'], body['state']['requirements'])
        context = body['state']['contextFiles']['a.java']
        self.assertEqual(4000, context['omittedCharacters'])
        self.assertEqual(4000, len(context['excerpt']))
        self.assertEqual('score', body['questions']['risk']['type'])
        self.assertEqual(2, result['questionVersion'])

    def test_independently_rounded_distributions_have_conservative_acceptance_bounds(self):
        # given: an actual observed API shape; score is computed before rounding probabilities.
        self.config = router.capability_settings({'questionVersion': 2})
        response = self.score_response(tier=3, risk=2)
        response['answers']['risk'].update(score=1.99, confidence=0.99)
        # when / then
        result, _ = self.judge(response)
        self.assertEqual('COMPLETED', result['status'])
        self.assertLess(result['confidence'], 0.99)
        response['answers']['risk'].update(score=1.0)
        self.assertEqual('INVALID_OUTPUT', self.judge(response)[0]['failureKind'])
        response = self.score_response()
        response['answers']['requiredTier'].update(probabilities={'0': 0.10, '1': 0.89, '2': 0.0, '3': 0.0},
                                                  score=0.89, confidence=0.89)
        self.assertEqual('COMPLETED', self.judge(response)[0]['status'])

    def test_unknown_sufficiency_and_changed_score_legend_never_authorize_routing(self):
        self.config = router.capability_settings({'questionVersion': 2})
        response = self.score_response()
        response['answers']['sufficiency'] = {'type': 'choice', 'choice': 'UNKNOWN', 'confidence': 1.0,
                                             'probabilities': {'SUFFICIENT': 0.0, 'UNKNOWN': 1.0}}
        self.assertEqual('UNKNOWN', self.judge(response)[0]['failureKind'])
        response = self.score_response()
        response['answers']['requiredTier']['legend']['1'] = 'Different contract'
        self.assertEqual('INVALID_OUTPUT', self.judge(response)[0]['failureKind'])

    def test_high_risk_floor_has_one_tier_outcome_even_when_raw_difficulty_is_uncertain(self):
        # given
        self.config = router.capability_settings({'questionVersion': 2})
        response = self.score_response(risk=2)
        response['answers']['requiredTier'].update(score=1.5, confidence=0.0,
                                                  probabilities=dict.fromkeys(('0', '1', '2', '3'), 0.25))
        # when
        result, _ = self.judge(response)
        # then
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual('TIER_4', result['requiredTier'])
        self.assertEqual(0.0, result['rawConfidence'])
        self.assertGreaterEqual(result['confidence'], 0.9)
        response['answers']['risk'].update(score=1.0, confidence=0.01, probabilities={'0': 0.33, '1': 0.34, '2': 0.33})
        self.assertEqual('LOW_CONFIDENCE', self.judge(response)[0]['failureKind'])

    def test_semantic_projection_preserves_contract_and_effects_without_generation_rules(self):
        requirement = {'title': 'Pure boundary', 'postconditions': ['Zero is accepted'], 'invariants': ['Public API preserved'],
                       'scenarios': {'ZERO': {'expectedOutcomes': ['true']}}, 'transaction': {'applicability': 'NOT_APPLICABLE'},
                       'review': {'status': 'EVIDENCE_SUPPORTED'}, 'mappings': {'code': []}}
        state = {'requirements': {'mode': 'CODE_CHANGE', 'requirements': {'BOUNDARY': requirement}},
                 'contextFiles': {'AGENTS.md': 'Never weaken assertions', 'build.gradle': 'test configuration', 'src/main/B.java': 'pure code'}}
        projected = jev.project_state(state)
        contract = projected['requirements']['BOUNDARY']
        self.assertEqual(requirement['postconditions'], contract['postconditions'])
        self.assertEqual(requirement['scenarios'], contract['scenarios'])
        self.assertEqual(requirement['transaction'], contract['effects']['transaction'])
        self.assertNotIn('review', contract)
        self.assertNotIn('AGENTS.md', projected['contextFiles'])
        self.assertEqual('pure code', projected['contextFiles']['src/main/B.java']['excerpt'])


if __name__ == '__main__':
    unittest.main()
