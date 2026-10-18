import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("ai_cost_report", Path(__file__).parents[1] / "ai_cost_report.py")
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class CostReportTest(unittest.TestCase):
    def test_normalizes_different_completed_counts(self):
        result = report.compare({"costUsd": 10, "completedTasks": 5}, {"costUsd": 12, "completedTasks": 10})
        self.assertAlmostEqual(40, result["savingPercent"])
        self.assertFalse(result["qualityVerified"])

    def test_unknown_cost_cannot_be_reported_as_savings(self):
        for unknown in [{"unknownCalls": 1}, {"costIsLowerBound": True}, {"costIsUnverified": True}, {"legacyCostUsd": 0.1}]:
            self.assertIsNone(report.compare({"costUsd": 10, "completedTasks": 5}, {"costUsd": 0, "completedTasks": 5, **unknown})["savingPercent"])

    def test_incomplete_tasks_still_contribute_spend(self):
        stats = report.summarize([{"status": "COMPLETED", "costUsd": 1, "stages": [{"unknownCalls": 0}]},
                                  {"status": "BLOCKED", "costUsd": 2, "stages": [{"unknownCalls": 0}]}])
        self.assertEqual(3, stats["costPerCompletedTaskUsd"])

    def test_empty_zero_baseline_and_legacy_data(self):
        self.assertIsNone(report.compare([], [])["savingPercent"])
        self.assertIsNone(report.compare({"costUsd": 0, "completedTasks": 1}, {"costUsd": 1, "completedTasks": 1})["savingPercent"])
        self.assertTrue(report.summarize([{"status": "COMPLETED", "costUsd": 1, "stages": [{}]}])["costIsLowerBound"])

    def test_increases_remain_negative_and_invalid_values_fail(self):
        self.assertAlmostEqual(-50, report.compare({"costUsd": 2, "completedTasks": 1}, {"costUsd": 3, "completedTasks": 1})["savingPercent"])
        for cost in [-1, float("nan"), float("inf"), True]:
            with self.assertRaises(ValueError):
                report.summarize({"costUsd": cost, "completedTasks": 1})

    def test_accounting_correction_is_not_reported_as_real_savings(self):
        result = report.compare({"costUsd": 0.25, "completedTasks": 1, "costIsUnverified": True},
                                {"costUsd": 0.15, "completedTasks": 1})
        self.assertIsNone(result["savingPercent"])


if __name__ == "__main__":
    unittest.main()
