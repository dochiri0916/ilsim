from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
import re
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('ask', Path(__file__).resolve().parents[1]/'ask.py')
ask = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ask)


class ClassifyTests(unittest.TestCase):
    def test_intents_from_natural_language(self):
        cases = {
            '주문 취소 API에 중복 요청 방지 추가해줘': 'CODE_CHANGE',
            '재고 차감 버그 고쳐줘': 'CODE_CHANGE',
            '이 프로젝트 위험 분석해줘': 'ANALYZE',
            '보안 문제 있는지 점검해줘': 'ANALYZE',
            '어디까지 진행됐어?': 'STATUS',
            '이어서 계속 진행해': 'CONTINUE',
            '검증 돌려줘': 'VERIFY',
            'PR 올려줘': 'PULL_REQUEST',
            '토큰 사용량 보여줘': 'COST',
            'SEC-001 규칙 설명해줘': 'RULES',
            '변이 테스트 해줘': 'MUTATION',
        }
        for text, intent in cases.items():
            with self.subTest(text=text):
                self.assertEqual(intent, ask.classify(text)[0])

    def test_change_verb_wins_over_analysis_and_status_words(self):
        # given: words like "상태" or "점검" appear inside a change request
        # when / then
        self.assertEqual('CODE_CHANGE', ask.classify('주문 상태 전이 버그 고쳐줘')[0])
        self.assertEqual('CODE_CHANGE', ask.classify('점검 결과대로 결제 로직 수정해줘')[0])

    def test_described_or_forbidden_changes_are_not_change_requests(self):
        self.assertEqual('ANALYZE', ask.classify('고치지 말고 문제점만 찾아줘')[0])
        self.assertEqual('ANALYZE', ask.classify('수정하지 말고 분석만 해줘')[0])
        self.assertEqual('VERIFY', ask.classify('구현한 거 검증해줘')[0])
        self.assertEqual('VERIFY', ask.classify('수정된 코드 검증해줘')[0])
        self.assertEqual('CODE_CHANGE', ask.classify('결제 검증 로직 수정해줘')[0])

    def test_rule_id_alone_asks_about_rules(self):
        self.assertEqual('RULES', ask.classify('TX-002가 뭐야?')[0])
        self.assertEqual('RULES', ask.classify('IDEM-003 설명해줘')[0])

    def test_unrecognized_request_is_rejected_instead_of_guessed(self):
        with self.assertRaises(ValueError):
            ask.classify('안녕')

    def test_pr_word_is_not_matched_inside_other_words(self):
        self.assertNotEqual('PULL_REQUEST', ask.classify('price 계산 분석해줘')[0])
        self.assertEqual('PULL_REQUEST', ask.classify('PR을 만들어줘')[0])


def jev(intent, confidence=0.95, status='COMPLETED'):
    return lambda text: {'status': status, 'intent': intent, 'confidence': confidence, 'reason': 'test'}


class DecideTests(unittest.TestCase):
    def test_agreeing_jev_and_keywords_decide_together(self):
        intent, reason = ask.decide('재고 차감 버그 고쳐줘', jev('CODE_CHANGE'))
        self.assertEqual('CODE_CHANGE', intent)
        self.assertIn('JEV', reason)

    def test_jev_understands_requests_without_keywords(self):
        self.assertEqual('CODE_CHANGE', ask.decide('주문이 두 번 결제되는 문제 좀 없애줘', jev('CODE_CHANGE'))[0])

    def test_disagreement_stops_instead_of_guessing(self):
        with self.assertRaisesRegex(ValueError, 'ANALYZE'):
            ask.decide('재고 차감 버그 고쳐줘', jev('ANALYZE'))

    def test_low_confidence_or_unknown_jev_answer_stops(self):
        for judge in (jev('CODE_CHANGE', confidence=0.5), jev('UNKNOWN')):
            with self.subTest(judge=judge):
                with self.assertRaises(ValueError):
                    ask.decide('재고 차감 버그 고쳐줘', judge)

    def test_without_jev_keywords_decide_and_say_so(self):
        intent, reason = ask.decide('재고 차감 버그 고쳐줘', None)
        self.assertEqual('CODE_CHANGE', intent)
        self.assertIn('keyword', reason)

    def test_failed_jev_call_falls_back_to_keywords_only_for_read_only_tasks(self):
        intent, reason = ask.decide('이 프로젝트 위험 분석해줘', jev(None, status='FAILED'))
        self.assertEqual('ANALYZE', intent)
        self.assertIn('JEV 실패', reason)

    def test_failed_jev_call_never_lets_keywords_alone_change_code(self):
        # given: JEV would answer UNKNOWN for this sentence when the call succeeds
        for text in ('점검하고 문제 있으면 고쳐줘', '검증 돌려줘', 'PR 올려줘', '이어서 계속 진행해'):
            with self.subTest(text=text):
                with self.assertRaisesRegex(ValueError, '--no-jev'):
                    ask.decide(text, jev(None, status='FAILED'))

    def test_failed_jev_call_without_keyword_match_is_rejected(self):
        with self.assertRaises(ValueError):
            ask.decide('주문이 두 번 결제되는 문제 좀 없애줘', jev(None, status='FAILED'))


class IntentResponseTests(unittest.TestCase):
    def response(self, choice='CODE_CHANGE', top=0.9):
        labels = list(ask.INTENT_QUESTION['criteria'])
        rest = (1 - top) / (len(labels) - 1)
        probabilities = {label: (top if label == choice else rest) for label in labels}
        confidence = (top - 1/len(labels)) / (1 - 1/len(labels))
        return {'model': 'jev-1.13.0', 'usage': {'input_tokens': 10, 'output_tokens': 1},
                'answers': {'intent': {'type': 'choice', 'choice': choice, 'probabilities': probabilities, 'confidence': confidence}}}

    def test_valid_response_yields_intent_and_confidence(self):
        result = ask.intent_answer(self.response())
        self.assertEqual('CODE_CHANGE', result['intent'])
        self.assertGreater(result['confidence'], 0.8)

    def test_understated_api_confidence_is_accepted_and_used(self):
        # given: live JEV reported 0.82 for a 0.85 choice probability
        value = self.response('PULL_REQUEST', top=0.85)
        value['answers']['intent']['confidence'] = 0.82
        # when
        result = ask.intent_answer(value)
        # then
        self.assertEqual(0.82, result['confidence'])

    def test_malformed_responses_are_rejected(self):
        broken = [self.response(), self.response(), self.response(), self.response()]
        broken[0]['answers']['intent']['choice'] = 'DEPLOY'
        del broken[1]['answers']['intent']['probabilities']['STATUS']
        broken[2]['answers']['intent']['confidence'] = 0.99
        broken[3]['model'] = 'gpt-6'
        for value in broken:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    ask.intent_answer(value)


class TierTests(unittest.TestCase):
    def test_high_risk_areas_use_tier_3(self):
        for text in ('결제 금액 수정', '재고 동시성 수정', '트랜잭션 롤백 추가', '권한 검사 추가', 'retry 로직 수정'):
            with self.subTest(text=text):
                self.assertEqual('TIER_3', ask.tier(text)[0])

    def test_low_risk_change_uses_tier_2_and_never_tier_4(self):
        self.assertEqual('TIER_2', ask.tier('버튼 문구 변경')[0])
        self.assertNotIn('TIER_4', {ask.tier(t)[0] for t in ('결제 보안 동시성 트랜잭션', '문구')})


class StageTests(unittest.TestCase):
    def test_change_runs_from_baseline_to_verification(self):
        self.assertEqual(['baseline', 'generate-tests', 'generate-implementation', 'verification'],
                         ask.stages('CODE_CHANGE', None, generator=True, service=False))

    def test_continue_picks_up_from_recorded_phase(self):
        self.assertEqual(['generate-implementation', 'verification'],
                         ask.stages('CONTINUE', 'TEST_WRITING', generator=True, service=False))
        self.assertEqual(['local-service', 'review'], ask.stages('CONTINUE', 'VERIFIED', generator=True, service=True))
        self.assertEqual(['review'], ask.stages('CONTINUE', 'VERIFIED', generator=True, service=False))
        self.assertEqual(['complete', 'pull-request'], ask.stages('PULL_REQUEST', 'REVIEWED', generator=True, service=False))

    def test_verify_stops_after_verification_until_reviewed(self):
        self.assertEqual(['verification'], ask.stages('VERIFY', 'IMPLEMENTATION', generator=True, service=False))

    def test_writing_without_agent_stops_before_generation(self):
        self.assertEqual(['baseline'], ask.stages('CODE_CHANGE', None, generator=False, service=False))

    def test_stale_or_finished_session_needs_a_person(self):
        with self.assertRaises(ValueError):
            ask.stages('CONTINUE', 'STALE', generator=True, service=False)
        with self.assertRaises(ValueError):
            ask.stages('VERIFY', None, generator=True, service=False)


class ResumeTests(unittest.TestCase):
    def stale(self, stage, reason=None, kind='SOURCES'):
        return {'phase': 'STALE', 'staleKind': kind, 'generationStage': stage,
                'invalidations': [{'reason': reason or ask.AGENT_STOP, 'kind': kind}]}

    def test_unfinished_model_stage_resumes_there(self):
        self.assertEqual(['generate-tests', 'generate-implementation', 'verification'],
                         ask.stages('CONTINUE', ask.resume_phase(self.stale('test-writing')), True, False))
        self.assertEqual(['generate-implementation', 'verification'],
                         ask.stages('CONTINUE', ask.resume_phase(self.stale('implementation')), True, False))

    def test_other_invalidations_still_need_a_new_session(self):
        for state in (self.stale('test-writing', 'Source or test changed outside the recorded operation'),
                      self.stale('test-writing', kind='INPUTS'), dict(self.stale(None))):
            self.assertEqual('STALE', ask.resume_phase(state))


class StopReasonTests(unittest.TestCase):
    def test_model_questions_and_gate_refusals_are_explained(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            # given: no record yet
            self.assertEqual([], ask.stop_reason(directory, 0))
            # given: the test writer asked questions
            (directory/'agent-test-writing.json').write_text(json.dumps(
                {'status': 'NEEDS_REVIEW', 'failureKind': 'AMBIGUITY', 'output': {'unknowns': ['상태 코드가 없습니다']}}))
            lines = ask.stop_reason(directory, 0)
            self.assertIn('- 상태 코드가 없습니다', lines)
            # given: the capability gate refused
            (directory/'agent-test-writing.json').write_text(json.dumps(
                {'status': 'NEEDS_REVIEW', 'failureKind': 'CAPABILITY',
                 'capability': {'status': 'NEEDS_REVIEW', 'failureKind': 'LOW_CONFIDENCE'}}))
            self.assertIn('JEV', ask.stop_reason(directory, 0)[0])
            # given: the answer broke the output contract
            (directory/'agent-test-writing.json').write_text(json.dumps(
                {'status': 'INVALID_OUTPUT', 'failureKind': 'OUTPUT_CONTRACT', 'reason': 'r', 'detail': 'ValueError: x',
                 'rejectedOutput': {'changes': []}}))
            lines = ask.stop_reason(directory, 0)
            self.assertIn('검사 내용: ValueError: x', lines)
            self.assertIn('rejectedOutput', lines[-2])
            self.assertEqual(ask.RETRY_HINT, lines[-1])
            # given: the record is older than the stage
            self.assertEqual([], ask.stop_reason(directory, 10**12))


class CompileErrorTests(unittest.TestCase):
    def test_missing_junit_results_show_the_javac_errors(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            (directory/'after.json').write_text(json.dumps({'project': folder, 'evidenceError': 'No unambiguous fresh JUnit results'}))
            output = f'{folder}/src/test/java/a/ATest.java:55: error: cannot find symbol\n  symbol: method x()\n'
            run = lambda command, **kwargs: subprocess.CompletedProcess(command, 1, output, '')
            self.assertEqual(['컴파일 오류:', '- src/test/java/a/ATest.java:55: error: cannot find symbol'],
                             ask.compile_errors(directory, 0, run))
            (directory/'after.json').write_text(json.dumps({'project': folder}))
            self.assertEqual([], ask.compile_errors(directory, 0, run))


class ConventionGateTests(unittest.TestCase):
    def gate(self, *reports, fix_code=0):
        with tempfile.TemporaryDirectory() as folder:
            session, reports, calls = Path(folder), list(reports), []

            def run(command):
                calls.append(command)
                if '--output' in command:
                    checks = reports.pop(0)
                    Path(command[command.index('--output') + 1]).write_text(json.dumps({'checks': checks}))
                    return 0 if all(status != 'FAIL' for status in checks.values()) else 1
                return fix_code
            value = {'convention': {'session': folder, 'project': folder}}
            result = ask._convention_gate(value, run)
        return result, ['--mode' in command for command in calls]

    def test_architecture_is_fixed_while_a_test_does_not_compile(self):
        broken = {'architecture': 'FAIL', 'compilation': 'FAIL'}
        self.assertEqual(((0, True), [False, True, False]), self.gate(broken, {'compilation': 'FAIL'}))
        self.assertEqual(((0, True), [False, True]), self.gate(broken, fix_code=1))

    def test_skipped_architecture_failure_is_not_fixed(self):
        # given: the report records architecture as skipped by the project's validators setting
        with tempfile.TemporaryDirectory() as folder:
            calls = []

            def run(command):
                calls.append(command)
                Path(command[command.index('--output') + 1]).write_text(json.dumps({
                    'checks': {'architecture': 'FAIL', 'compilation': 'FAIL'}, 'skipped': {'architecture': 'off'}}))
                return 1
            result = ask._convention_gate({'convention': {'session': folder, 'project': folder}}, run)
        # then: only compilation remains, which verification reports, so no fix round runs
        self.assertEqual(((0, True), 1), (result, len(calls)))

    def test_only_compilation_failing_steps_aside_at_once(self):
        self.assertEqual(((0, True), [False]), self.gate({'compilation': 'FAIL', 'tests': 'FAIL'}))


class SessionTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.project = self.root/'project'
        (self.project/'build').mkdir(parents=True)
        self.state = self.project/'build'/'s1'/'state.json'
        self.write('plan.json', {'project': str(self.project)})
        self.write('models.json', {'version': 1, 'models': {
            'cheap': {'adapter': 'codex', 'model': 'gpt-6-luna', 'status': 'ACTIVE',
                      'supportedTiers': ['TIER_1', 'TIER_2', 'TIER_3', 'TIER_4'], 'reasoningEfforts': ['medium', 'high', 'max']},
            'pricey': {'adapter': 'claude', 'model': 'claude-fable-5-1', 'status': 'ACTIVE',
                       'supportedTiers': ['TIER_3', 'TIER_4'], 'reasoningEfforts': ['high']}}})
        self.policy({'TIER_1': 'cheap', 'TIER_2': 'cheap', 'TIER_3': 'cheap', 'TIER_4': 'cheap'})
        self.config = {'plan': str(self.root/'plan.json'), 'requirements': 'r', 'analyzer': 'a', 'scope': 's', 'documents': ['d'],
                       'routing': {'registry': str(self.root/'models.json'), 'policy': str(self.root/'policy.json')}}
        self.calls = []

    def write(self, name, value):
        (self.root/name).write_text(json.dumps(value))

    def policy(self, tiers, fallback=None):
        value = {tier: {'modelId': model, 'reasoningEffort': 'high'} for tier, model in tiers.items()}
        if fallback:
            value['TIER_3']['fallbacks'] = [{'modelId': fallback, 'reasoningEffort': 'high'}]
        self.write('policy.json', {'version': 1, 'tiers': value})

    def runner(self, results=None):
        results = list(results or [])

        def run(command):
            self.calls.append(command)
            return results.pop(0) if results else 0
        return run

    def plan(self, text='재고 동시성 버그 고쳐줘'):
        self.write('config.json', self.config)
        return ask.plan(text, config=str(self.root/'config.json'), state=str(self.state))

    def test_plan_fixes_tier_for_new_session_and_shows_model(self):
        # when
        plan = self.plan()
        # then
        self.assertEqual('CODE_CHANGE', plan['intent'])
        self.assertEqual({'test-writing': 'TIER_3', 'implementation': 'TIER_3'}, plan['config']['routing']['requiredTiers'])
        self.assertEqual(['gpt-6-luna'], plan['models'])
        self.assertTrue(plan['changesCode'])

    def test_expensive_model_anywhere_in_reachable_routes_is_refused(self):
        # given: the cheap primary falls back to a forbidden model
        self.policy({'TIER_2': 'cheap', 'TIER_3': 'cheap'}, fallback='pricey')
        # when / then
        with self.assertRaisesRegex(ValueError, 'claude-fable-5-1'):
            self.plan()

    def test_capability_routing_checks_every_tier(self):
        # given: JEV may pick TIER_4 which routes to a forbidden model
        self.policy({'TIER_1': 'cheap', 'TIER_2': 'cheap', 'TIER_3': 'cheap', 'TIER_4': 'pricey'})
        self.config['routing']['capability'] = {}
        # when / then
        with self.assertRaises(ValueError):
            self.plan()

    def test_max_reasoning_effort_is_refused(self):
        # given
        self.write('policy.json', {'version': 1, 'tiers': {tier: {'modelId': 'cheap', 'reasoningEffort': 'max'}
                                                           for tier in ('TIER_2', 'TIER_3')}})
        # when / then
        with self.assertRaisesRegex(ValueError, 'max'):
            self.plan()

    def test_direct_agent_model_is_checked(self):
        # given
        del self.config['routing']
        self.config['agent'] = {'provider': 'claude', 'model': 'astra-2', 'allowedModels': ['astra-2']}
        # when / then
        with self.assertRaisesRegex(ValueError, 'astra-2'):
            self.plan()

    def test_code_change_is_not_executed_without_confirmation(self):
        # given
        plan = self.plan()
        # when
        code = ask.execute(plan, confirmed=False, run=self.runner())
        # then
        self.assertEqual(0, code)
        self.assertEqual([], self.calls)
        self.assertFalse(self.state.parent.exists())

    def test_confirmed_run_uses_saved_session_config_and_stops_on_failure(self):
        # given
        plan = self.plan()
        # when
        code = ask.execute(plan, confirmed=True, run=self.runner([0, 1]))
        # then
        self.assertEqual(1, code)
        self.assertEqual(['baseline', 'generate-tests'], [call[2] for call in self.calls])
        saved = self.state.parent/'ask-config.json'
        self.assertEqual(plan['config'], json.loads(saved.read_text()))
        self.assertEqual(str(saved), self.calls[0][self.calls[0].index('--config') + 1])

    def test_later_requests_reuse_session_config_instead_of_rerouting(self):
        # given: a low-risk session already fixed TIER_2
        ask.execute(self.plan('문구 변경해줘'), confirmed=True, run=self.runner([0, 0, 0, 0]))
        self.state.write_text(json.dumps({'phase': 'IMPLEMENTATION', 'history': []}))
        # when: the follow-up mentions a high-risk word
        plan = self.plan('결제 검증 돌려줘')
        # then
        self.assertEqual('TIER_2', plan['config']['routing']['requiredTiers']['implementation'])
        self.assertEqual(['verification'], plan['stages'])

    def test_with_jev_new_session_uses_capability_judgment_for_tiers(self):
        # given
        self.write('config.json', self.config)
        # when
        plan = ask.plan('재고 동시성 버그 고쳐줘', config=str(self.root/'config.json'), state=str(self.state), judge=jev('CODE_CHANGE'))
        # then
        self.assertEqual({}, plan['config']['routing']['capability'])
        self.assertNotIn('requiredTiers', plan['config']['routing'])

    def test_new_session_saves_config_with_absolute_paths_from_any_working_directory(self):
        # given: a config written with paths relative to itself
        self.config['plan'] = 'plan.json'
        self.config['routing'] = {'registry': 'models.json', 'policy': 'policy.json',
                                  'requiredTiers': {'test-writing': 'TIER_2', 'implementation': 'TIER_2'}}
        # when
        plan = self.plan()
        # then
        self.assertEqual('auto', plan['configSource'])
        self.assertEqual(str(self.root/'plan.json'), plan['config']['plan'])
        self.assertEqual(str(self.root/'models.json'), plan['config']['routing']['registry'])
        self.assertEqual(str(self.state.parent/'ask-config.json'), plan['configPath'])

    def test_missing_analyzer_defaults_to_this_checkouts_jar(self):
        del self.config['analyzer']
        self.assertEqual(str(ask.DEFAULT_JAR), self.plan()['config']['analyzer'])

    def test_explicit_tiers_in_config_are_kept(self):
        # given
        self.config['routing']['requiredTiers'] = {'test-writing': 'TIER_2', 'implementation': 'TIER_2'}
        # when
        plan = self.plan()
        # then
        self.assertEqual('TIER_2', plan['config']['routing']['requiredTiers']['test-writing'])

    def test_status_is_read_only_and_needs_no_confirmation(self):
        # given
        self.state.parent.mkdir(parents=True)
        self.state.write_text(json.dumps({'phase': 'VERIFIED', 'history': []}))
        plan = self.plan('어디까지 진행됐어?')
        # when
        code = ask.execute(plan, confirmed=False, run=self.runner())
        # then
        self.assertEqual(0, code)
        self.assertFalse(plan['changesCode'])
        self.assertEqual([], self.calls)

    def test_failed_verification_runs_configured_repair(self):
        # given
        self.config['repair'] = {}
        self.state.parent.mkdir(parents=True)
        self.state.write_text(json.dumps({'phase': 'IMPLEMENTATION', 'history': []}))
        plan = self.plan('검증해줘')
        # when
        code = ask.execute(plan, confirmed=True, run=self.runner([1, 0]))
        # then
        self.assertEqual(0, code)
        self.assertEqual(['verification', 'repair'], [call[2] for call in self.calls])


class SetupTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def test_key_from_secrets_file_with_export_lines(self):
        # given
        (self.root/'secrets').write_text('export OTHER=1\nexport TYPESAFE_API_KEY="abc123"\n')
        environ = {'CODE_RISK_SECRETS': str(self.root/'secrets')}
        # when / then
        self.assertTrue(ask.load_key(environ))
        self.assertEqual('abc123', environ['TYPESAFE_API_KEY'])

    def test_key_from_secrets_directory(self):
        # given
        (self.root/'TYPESAFE_API_KEY').write_text('xyz789\n')
        environ = {'CODE_RISK_SECRETS': str(self.root)}
        # when / then
        self.assertTrue(ask.load_key(environ))
        self.assertEqual('xyz789', environ['TYPESAFE_API_KEY'])

    def test_exported_key_wins_and_missing_secrets_mean_no_jev(self):
        environ = {'TYPESAFE_API_KEY': 'shell', 'CODE_RISK_SECRETS': str(self.root/'none')}
        self.assertTrue(ask.load_key(environ))
        self.assertEqual('shell', environ['TYPESAFE_API_KEY'])
        self.assertFalse(ask.load_key({'CODE_RISK_SECRETS': str(self.root/'none')}))

    def test_relative_config_paths_resolve_from_config_directory(self):
        # given
        config = {'plan': 'plans/p.json', 'analyzer': '/abs/a.jar', 'documents': ['docs/spec.md'],
                  'routing': {'registry': 'models.json', 'policy': '~/policy.json', 'capability': {'calibration': 'cal.json'}}}
        # when
        result = ask.resolve_paths(config, self.root)
        # then
        self.assertEqual(str((self.root/'plans/p.json').resolve()), result['plan'])
        self.assertEqual('/abs/a.jar', result['analyzer'])
        self.assertEqual(['docs/spec.md'], result['documents'])
        self.assertEqual(str((self.root/'models.json').resolve()), result['routing']['registry'])
        self.assertEqual(str(Path('~/policy.json').expanduser()), result['routing']['policy'])
        self.assertEqual(str((self.root/'cal.json').resolve()), result['routing']['capability']['calibration'])
        self.assertEqual('plans/p.json', config['plan'])


class ZeroConfigTests(unittest.TestCase):
    """A start.spring.io-shaped project with nothing added: ask finds it, drafts the inputs and keeps the session."""

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.project = Path(temp.name).resolve()/'demo'
        tests = self.project/'src/test/java/com/example/demo'
        tests.mkdir(parents=True)
        (self.project/'gradlew').write_text('')
        (self.project/'settings.gradle').write_text("rootProject.name = 'demo'")
        (tests/'DemoApplicationTests.java').write_text(
            'package com.example.demo;\nclass DemoApplicationTests {\n\t@Test\n\tvoid contextLoads() {\n\t}\n}\n')
        self.calls = []
        draft_spec = importlib.util.spec_from_file_location('test_ask_draft', Path(__file__).resolve().parent/'test_ask_draft.py')
        self.samples = importlib.util.module_from_spec(draft_spec)
        draft_spec.loader.exec_module(self.samples)

    def spec(self, **changes):
        """The person's spec file for the change."""
        path = self.project.parent/'spec.json'
        path.write_text(json.dumps(self.samples.sample(**changes), ensure_ascii=False), encoding='utf-8')
        return path

    def measure(self, project, output, base_ref, **options):
        report = {'status': 'FAIL', 'checks': {'checkstyle': 'FAIL'}, 'baseRef': base_ref, 'violations': [
            {'file': 'src/test/java/com/example/demo/DemoApplicationTests.java', 'line': 3, 'message': 'tab'}]}
        output.write_text(json.dumps(report))
        self.measured.append(base_ref)
        self.measured_off = options.get('off')
        return report

    def validators(self, configured):
        """The project's .code-risk.yml validators, as the analyzer jar would report them."""
        original, self.configured = ask._module, dict(configured)

        def module(name):
            loaded = original(name)
            if name == 'convention_check':
                loaded.settings = lambda project, *args, **kwargs: dict(self.configured)
            return loaded
        ask._module = module
        self.addCleanup(setattr, ask, '_module', original)

    def test_architecture_switched_off_in_the_project_config_is_skipped_with_its_reason(self):
        # given
        self.validators({'architecture': False, 'convention': None})
        # when
        value, output = self.start()
        # then: conventions still run, architecture is recorded as skipped for the whole session
        marker = json.loads((Path(value['state']).parent/ask.CONVENTION_MARKER).read_text())
        self.assertEqual((True, ['architecture']), (marker['enabled'], list(marker['off'])))
        self.assertEqual({'architecture': False, 'convention': None}, marker['validators'])
        self.assertIn('validators.architecture.enabled: false', self.measured_off['architecture'])
        self.assertIn('아키텍처 검사 건너뜀(SKIP)', output)

    def test_convention_switched_off_in_the_project_config_checks_nothing(self):
        self.validators({'architecture': None, 'convention': False})
        value, output = self.start()
        self.assertEqual([], self.measured)
        self.assertNotIn('convention', value)
        self.assertIn('컨벤션: 끔', output)
        self.assertIn('컨벤션 검사 건너뜀(SKIP) - .code-risk.yml validators.convention.enabled: false', output)

    def test_a_changed_validators_setting_stops_the_session_it_did_not_start(self):
        # given
        self.validators({'architecture': None, 'convention': None})
        self.start()
        # when: the project config switches architecture off after the draft
        self.configured = {'architecture': False, 'convention': None}
        # then: continuing would mix evidence from both settings
        for text in (None, '검증 다시 해줘'):
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, 'validators 설정이 이 세션을 시작한 뒤 바뀌었습니다'):
                ask.zero_config(text, self.project, None, None)

    def start(self, text='회원 가입 기능 추가해줘'):
        self.measured = []
        with redirect_stdout(io.StringIO()) as output:
            value = ask.start_change(text, self.project, ask.decide(text, None), None, None, self.spec(),
                                     lambda path, jar: None, self.measure)
        return value, output.getvalue()

    def test_request_wording_turns_conventions_on_or_off(self):
        cases = {'회원 가입 추가해줘': ask.CONVENTION_DEFAULT, '컨벤션 없이 회원 가입 추가해줘': False,
                 '코드 스타일 검사 빼고 고쳐줘': False, '컨벤션 무시하고 추가해줘': False,
                 '컨벤션 적용해서 회원 가입 추가해줘': True, '컨벤션 지켜서 고쳐줘': True}
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(expected, ask.convention_choice(text))

    def test_project_is_found_from_any_folder_inside_it(self):
        self.assertEqual(self.project, ask.find_project(self.project/'src/test/java'))
        with self.assertRaises(ValueError):
            ask.find_project(self.project.parent)

    def test_user_config_replaces_only_the_files_it_has(self):
        home = self.project.parent/'home'
        home.mkdir()
        (home/'routing-policy.json').write_text('{}')
        routing = ask.default_routing({'CODE_RISK_CONFIG_HOME': str(home)})
        self.assertEqual(str(home/'routing-policy.json'), routing['policy'])
        self.assertEqual(str(ask.DEFAULT_ROUTING/'models.json'), routing['registry'])

    def test_convention_fixes_use_the_cheap_tier_and_refuse_forbidden_models(self):
        configs = ask.fix_models(ask.default_routing({}))
        self.assertEqual(['gpt-6.1-sol', 'gpt-6.1-sol'], [config['model'] for config in configs])
        registry = json.loads((ask.DEFAULT_ROUTING/'models.json').read_text())
        registry['models']['general']['model'] = 'claude-opus-5-5'
        path = self.project.parent/'models.json'
        path.write_text(json.dumps(registry))
        with self.assertRaisesRegex(ValueError, '금지된 고비용 모델'):
            ask.fix_models(dict(ask.default_routing({}), registry=str(path)))

    def test_change_request_needs_the_persons_spec_and_calls_no_model(self):
        # given / when / then: without a spec file nothing is created
        with self.assertRaisesRegex(ValueError, '명세 파일이 필요합니다'):
            ask.zero_config('회원 가입 기능 추가해줘', self.project, None, None)
        self.assertFalse((self.project/'build/ask').exists())
        # a spec with open questions is not a spec yet
        with self.assertRaisesRegex(ValueError, '정하지 못한 질문'):
            ask.start_change('회원 가입 기능 추가해줘', self.project, ('CODE_CHANGE', 'k'), None, None,
                             self.spec(status='NEEDS_REVIEW', unknowns=['어떤 DB?'], requirements=[], files=[]))

    def test_change_request_drafts_a_new_session_and_waits_for_approval(self):
        # when
        value, output = self.start()
        # then
        session = Path(value['state']).parent
        self.assertEqual(self.project/'build'/'ask', session.parent)
        self.assertEqual(session.name, (self.project/'build/ask/current').read_text())
        self.assertEqual(value['config'], json.loads((session/'ask-config.json').read_text()))
        self.assertEqual(['baseline', 'generate-tests', 'generate-implementation', 'verification'], value['stages'])
        self.assertEqual({'project': str(self.project), 'session': str(session), 'baseRef': None}, value['convention'])
        self.assertEqual(['build/ask/' + session.name + '/request.md', 'build/ask/' + session.name + '/conventions.md'],
                         value['config']['documents'])
        self.assertIn('REQ-1 이메일로 회원 가입 (시나리오 2개)', output)
        self.assertIn('먼저 기존 코드 위반을 고칩니다 - src/test/java/com/example/demo/DemoApplicationTests.java', output)
        self.assertEqual([None], self.measured)
        self.assertFalse((session/'state.json').exists())

    def test_yes_alone_continues_the_current_session_with_its_saved_config(self):
        # given
        started, _ = self.start()
        # when
        value = ask.zero_config(None, self.project, None, None)
        # then
        self.assertEqual('CONTINUE', value['intent'])
        self.assertEqual('session', value['configSource'])
        self.assertEqual(started['config'], value['config'])
        self.assertEqual(started['stages'], value['stages'])

    def gate_runner(self, checks, commands):
        """Convention checks fail with the next entry of `checks` (a set of failing check names) until it is empty."""
        checks = list(checks)

        def run(command):
            commands.append(command)
            if command[1].endswith('convention_check.py'):
                failing = checks.pop(0) if checks else set()
                output = Path(command[command.index('--output') + 1])
                output.write_text(json.dumps({'status': 'FAIL' if failing else 'PASS',
                                              'checks': {name: 'FAIL' for name in failing}}))
                return 1 if failing else 0
            return 0
        return run

    def names(self, commands):
        return [Path(c[1]).name + (':' + c[c.index('--mode') + 1] if '--mode' in c else '') if 'convention' in c[1] else c[2]
                for c in commands]

    def test_new_code_violations_are_fixed_through_the_workflow_before_verification(self):
        # given
        value, _ = self.start()
        commands = []
        # when
        with redirect_stdout(io.StringIO()):
            code = ask.execute(value, True, self.gate_runner([{'architecture'}, {'checkstyle'}], commands))
        # then
        self.assertEqual(0, code)
        self.assertEqual(['convention_fix.py', 'baseline', 'generate-tests', 'generate-implementation',
                          'convention_check.py', 'convention_fix.py:implementation', 'convention_check.py',
                          'convention_fix.py:implementation', 'convention_check.py', 'verification'], self.names(commands))
        session = Path(value['state']).parent
        self.assertEqual(str(session/'convention-after.json'), commands[5][commands[5].index('--report') + 1])
        self.assertEqual(str(session/'convention-before.json'), commands[4][commands[4].index('--before') + 1])

    def test_violations_left_after_the_fix_rounds_stop_before_verification(self):
        value, _ = self.start()
        commands = []
        with redirect_stdout(io.StringIO()) as output:
            code = ask.execute(value, True, self.gate_runner([{'architecture'}] * 3, commands))
        self.assertEqual(1, code)
        self.assertNotIn('verification', self.names(commands))
        self.assertEqual(2, self.names(commands).count('convention_fix.py:implementation'))
        self.assertIn('남은 위반', output.getvalue())

    def test_failing_tests_are_left_to_verification_then_checked_once_more(self):
        value, _ = self.start()
        commands = []
        with redirect_stdout(io.StringIO()):
            code = ask.execute(value, True, self.gate_runner([{'tests'}, {'checkstyle'}], commands))
        self.assertEqual(1, code)
        self.assertEqual(['convention_check.py', 'verification', 'convention_check.py'], self.names(commands)[4:])

    def test_failed_cleanup_stops_before_the_baseline(self):
        value, _ = self.start()
        commands = []
        with redirect_stdout(io.StringIO()):
            code = ask.execute(value, True, lambda command: commands.append(command) or 1)
        self.assertEqual((1, 1), (code, len(commands)))

    def test_conventions_off_follow_existing_style_without_checks(self):
        # when
        value, output = self.start('컨벤션 없이 회원 가입 기능 추가해줘')
        commands = []
        with redirect_stdout(io.StringIO()):
            ask.execute(value, True, lambda command: commands.append(command) or 0)
        # then
        session = Path(value['state']).parent
        self.assertNotIn('convention', value)
        self.assertEqual(['build/ask/' + session.name + '/request.md'], value['config']['documents'])
        self.assertEqual([], self.measured)
        self.assertIn('컨벤션: 끔', output)
        self.assertEqual(['baseline', 'generate-tests', 'generate-implementation', 'verification'], [c[2] for c in commands])
        self.assertNotIn('convention', ask.zero_config(None, self.project, None, None))

    def test_convention_that_cannot_run_never_blocks_the_verified_change(self):
        value, _ = self.start()
        commands = []
        run = lambda command: commands.append(command) or (2 if command[1].endswith('convention_check.py') else 0)
        with redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, ask.execute(value, True, run))
        self.assertEqual('verification', commands[-1][2])
        self.assertIn('건너뜁니다', output.getvalue())

    def test_analysis_also_checks_conventions_and_runs_every_check(self):
        # given
        value = ask.zero_config('결제 코드 위험 분석해줘', self.project, None, None)
        calls = []
        # when
        with redirect_stdout(io.StringIO()):
            code = ask.execute(value, False, lambda command: calls.append(command) or (1 if len(calls) == 1 else 0))
        # then
        self.assertEqual(['scan', str(self.project)], value['commands'][0][3:])
        self.assertTrue(value['commands'][1][1].endswith('convention_check.py'))
        self.assertEqual((2, 1), (len(calls), code))

    def test_analysis_with_a_jev_key_asks_jev_about_what_static_rules_cannot_settle(self):
        # when: JEV judged the request, so a key is available
        value = ask.zero_config('결제 코드 위험 분석해줘', self.project, None,
                                lambda text: {'status': 'COMPLETED', 'intent': 'ANALYZE', 'confidence': 0.99})
        # then
        self.assertEqual(['scan', str(self.project), '--jev', 'true'], value['commands'][0][3:])

    def test_follow_up_without_a_session_explains_what_to_do_first(self):
        with self.assertRaisesRegex(ValueError, '진행 중인 작업이 없습니다'):
            ask.zero_config('검증 다시 해줘', self.project, None, None)

    def test_request_is_required_unless_continuing(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            ask.main(['--project', str(self.project)])


class UpdateTests(unittest.TestCase):
    class Updated(Exception):
        pass

    def updated_with(self, argv):
        seen = []

        def update(arguments):
            seen.append(arguments)
            raise self.Updated()

        with tempfile.TemporaryDirectory() as folder, redirect_stdout(io.StringIO()):
            try:
                # Without a spec file a change request stops right after the update point.
                ask.main([*argv, '--project', folder, '--no-jev'], update=update)
            except self.Updated:
                pass
        return seen

    def test_new_requests_update_first_with_the_same_arguments(self):
        # given / when
        seen = self.updated_with(['회원 가입 기능 추가해줘'])
        # then: the restarted process gets exactly what was typed
        self.assertEqual([['회원 가입 기능 추가해줘', '--project', seen[0][2], '--no-jev']], seen)
        self.assertEqual(1, len(self.updated_with(['결제 코드 위험 분석해줘'])))
        self.assertEqual(1, len(self.updated_with(['무슨 말인지 모를 문장'])))

    def test_continuing_a_session_keeps_the_version_that_started_it(self):
        for argv in (['--yes'], ['이어서 진행해'], ['검증 다시 해줘'], ['PR 올려줘'], ['기능 추가해줘', '--no-update']):
            with self.subTest(argv=argv):
                self.assertEqual([], self.updated_with(argv))


class ExampleSettingsTests(unittest.TestCase):
    def test_example_settings_are_valid_and_free_of_forbidden_models(self):
        router_spec = importlib.util.spec_from_file_location('model_router', Path(__file__).resolve().parents[1]/'model_router.py')
        router = importlib.util.module_from_spec(router_spec)
        router_spec.loader.exec_module(router)
        base = Path(__file__).resolve().parents[2]/'docs'/'examples'/'ask'
        config = ask.resolve_paths(json.loads((base/'config.json').read_text()), base)
        self.assertEqual(ask.default_routing({'CODE_RISK_CONFIG_HOME': str(base)}), config['routing'])
        registry = router.validate_registry(json.loads(Path(config['routing']['registry']).read_text()))
        router.validate_policy(json.loads(Path(config['routing']['policy']).read_text()), registry)
        self.assertTrue(router.settings(dict(config['routing'], capability={})))
        self.assertTrue(ask.models(dict(config, routing=dict(config['routing'], capability={}))))


eval_spec = importlib.util.spec_from_file_location('eval_ask', Path(__file__).resolve().parents[1]/'eval_ask.py')
evaluation = importlib.util.module_from_spec(eval_spec)
eval_spec.loader.exec_module(evaluation)


class EvaluationTests(unittest.TestCase):
    def reply(self, choice):
        value = IntentResponseTests.response(None, choice)
        return json.dumps(value).encode()

    def test_outcomes_separate_wrong_runs_from_safe_rejections(self):
        # given
        answers = {'재고 차감 버그 고쳐줘': 'CODE_CHANGE', '이 프로젝트 위험 분석해줘': 'CODE_CHANGE', '안녕': 'UNKNOWN'}
        items = [{'text': text, 'expected': expected} for text, expected in
                 (('재고 차감 버그 고쳐줘', 'CODE_CHANGE'), ('이 프로젝트 위험 분석해줘', 'ANALYZE'), ('안녕', 'REJECT'))]
        # when
        report = evaluation.evaluate(items, lambda text, timeout: self.reply(answers[text]))
        # then
        self.assertEqual({'CORRECT': 1, 'OVER_REJECT': 1, 'SAFE_REJECT': 1, 'JEV_FAILED': 0, 'JEV_ACCURACY': 0.667},
                         report['summary'])

    def test_invalid_response_keeps_raw_text_for_diagnosis(self):
        # when
        report = evaluation.evaluate([{'text': '검증 돌려줘', 'expected': 'VERIFY'}], lambda text, timeout: b'{"model": "x"}')
        # then
        row = report['rows'][0]
        self.assertEqual('{"model": "x"}', row['raw'])
        self.assertIn('ValueError', row['failure'])
        self.assertEqual('OVER_REJECT', row['outcome'])

    def test_labeled_sets_cover_every_intent_and_rejections_without_overlap(self):
        directory = Path(__file__).resolve().parents[1]/'eval'
        sets = [json.loads((directory/name).read_text(encoding='utf-8'))['items']
                for name in ('ask_intents.json', 'ask_intents_holdout.json')]
        for items in sets:
            self.assertEqual(set(ask.INTENT_QUESTION['criteria']) - {'UNKNOWN'} | {'REJECT'}, {item['expected'] for item in items})
        texts = [item['text'] for items in sets for item in items]
        self.assertEqual(len(texts), len(set(texts)))
        examples = {example for criterion in ask.INTENT_QUESTION['criteria'].values() for example in re.findall(r'"([^"]+)"', criterion)}
        self.assertGreater(len(examples), 10)
        self.assertFalse(set(texts) & examples)


if __name__ == '__main__':
    unittest.main()
