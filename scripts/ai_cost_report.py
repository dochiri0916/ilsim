#!/usr/bin/env python3
import argparse
import json
import math
from pathlib import Path


def summarize(data):
    if not isinstance(data, (dict, list)):
        raise ValueError("JSON 객체 또는 작업 배열이 필요합니다")
    unverified = isinstance(data, dict) and (data.get("costIsUnverified", False) or data.get("legacyCostUsd", 0) > 0)
    if isinstance(data, dict) and "completedTasks" in data:
        cost = data["costUsd"]
        completed = data["completedTasks"]
        unknown = data.get("unknownCalls", 0)
        lower = data.get("costIsLowerBound", False) or unknown > 0
    else:
        tasks = data if isinstance(data, list) else data.get("tasks", [])
        if not isinstance(tasks, list):
            raise ValueError("완료 건수가 없는 /api/stats 대신 작업 목록 또는 completedTasks가 포함된 집계를 사용하세요")
        tasks = [item.get("task", item) for item in tasks]
        unverified = unverified or any(t.get("costIsUnverified", False) for t in tasks)
        completed = sum(t.get("status") == "COMPLETED" for t in tasks)
        cost = data.get("costUsd", sum(t.get("costUsd", 0) for t in tasks)) if isinstance(data, dict) else sum(t.get("costUsd", 0) for t in tasks)
        unknown = data.get("unknownCalls", 0) if isinstance(data, dict) and "unknownCalls" in data else sum(s.get("unknownCalls", 0) for t in tasks for s in t.get("stages", []))
        lower = (unknown > 0 or not isinstance(data, dict) or "costUsd" not in data
                 or data.get("costIsLowerBound", False)
                 or any(not t.get("stages") or any("unknownCalls" not in s for s in t["stages"]) for t in tasks))
    if not isinstance(cost, (int, float)) or isinstance(cost, bool) or not math.isfinite(cost) or cost < 0:
        raise ValueError("costUsd는 유효한 0 이상의 금액이어야 합니다")
    if not isinstance(completed, int) or isinstance(completed, bool) or completed < 0:
        raise ValueError("completedTasks는 0 이상의 정수여야 합니다")
    if not isinstance(unknown, int) or isinstance(unknown, bool) or unknown < 0:
        raise ValueError("unknownCalls는 0 이상의 정수여야 합니다")
    return {"costUsd": cost, "completedTasks": completed, "unknownCalls": unknown, "costIsUnverified": bool(unverified),
            "costIsLowerBound": lower, "costPerCompletedTaskUsd": cost / completed if completed else None}


def compare(before, after):
    first, second = summarize(before), summarize(after)
    old, new = first["costPerCompletedTaskUsd"], second["costPerCompletedTaskUsd"]
    measurable = old is not None and old > 0 and new is not None and not first["costIsLowerBound"] and not second["costIsLowerBound"] and not first["costIsUnverified"] and not second["costIsUnverified"]
    return {"before": first, "after": second,
            "savingPercent": 100 * (1 - new / old) if measurable else None,
            "qualityVerified": False,
            "basis": "완료 작업당 확인된 AI 비용. JEV·분석·재시도·표본 검증 비용을 양쪽 기간에 동일하게 포함해야 함",
            "note": "비용 미확인 또는 완료 작업 부족 시 절감률을 확정하지 않음. 품질 동등성과 실제 청구 절감은 별도 검증"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = compare(json.loads(args.before.read_text()), json.loads(args.after.read_text()))
    except (ValueError, KeyError, TypeError) as exc:
        parser.error(str(exc))
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
