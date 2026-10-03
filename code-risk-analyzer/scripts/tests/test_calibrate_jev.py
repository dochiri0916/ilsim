import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('calibration', Path(__file__).resolve().parents[1]/'calibrate_jev.py')
calibration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(calibration)


class CalibrationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.project = Path(temporary.name).resolve()
        (self.project/'local.py').write_text('def compute():\n    return 1\n')
        self.corpus = self.project/'corpus.json'
        self.cases = [{'id': split+str(index), 'split': split, 'task': 'Change the local return value to two',
                       'context': [{'path': 'local.py', 'symbol': 'compute'}], 'expectedTier': 'TIER_2', 'expectedRisk': 'LOW'}
                      for split in ('calibration', 'holdout') for index in range(4)]
        self.corpus.write_text(json.dumps({'version': 1, 'cases': self.cases}))

    def test_real_source_symbols_and_labels_are_bound_and_wrong_acceptance_fails_gate(self):
        # given
        result = {'status': 'COMPLETED', 'requiredTier': 'TIER_2', 'risk': 'LOW', 'modelSnapshot': 'jev-1.13.0'}
        def judge(config, state, decode):
            return dict(result, stateSha256=calibration.state_sha(state))
        with patch.object(calibration.jev, 'judge', side_effect=judge):
            # when
            report = calibration.evaluate(self.project, self.corpus, self.project/'output', versions=(2,))
        # then
        self.assertEqual('PASSED', report['profiles']['2']['status'])
        receipt = self.project/'output/summary.json'
        config = calibration.router.capability_settings({'questionVersion': 2})
        calibration.validate_receipt(receipt, config)
        (self.project/'local.py').write_text('def compute():\n    return 3\n')
        with self.assertRaises(ValueError): calibration.validate_receipt(receipt, config)
        result['risk'] = 'HIGH'
        with patch.object(calibration.jev, 'judge', side_effect=judge):
            report = calibration.evaluate(self.project, self.corpus, self.project/'wrong', versions=(2,))
        self.assertEqual('NEEDS_REVIEW', report['profiles']['2']['status'])
        with self.assertRaises(ValueError): calibration.validate_receipt(self.project/'wrong/summary.json', config)

    def test_holdout_under_routing_and_unknown_acceptance_are_visible_independently(self):
        # given
        records = [{'id': 'critical', 'split': 'holdout', 'expectedTier': 'TIER_4', 'expectedRisk': 'HIGH',
                    'result': {'status': 'COMPLETED', 'requiredTier': 'TIER_2', 'risk': 'LOW'}},
                   {'id': 'unknown', 'split': 'holdout', 'expectedTier': 'UNKNOWN', 'expectedRisk': 'UNKNOWN',
                    'result': {'status': 'COMPLETED', 'requiredTier': 'TIER_1', 'risk': 'LOW'}}]
        # when
        metrics = calibration.metrics(records, 'holdout')
        # then
        self.assertEqual(['critical'], metrics['acceptedWrong'])
        self.assertEqual(['critical'], metrics['highRiskUnderRouting'])
        self.assertEqual(['unknown'], metrics['unknownAccepted'])

    def test_corpus_cannot_escape_use_missing_symbols_or_drop_holdouts(self):
        for replacement in ({'path': '../outside'}, {'path': 'local.py', 'symbol': 'missing'}):
            corpus = {'version': 1, 'cases': [dict(case, context=[replacement]) for case in self.cases]}
            self.corpus.write_text(json.dumps(corpus))
            with self.assertRaises((ValueError, OSError)): calibration.load_cases(self.project, self.corpus)
        self.corpus.write_text(json.dumps({'version': 1, 'cases': self.cases[:4]}))
        with self.assertRaises(ValueError): calibration.load_cases(self.project, self.corpus)

    def test_stability_uses_complete_frozen_trials_and_preserves_failed_observations(self):
        def judge(config, state, decode):
            return {'status': 'COMPLETED', 'requiredTier': 'TIER_2', 'risk': 'LOW',
                    'modelSnapshot': 'jev-1.13.0', 'stateSha256': calibration.state_sha(state)}
        with patch.object(calibration.jev, 'judge', side_effect=judge):
            calibration.evaluate(self.project, self.corpus, self.project/'one', versions=(2,))
            calibration.evaluate(self.project, self.corpus, self.project/'two', versions=(2,))
        paths = [self.project/'one/summary.json', self.project/'two/summary.json']
        report = calibration.combine_reports(paths, self.project/'combined.json')
        calibration.validate_receipt(self.project/'combined.json', calibration.router.capability_settings({'questionVersion': 2}))
        self.assertEqual(2, report['profiles']['2']['trials'])
        self.assertEqual(8, report['profiles']['2']['holdout']['cases'])
        second = json.loads(paths[1].read_text())
        second['profiles']['2']['records'][0]['result']['risk'] = 'HIGH'
        paths[1].write_text(json.dumps(second))
        report = calibration.combine_reports(paths, self.project/'combined.json')
        self.assertEqual('NEEDS_REVIEW', report['profiles']['2']['status'])
        second['profiles']['2']['records'][0]['result']['stateSha256'] = 'different'
        paths[1].write_text(json.dumps(second))
        with self.assertRaises(ValueError): calibration.combine_reports(paths, self.project/'combined.json')


if __name__ == '__main__':
    unittest.main()
