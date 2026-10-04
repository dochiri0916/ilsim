"""Derive the model Registry and routing Policy from published benchmark numbers.

config/ask/model-benchmarks.json records, per model and reasoning effort, the Artificial Analysis Intelligence Index and
the cost to run that index per task, plus the minimum index each capability tier needs. The routes follow from those
numbers instead of being picked by hand:

- primary: the cheapest allowed (model, effort) whose index meets the tier minimum;
- first fallback: the cheapest one with a strictly higher index than the primary (a stronger retry);
- second fallback: the cheapest one meeting the tier on the other CLI (codex/claude), so one provider outage is survivable.

Forbidden models (astra, fable, opus) and max effort are never selected. An unpublished cost is bounded by the next
higher effort of the same model (a higher effort never costs less); ties prefer the lower effort. Models change often:
update the numbers, run `python3 scripts/model_select.py --write`, review the diff, push.
"""
import argparse
import json
from pathlib import Path
import sys

SCRIPTS = Path(__file__).resolve().parent
CONFIG = SCRIPTS.parent/'config'/'ask'
TIERS = ('TIER_1', 'TIER_2', 'TIER_3', 'TIER_4')
EFFORTS = ('low', 'medium', 'high', 'xhigh', 'max')
FORBIDDEN_MODELS = ('astra', 'fable', 'opus')
FORBIDDEN_EFFORTS = ('max',)


def _number(value, name, allow_null=False):
    if value is None and allow_null:
        return None
    if type(value) not in (int, float) or value < 0:
        raise ValueError(f'{name} must be a nonnegative number')
    return value


def validate(value):
    if not isinstance(value, dict) or value.get('version') != 1 or not isinstance(value.get('models'), dict) \
            or not isinstance(value.get('tierRequirements'), dict) or set(value['tierRequirements']) != set(TIERS):
        raise ValueError('Benchmark file version 1 with models and requirements for every tier required')
    for tier, requirement in value['tierRequirements'].items():
        if not isinstance(requirement, dict) or set(requirement) != {'minIntelligenceIndex'}:
            raise ValueError(f'{tier} needs exactly minIntelligenceIndex')
        _number(requirement['minIntelligenceIndex'], f'{tier} minIntelligenceIndex')
    for identifier, entry in value['models'].items():
        if not isinstance(entry, dict) or set(entry) != {'adapter', 'model', 'efforts'} or entry['adapter'] not in ('codex', 'claude') \
                or not isinstance(entry['model'], str) or not isinstance(entry['efforts'], dict) or not entry['efforts']:
            raise ValueError(f'Invalid benchmark entry {identifier}')
        for effort, numbers in entry['efforts'].items():
            if effort not in EFFORTS or not isinstance(numbers, dict) or set(numbers) != {'intelligenceIndex', 'costPerTaskUsd'}:
                raise ValueError(f'Invalid benchmark numbers for {identifier} {effort}')
            _number(numbers['intelligenceIndex'], 'intelligenceIndex')
            _number(numbers['costPerTaskUsd'], 'costPerTaskUsd', allow_null=True)
    return value


def allowed(entry, effort):
    return effort not in FORBIDDEN_EFFORTS and not any(word in entry['model'].lower() for word in FORBIDDEN_MODELS)


def candidates(value):
    """Every selectable (profile, effort) with its index and cost (published, or bounded by a higher effort)."""
    result = []
    for identifier, entry in value['models'].items():
        efforts = [effort for effort in EFFORTS if effort in entry['efforts']]
        for position, effort in enumerate(efforts):
            if not allowed(entry, effort):
                continue
            numbers = entry['efforts'][effort]
            cost = numbers['costPerTaskUsd']
            if cost is None:
                cost = next((entry['efforts'][higher]['costPerTaskUsd'] for higher in efforts[position + 1:]
                             if entry['efforts'][higher]['costPerTaskUsd'] is not None), None)
            if cost is None:
                continue  # no published or bounding cost: cannot be compared
            result.append({'modelId': identifier, 'adapter': entry['adapter'], 'model': entry['model'], 'effort': effort,
                           'index': numbers['intelligenceIndex'], 'cost': cost,
                           'costKnown': numbers['costPerTaskUsd'] is not None})
    return result


def _cheapest(options):
    return min(options, key=lambda option: (option['cost'], EFFORTS.index(option['effort']), -option['index'], option['modelId']),
               default=None)


def routes(value):
    """{tier: [primary, fallback...]} chosen from the benchmark numbers."""
    value = validate(value)
    pool = candidates(value)
    chosen = {}
    for tier in TIERS:
        minimum = value['tierRequirements'][tier]['minIntelligenceIndex']
        meeting = [option for option in pool if option['index'] >= minimum]
        primary = _cheapest(meeting)
        if primary is None:
            raise ValueError(f'No allowed model meets {tier} (minimum index {minimum})')
        chain = [primary]
        stronger = _cheapest([option for option in meeting if option['index'] > primary['index']])
        if stronger:
            chain.append(stronger)
        other = _cheapest([option for option in meeting if option['adapter'] != primary['adapter']
                           and option['index'] >= primary['index'] and option not in chain])
        if other:
            chain.append(other)
        chosen[tier] = chain
    return chosen


def derive(value):
    """(registry, policy) in the shapes model_router.py validates."""
    chosen = routes(value)
    registry = {'version': 1, 'models': {}}
    for identifier, entry in value['models'].items():
        tiers = [tier for tier in TIERS if any(step['modelId'] == identifier for step in chosen[tier])]
        efforts = [effort for effort in EFFORTS if effort in entry['efforts'] and allowed(entry, effort)]
        if tiers and efforts:
            registry['models'][identifier] = {'adapter': entry['adapter'], 'model': entry['model'], 'status': 'ACTIVE',
                                              'supportedTiers': tiers, 'reasoningEfforts': efforts}
    policy = {'version': 1, 'tiers': {}}
    for tier, chain in chosen.items():
        steps = [{'modelId': step['modelId'], 'reasoningEffort': step['effort']} for step in chain]
        policy['tiers'][tier] = dict(steps[0], **({'fallbacks': steps[1:]} if steps[1:] else {}))
    return registry, policy


def describe(value):
    lines = [f"모델 근거: {value.get('source', '')} ({value.get('retrievedAt', '')})"]
    for tier, chain in routes(value).items():
        minimum = value['tierRequirements'][tier]['minIntelligenceIndex']
        steps = ' → '.join(f"{step['model']}/{step['effort']} (지수 {step['index']}, 과제당 "
                           f"{'' if step['costKnown'] else '≤'}${step['cost']})" for step in chain)
        lines.append(f'- {tier} (최소 {minimum}): {steps}')
    return '\n'.join(lines)


def _dump(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description='Derive models.json and routing-policy.json from benchmark numbers')
    parser.add_argument('--benchmarks', default=str(CONFIG/'model-benchmarks.json'))
    parser.add_argument('--write', action='store_true', help='write models.json and routing-policy.json next to the benchmarks')
    args = parser.parse_args(argv)
    path = Path(args.benchmarks)
    try:
        value = validate(json.loads(path.read_text(encoding='utf-8')))
        registry, policy = derive(value)
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print(f'벤치마크 거부: {error}')
        return 2
    print(describe(value))
    if args.write:
        (path.parent/'models.json').write_text(_dump(registry), encoding='utf-8')
        (path.parent/'routing-policy.json').write_text(_dump(policy), encoding='utf-8')
        print(f'{path.parent} 의 models.json, routing-policy.json을 다시 썼습니다')
    return 0


if __name__ == '__main__':
    sys.exit(main())
