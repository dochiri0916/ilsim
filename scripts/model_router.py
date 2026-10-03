"""Resolve capability tiers to registered, active CLI model profiles."""
import math
from pathlib import Path
import re


VERSION = 1
TIERS = ('TIER_1', 'TIER_2', 'TIER_3', 'TIER_4')
EFFORTS = ('low', 'medium', 'high', 'xhigh', 'max')
STATUSES = ('DISCOVERED', 'CANDIDATE', 'BENCHMARKED', 'SHADOW', 'CANARY', 'ACTIVE', 'DEPRECATED', 'RETIRED')
IDENTIFIER = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,99}')
MODEL = re.compile(r'[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}')


def capability_settings(value):
    if not isinstance(value, dict) or not set(value) <= {'model', 'minConfidence', 'timeoutSeconds', 'questionVersion', 'calibration'}:
        raise ValueError('Invalid capability settings')
    model = value.get('model', 'jev-latest')
    if not isinstance(model, str) or not re.fullmatch(r'jev-[A-Za-z0-9][A-Za-z0-9._-]{0,60}', model):
        raise ValueError('Explicit Jev model required')
    confidence = value.get('minConfidence', 0.8)
    if type(confidence) not in (int, float) or not 0.5 <= confidence <= 1 or not math.isfinite(confidence):
        raise ValueError('Capability confidence must be between 0.5 and 1')
    timeout = value.get('timeoutSeconds', 30)
    if type(timeout) is not int or not 1 <= timeout <= 120:
        raise ValueError('Capability timeout must be between 1 and 120 seconds')
    result = {'model': model, 'minConfidence': confidence, 'timeoutSeconds': timeout}
    if 'questionVersion' in value:
        if type(value['questionVersion']) is not int or value['questionVersion'] not in (1, 2):
            raise ValueError('Known capability question version required')
        result['questionVersion'] = value['questionVersion']
    if 'calibration' in value:
        if not isinstance(value['calibration'], str) or not Path(value['calibration']).is_absolute():
            raise ValueError('Absolute calibration receipt required')
        result['calibration'] = value['calibration']
    return result


def settings(value):
    required = {'registry', 'policy'}
    if not isinstance(value, dict) or not required <= set(value) \
            or not set(value) <= required | {'requiredTiers', 'capability', 'timeoutSeconds', 'maxAttempts'} \
            or ('requiredTiers' in value) == ('capability' in value):
        raise ValueError('Choose explicit stage tiers or automatic capability judgment')
    for name in ('registry', 'policy'):
        path = value[name]
        if not isinstance(path, str) or not path or not Path(path).is_absolute():
            raise ValueError('Routing files require absolute paths')
    result = dict(value)
    if 'requiredTiers' in value:
        tiers = value['requiredTiers']
        if not isinstance(tiers, dict) or set(tiers) != {'test-writing', 'implementation'} or any(tier not in TIERS for tier in tiers.values()):
            raise ValueError('Explicit valid tiers required for both generation stages')
    else:
        result['capability'] = capability_settings(value['capability'])
    timeout = value.get('timeoutSeconds', 300)
    if type(timeout) is not int or not 1 <= timeout <= 3600:
        raise ValueError('timeoutSeconds must be an integer between 1 and 3600')
    attempts = value.get('maxAttempts', 1)
    if type(attempts) is not int or not 1 <= attempts <= 3:
        raise ValueError('maxAttempts must be between 1 and 3')
    return dict(result, timeoutSeconds=timeout, maxAttempts=attempts)


def validate_registry(value):
    if not isinstance(value, dict) or set(value) != {'version', 'models'} or type(value['version']) is not int or value['version'] != VERSION:
        raise ValueError('Model Registry version 1 required')
    models = value['models']
    if not isinstance(models, dict) or not models:
        raise ValueError('Nonempty model profiles required')
    fields = {'adapter', 'model', 'status', 'supportedTiers', 'reasoningEfforts'}
    for identifier, profile in models.items():
        if not isinstance(identifier, str) or not IDENTIFIER.fullmatch(identifier) or not isinstance(profile, dict) or set(profile) != fields:
            raise ValueError('Invalid model profile fields or identifier')
        if profile['adapter'] not in ('codex', 'claude') or profile['status'] not in STATUSES \
                or not isinstance(profile['model'], str) or not MODEL.fullmatch(profile['model']):
            raise ValueError('Invalid model adapter, status or name')
        for field, choices in (('supportedTiers', TIERS), ('reasoningEfforts', EFFORTS)):
            entries = profile[field]
            if not isinstance(entries, list) or not entries or any(not isinstance(entry, str) or entry not in choices for entry in entries) \
                    or len(set(entries)) != len(entries):
                raise ValueError('Distinct supported tiers and reasoning efforts required')
    return value


def validate_policy(value, registry):
    if not isinstance(value, dict) or set(value) != {'version', 'tiers'} or type(value['version']) is not int or value['version'] != VERSION:
        raise ValueError('Routing Policy version 1 required')
    tiers = value['tiers']
    if not isinstance(tiers, dict) or not tiers:
        raise ValueError('Nonempty tier routing required')
    for tier, route in tiers.items():
        if tier not in TIERS or not isinstance(route, dict) or not {'modelId', 'reasoningEffort'} <= set(route) \
                or not set(route) <= {'modelId', 'reasoningEffort', 'fallbacks'}:
            raise ValueError('Invalid tier routing fields')
        fallbacks = route.get('fallbacks', [])
        if not isinstance(fallbacks, list) or len(fallbacks) > 2:
            raise ValueError('At most two explicit fallback profiles permitted')
        seen = {}
        for index, step in enumerate([{'modelId': route['modelId'], 'reasoningEffort': route['reasoningEffort']}, *fallbacks]):
            if not isinstance(step, dict) or set(step) != {'modelId', 'reasoningEffort'}:
                raise ValueError('Invalid fallback fields')
            identifier = step['modelId']
            if not isinstance(identifier, str) or identifier not in registry['models']:
                raise ValueError('Routing references an unregistered model profile')
            profile = registry['models'][identifier]
            effort = step['reasoningEffort']
            if tier not in profile['supportedTiers'] or effort not in profile['reasoningEfforts']:
                raise ValueError('Routing exceeds registered tier or reasoning support')
            key = (profile['adapter'], profile['model'])
            if (index and effort == 'max') or (key in seen and EFFORTS.index(effort) <= seen[key]):
                raise ValueError('Fallback cannot repeat, reduce effort or automatically select max')
            seen[key] = EFFORTS.index(effort)
    return value


def select(registry, policy, tier, timeout_seconds=300, attempt=0):
    if not isinstance(tier, str) or tier not in TIERS or tier not in policy['tiers']:
        raise ValueError('No explicit route for the required tier')
    if type(timeout_seconds) is not int or not 1 <= timeout_seconds <= 3600:
        raise ValueError('Invalid routing timeout')
    primary = policy['tiers'][tier]
    chain = [primary, *primary.get('fallbacks', [])]
    if type(attempt) is not int or not 0 <= attempt < len(chain):
        raise ValueError('No explicit fallback for this attempt')
    route = chain[attempt]
    profile = registry['models'][route['modelId']]
    if profile['status'] != 'ACTIVE':
        raise ValueError('Only ACTIVE model profiles can be selected')
    config = {'provider': profile['adapter'], 'model': profile['model'], 'allowedModels': [profile['model']],
              'reasoningEffort': route['reasoningEffort'], 'timeoutSeconds': timeout_seconds}
    decision = {'requiredTier': tier, 'modelId': route['modelId'], 'provider': profile['adapter'],
                'model': profile['model'], 'reasoningEffort': route['reasoningEffort']}
    return config, decision
