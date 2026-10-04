import copy
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


draft = load('ask_draft')
regression = load('regression_workflow')
JAR = SCRIPTS.parent/'build'/'libs'/'code-risk-analyzer-0.0.1-SNAPSHOT.jar'

SAMPLE = {
    'status': 'COMPLETED', 'unknowns': [], 'title': '회원 가입', 'assumptions': ['이메일은 대소문자를 구분하지 않는다'],
    'database': 'UNDECIDED',
    'requirements': [{
        'id': 'REQ-1', 'title': '이메일로 회원 가입',
        'domain': {'owner': 'member', 'aggregates': ['Member'], 'dataOwnership': ['member'], 'allowedDependencies': [],
                   'entryPoints': ['POST /members']},
        'preconditions': ['같은 이메일 회원이 없다'], 'postconditions': ['회원이 저장된다'], 'invariants': ['이메일은 유일하다'],
        'coreLogic': ['이메일 중복을 확인하고 회원을 만든다'],
        'transaction': {'applicability': 'APPLICABLE', 'reason': '저장', 'expectations': ['한 트랜잭션에서 저장한다']},
        'concurrency': {'applicability': 'APPLICABLE', 'reason': '동시 가입', 'expectations': ['같은 이메일 동시 가입은 하나만 성공한다']},
        'idempotency': {'applicability': 'NOT_APPLICABLE', 'reason': '중복은 거절한다', 'expectations': []},
        'consistency': {'applicability': 'NOT_APPLICABLE', 'reason': '단일 저장소', 'expectations': []},
        'scenarios': [
            {'id': 'SCN-1', 'type': 'NORMAL', 'given': '가입 이력 없음', 'when': '가입 요청', 'expectedOutcomes': ['회원 생성'],
             'testTypes': ['DOMAIN_UNIT']},
            {'id': 'SCN-2', 'type': 'DUPLICATE', 'given': '같은 이메일 존재', 'when': '가입 요청', 'expectedOutcomes': ['가입 거절'],
             'testTypes': ['API_SLICE']},
            {'id': 'SCN-3', 'type': 'CONCURRENCY', 'given': '가입 이력 없음', 'when': '같은 이메일 동시 가입',
             'expectedOutcomes': ['하나만 성공'], 'testTypes': ['REPOSITORY_DB']},
            {'id': 'SCN-4', 'type': 'ROLLBACK', 'given': '저장 중 오류', 'when': '가입 요청', 'expectedOutcomes': ['저장 안 됨'],
             'testTypes': ['REPOSITORY_DB']}],
        'implementationStatus': 'NOT_IMPLEMENTED', 'existingCode': []}],
    'files': ['src/main/java/com/example/demo/member/domain/model/Member.java',
              'src/test/java/com/example/demo/member/domain/model/MemberTest.java'],
}
SPRING_TEST = '''package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DemoApplicationTests {

\t@Test
\tvoid contextLoads() {
\t}

\t@Test
\t@DisplayName("표시 이름이 있는 테스트")
\tvoid named() {
\t}

}
'''


def sample(**changes):
    value = copy.deepcopy(SAMPLE)
    value.update(changes)
    return value


class Project:
    """A start.spring.io-shaped project with only the generated context test."""

    def __init__(self, case):
        temp = tempfile.TemporaryDirectory()
        case.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()/'demo'
        tests = self.root/'src/test/java/com/example/demo'
        tests.mkdir(parents=True)
        (self.root/'src/main/java/com/example/demo').mkdir(parents=True)
        (self.root/'gradlew').write_text('')
        (self.root/'build.gradle').write_text("plugins { id 'java' }")
        (tests/'DemoApplicationTests.java').write_text(SPRING_TEST)
        self.session = self.root/'build/ask/s1'


class ValidateTests(unittest.TestCase):
    def test_complete_draft_is_accepted(self):
        self.assertEqual(SAMPLE, draft.validate(copy.deepcopy(SAMPLE)))

    def test_an_applicable_perspective_needs_a_scenario_that_exercises_it(self):
        # given: concurrency applies but the concurrent scenario was left out
        value = sample()
        value['requirements'][0]['scenarios'] = [scenario for scenario in value['requirements'][0]['scenarios']
                                                 if scenario['type'] != 'CONCURRENCY']
        # when / then
        with self.assertRaisesRegex(ValueError, 'concurrency이 APPLICABLE이면 CONCURRENCY 시나리오가 필요합니다'):
            draft.validate(value)

    def test_conditional_schema_rules_are_enforced(self):
        def requirement(**changes):
            value = sample()
            value['requirements'][0].update(changes)
            return value
        cases = {
            'applicable without expectations': requirement(transaction={'applicability': 'APPLICABLE', 'reason': 'r', 'expectations': []}),
            'not applicable with expectations': requirement(
                idempotency={'applicability': 'NOT_APPLICABLE', 'reason': 'r', 'expectations': ['x']}),
            'new behavior linked to code': requirement(existingCode=[{'path': 'src/main/A.java', 'symbol': 'A'}]),
            'implemented without code': requirement(implementationStatus='IMPLEMENTED'),
            'missing invariant': requirement(invariants=[]),
            'duplicate postconditions': requirement(postconditions=['a', 'a']),
            'bad requirement id': requirement(id='R1'),
            'no scenarios': requirement(scenarios=[]),
            'blank text': sample(title=' '),
            'extra field': sample(extra='x'),
            'no test file': sample(files=['src/main/java/A.java', 'src/main/java/B.java']),
            'escaping path': sample(files=['src/main/../../etc/A.java', 'src/test/java/ATest.java']),
            'build file': sample(files=['build.gradle', 'src/main/java/A.java', 'src/test/java/ATest.java']),
            'completed with questions': sample(unknowns=['어떤 DB?']),
            'review without questions': sample(status='NEEDS_REVIEW'),
        }
        for name, value in cases.items():
            with self.subTest(name):
                with self.assertRaises(ValueError):
                    draft.validate(value)

    def test_review_request_with_questions_is_valid(self):
        value = sample(status='NEEDS_REVIEW', unknowns=['결제 수단은 무엇인가요?'], requirements=[], files=[])
        self.assertEqual(value, draft.validate(value))


class InputTests(unittest.TestCase):
    def setUp(self):
        self.project = Project(self)

    def test_existing_tests_use_display_names_as_the_report_does(self):
        self.assertEqual(['com.example.demo.DemoApplicationTests#contextLoads', 'com.example.demo.DemoApplicationTests#표시 이름이 있는 테스트'],
                         draft.existing_tests(self.project.root))

    def test_written_inputs_satisfy_the_workflow_contracts(self):
        # when
        path = draft.write_inputs(self.project.root, self.project.session, '회원 가입 추가해줘', sample(), JAR,
                                  {'registry': 'r', 'policy': 'p'}, guidance='# 규칙')
        # then
        session = self.project.session
        config = json.loads(path.read_text())
        self.assertEqual(['build/ask/s1/request.md', 'build/ask/s1/conventions.md'], config['documents'])
        self.assertEqual({'plan', 'requirements', 'scope', 'analyzer', 'documents', 'routing', 'qualityGates'}, set(config))
        # the changed production class gets the default coverage and mutation gates
        self.assertEqual({'targets': {'com.example.demo.member.domain.model.Member':
                                      'src/main/java/com/example/demo/member/domain/model/Member.java'}}, config['qualityGates'])
        plan = regression.validate_plan(json.loads((session/'plan.json').read_text()))
        self.assertEqual({'existing-tests': ['com.example.demo.DemoApplicationTests#contextLoads',
                                             'com.example.demo.DemoApplicationTests#표시 이름이 있는 테스트']}, plan['features'])
        self.assertEqual('NOT_APPLICABLE', plan['database'])
        scope = json.loads((session/'scope.json').read_text())
        self.assertEqual(str(self.project.root), scope['project'])
        self.assertEqual(SAMPLE['files'], scope['files'])
        requirements = json.loads((session/'requirements.json').read_text())
        self.assertEqual('NO_GIT_REVISION', requirements['codeRevision'])
        self.assertEqual({'SCN-1', 'SCN-2', 'SCN-3', 'SCN-4'}, set(requirements['requirements']['REQ-1']['scenarios']))
        self.assertIn('회원 가입 추가해줘', (session/'request.md').read_text())
        self.assertEqual('# 규칙', (session/'conventions.md').read_text())

    def test_quality_gates_can_be_turned_off(self):
        path = draft.write_inputs(self.project.root, self.project.session, '추가해줘', sample(), JAR,
                                  {'registry': 'r', 'policy': 'p'}, quality=False)
        self.assertNotIn('qualityGates', json.loads(path.read_text()))

    def test_project_without_tests_has_no_regression_baseline(self):
        (self.project.root/'src/test/java/com/example/demo/DemoApplicationTests.java').unlink()
        with self.assertRaises(ValueError):
            draft.write_inputs(self.project.root, self.project.session, '추가해줘', sample(), JAR, {})

    def test_prompt_carries_request_project_files_conventions_and_feedback(self):
        text = draft.prompt('회원 가입 추가해줘', self.project.root, 'files must include a test', '# 규칙 본문')
        self.assertIn('회원 가입 추가해줘', text)
        self.assertIn('src/test/java/com/example/demo/DemoApplicationTests.java', text)
        self.assertIn('void contextLoads()', text)
        self.assertIn('# 규칙 본문', text)
        self.assertIn('files must include a test', text)
        self.assertIn('concurrency -> CONCURRENCY', text)

    @unittest.skipUnless(JAR.is_file(), 'analyzer jar not built')
    def test_written_requirements_pass_the_analyzer_schema(self):
        draft.write_inputs(self.project.root, self.project.session, '회원 가입 추가해줘', sample(), JAR, {})
        draft.check_requirements(self.project.session/'requirements.json', JAR)


class SpecFileTests(unittest.TestCase):
    """The spec comes from a file the person wrote; nothing here calls a model."""

    def setUp(self):
        self.project = Project(self)
        self.path = self.project.root.parent/'spec.json'

    def write(self, value):
        self.path.write_text(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False), encoding='utf-8')
        return self.path

    def test_finished_spec_may_leave_out_status_and_unknowns(self):
        value = sample()
        del value['status'], value['unknowns']
        self.assertEqual(SAMPLE, draft.load(self.write(value)))

    def test_problems_name_where_they_are(self):
        broken = sample()
        del broken['requirements'][0]['domain']['owner']
        cases = {'{not json': '올바른 JSON', json.dumps(broken): 'requirements[0].domain: 필드가 맞지 않습니다 (없음: owner)',
                 json.dumps(sample(database='POSTGRES')): 'spec.database: 비어 있거나 허용되지 않은 값',
                 json.dumps(sample(status='NEEDS_REVIEW', unknowns=['어떤 DB?'], requirements=[], files=[])): '정하지 못한 질문'}
        for text, message in cases.items():
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, re.escape(message)):
                draft.load(self.write(text))
        with self.assertRaisesRegex(ValueError, '읽지 못했습니다'):
            draft.load(self.project.root/'missing.json')

    def test_prepare_writes_the_inputs_and_needs_the_analyzer_to_accept_them(self):
        checked = []
        config = draft.prepare(self.project.root, self.project.session, '회원 가입 추가해줘', sample(), JAR, {},
                               lambda path, analyzer: checked.append(path), rules='# 트리와 예시 코드')
        self.assertEqual([self.project.session/'requirements.json'], checked)
        self.assertEqual('# 트리와 예시 코드', (self.project.session/'conventions.md').read_text())
        self.assertTrue(config.is_file())

        def reject(path, analyzer):
            raise ValueError('Error: Requirements input violates JSON Schema')
        with self.assertRaisesRegex(ValueError, 'violates JSON Schema'):
            draft.prepare(self.project.root, self.project.session, '추가해줘', sample(), JAR, {}, reject)

    def test_the_documented_example_is_a_valid_spec(self):
        example = SCRIPTS.parent/'docs'/'examples'/'ask'/'spec.json'
        self.assertTrue(draft.load(example)['requirements'])


class DraftTests(unittest.TestCase):
    def setUp(self):
        self.project = Project(self)
        self.calls = []

    def caller(self, *results):
        results = list(results)

        def call(config, workspace, prompt, schema, validate):
            self.calls.append({'config': config, 'workspace': workspace, 'prompt': prompt})
            self.assertIs(draft.DRAFT_SCHEMA, schema)
            self.assertIs(draft.validate, validate)
            self.assertNotEqual(str(self.project.root), workspace)
            return results.pop(0)
        return call

    def run_draft(self, call, check=lambda path, analyzer: None):
        return draft.draft(self.project.root, self.project.session, '회원 가입 추가해줘', [{'model': 'first'}, {'model': 'second'}],
                           {}, JAR, call, check)

    def test_invalid_output_retries_once_on_the_fallback_model(self):
        # given
        call = self.caller({'status': 'INVALID_OUTPUT', 'failureKind': 'OUTPUT_CONTRACT', 'usage': {'input': 1}},
                           {'status': 'COMPLETED', 'output': sample(), 'usage': {'input': 2}})
        # when
        result = self.run_draft(call)
        # then
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual(['first', 'second'], [call['config']['model'] for call in self.calls])
        self.assertIn('previous draft was rejected', self.calls[1]['prompt'])
        self.assertEqual([{'input': 1}, {'input': 2}], result['usage'])
        self.assertTrue((self.project.session/'plan.json').is_file())

    def test_draft_prompt_gets_the_short_guidance_and_the_writers_get_the_full_rules(self):
        # given
        call = self.caller({'status': 'COMPLETED', 'output': sample()})
        # when
        draft.draft(self.project.root, self.project.session, '회원 가입 추가해줘', [{'model': 'first'}], {}, JAR, call,
                    lambda path, analyzer: None, guidance='# 파일 트리', rules='# 트리와 예시 코드')
        # then
        self.assertIn('# 파일 트리', self.calls[0]['prompt'])
        self.assertNotIn('# 트리와 예시 코드', self.calls[0]['prompt'])
        self.assertEqual('# 트리와 예시 코드', (self.project.session/'conventions.md').read_text())

    def test_questions_stop_before_any_file_is_written(self):
        call = self.caller({'status': 'NEEDS_REVIEW', 'output': sample(status='NEEDS_REVIEW', unknowns=['어떤 DB?'])})
        result = self.run_draft(call)
        self.assertEqual(('NEEDS_REVIEW', ['어떤 DB?']), (result['status'], result['questions']))
        self.assertEqual(1, len(self.calls))
        self.assertFalse((self.project.session/'requirements.json').exists())

    def test_provider_failure_is_not_retried(self):
        call = self.caller({'status': 'UNAVAILABLE', 'failureKind': 'BILLING', 'reason': 'balance'})
        result = self.run_draft(call)
        self.assertEqual(('FAILED', 'balance'), (result['status'], result['reason']))
        self.assertEqual(1, len(self.calls))

    def test_schema_rejection_is_fed_back_and_fails_after_last_model(self):
        # given
        def reject(path, analyzer):
            raise ValueError('Error: Requirements input violates JSON Schema')
        call = self.caller({'status': 'COMPLETED', 'output': sample()}, {'status': 'COMPLETED', 'output': sample()})
        # when
        result = self.run_draft(call, reject)
        # then
        self.assertEqual('FAILED', result['status'])
        self.assertIn('violates JSON Schema', result['reason'])
        self.assertIn('violates JSON Schema', self.calls[1]['prompt'])


    def test_drafted_spec_is_saved_without_status_for_editing(self):
        result = self.run_draft(self.caller({'status': 'COMPLETED', 'output': sample()}))
        saved = json.loads((self.project.session/'spec.json').read_text())
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual(SAMPLE['files'], saved['files'])
        self.assertNotIn('unknowns', saved)
        self.assertEqual(saved, {k: v for k, v in draft.load(self.project.session/'spec.json').items() if k not in ('status', 'unknowns')})


if __name__ == '__main__':
    unittest.main()
