import json
import unittest
from unittest.mock import patch

import test_code_change_workflow as base

controller = base.controller


class PullRequestStageTests(unittest.TestCase):
    # Reuse the controller fixture helpers without re-running the inherited workflow tests.
    locals().update({name: getattr(base.CodeChangeWorkflowTests, name) for name in
                     ('setUp', 'put', 'run_stage', 'writer', 'changes', 'implementation', 'verify', 'review')})

    def completed(self, approve=False):
        if approve:
            plan = json.loads((self.project/'plan.json').read_text())
            plan['approvedActions'].append('PULL_REQUEST')
            self.put('plan.json', json.dumps(plan))
        self.implementation()
        self.assertEqual('PASSED', self.verify()['status'])
        self.assertEqual('COMPLETED', self.review()['status'])
        self.assertEqual('COMPLETED', self.run_stage('complete')['status'])

    def git(self, dirty=False, pushed=True):
        def command(args):
            if args[:2] == ['git', 'status']:
                return ' M src/main/Service.java\n' if dirty else ''
            if args[:2] == ['git', 'rev-parse']:
                if '--abbrev-ref' in args:
                    return 'feature/change'
                if args[-1] == 'HEAD':
                    return 'a' * 40
                if args[-1] == '@{upstream}':
                    return 'a' * 40 if pushed else 'b' * 40
            if args[:3] == ['gh', 'pr', 'create']:
                return 'https://github.com/example/service/pull/7\n'
            raise AssertionError(args)
        return patch.object(controller.Controller, '_command', side_effect=command)

    def test_pull_request_requires_completed_session(self):
        self.implementation()
        with self.git() as command, self.assertRaisesRegex(ValueError, 'Stage transition rejected'):
            self.run_stage('pull-request', pull_request={'title': 'Fix boundary'})
        command.assert_not_called()

    def test_draft_summarizes_completed_evidence_without_submitting(self):
        self.completed()
        with self.git() as command:
            result = self.run_stage('pull-request', pull_request={'title': 'Fix boundary'})
        command.assert_not_called()
        self.assertEqual('DRAFTED', result['status'])
        body = (self.project/'build/session/pull-request.md').read_text()
        for expected in ('ServiceTest#works', 'PASSED', 'normal', 'rollback', 'src/main/Service.java',
                         'docs/spec.md', 'NO_REPORTED_RISKS', 'src/test/ServiceTest.java'):
            self.assertIn(expected, body)
        record = controller.regression.read_json(self.project/'build/session/pull-request.json')
        self.assertEqual(controller.regression.digest(self.project/'build/session/pull-request.md'), record['bodySha256'])
        self.assertEqual('COMPLETED', controller.regression.read_json(self.state)['phase'])

    def test_submission_requires_explicit_plan_approval(self):
        self.completed()
        with self.git() as command, self.assertRaisesRegex(ValueError, 'PULL_REQUEST'):
            self.run_stage('pull-request', pull_request={'title': 'Fix boundary', 'submit': True})
        command.assert_not_called()

    def test_submission_requires_committed_and_pushed_verified_sources(self):
        self.completed(approve=True)
        for dirty, pushed in ((True, True), (False, False)):
            with self.git(dirty=dirty, pushed=pushed) as command, self.assertRaises(ValueError):
                self.run_stage('pull-request', pull_request={'title': 'Fix boundary', 'submit': True})
            self.assertFalse(any(call.args[0][:2] == ['gh', 'pr'] for call in command.call_args_list))

    def test_approved_submission_opens_draft_pull_request_with_generated_body(self):
        self.completed(approve=True)
        with self.git() as command:
            result = self.run_stage('pull-request', pull_request={'title': 'Fix boundary', 'base': 'main', 'submit': True})
        self.assertEqual('SUBMITTED', result['status'])
        self.assertEqual('https://github.com/example/service/pull/7', result['url'])
        create = [call.args[0] for call in command.call_args_list if call.args[0][:3] == ['gh', 'pr', 'create']]
        self.assertEqual(1, len(create))
        self.assertIn('--draft', create[0])
        self.assertEqual('main', create[0][create[0].index('--base') + 1])
        self.assertEqual('feature/change', create[0][create[0].index('--head') + 1])
        self.assertEqual(str(self.project/'build/session/pull-request.md'), create[0][create[0].index('--body-file') + 1])
        self.assertEqual('https://github.com/example/service/pull/7',
                         controller.regression.read_json(self.state)['pullRequest']['url'])

    def test_invalid_options_and_source_drift_are_rejected(self):
        self.completed(approve=True)
        for options in ({}, {'title': ''}, {'title': 'a\nb'}, {'title': 'ok', 'base': '-x'},
                        {'title': 'ok', 'submit': 'yes'}, {'title': 'ok', 'extra': 1}):
            with self.git() as command, self.assertRaises(ValueError):
                self.run_stage('pull-request', pull_request=options)
            command.assert_not_called()
        self.put('src/main/Service.java', 'class Service { int changed; }')
        with self.git() as command, self.assertRaises(ValueError):
            self.run_stage('pull-request', pull_request={'title': 'ok', 'submit': True})
        command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
