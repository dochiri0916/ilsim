---
name: risk-workflow
description: Run the code-risk-analyzer workflow for a code task (new feature, behavior change, bug fix, refactor). Use when starting or verifying a change in a Spring/JPA project and you want risk scan, test-gap and mutation checks.
---

`CRA` = `java -jar <analyzer>/build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar` (rebuild with `./gradlew bootJar` if missing). `<project>` is the target project. JEV needs `TYPESAFE_API_KEY` (in `~/.secrets`); never print it.

Scope: stability (risk, test adequacy, mutation) verification and judgment only. Architecture and design review are out of scope; do not judge them here.

JEV output is advisory evidence only. Never treat a probability as proof, skip a check because of it, or register a mutation exception from it.

## 1. Classify the task
- **Refactor (behavior-preserving)**: go to *Refactor*.
- **Bug fix / small change**: `CRA route --change "<text>" --files <a,b>`.
  - `LIGHT` → *Light path*. `FULL` or any error → *Full path*.
- **New feature / design from scratch / behavior change touching money, stock, state, retries, messages, transactions**: *Full path* directly.

## Light path
1. `CRA scan <project> --fail-on off`, then `CRA baseline create <project>`.
2. `CRA prompt --change "<text>" --files <a,b> [--rules <file>]` and follow its workflow: failing test first, minimal change.
3. Write the changed file paths to a list, then `CRA scan <project> --files-file <list> --baseline .code-risk-baseline.json --fail-on`.
4. If a new TX/CON/IDEM finding appears, switch to *Full path*.

## Full path
1. Requirements: if no spec exists, write a short `requirements-analysis` JSON (`docs/requirements-analysis/`, mode `CODE_CHANGE`) from the request. Mark anything you are unsure of `UNKNOWN`; do not invent. Ask the user to resolve `UNKNOWN` items.
2. `CRA prompt <input.json> [--rules <file>]`. Exit 2 means incomplete input: fix it, do not bypass.
3. Tests first (confirm they fail for the expected reason), then minimal implementation.
4. `CRA scan <project> --jev true --fail-on` (cost is capped by `jev.max-calls` in `.code-risk.yml`; set it, e.g. 20), then `CRA mutation <project>`.
5. Fix and repeat step 4. Report REVIEW findings, TEST-001 gaps and JEV-001/JEV-002 diagnostics as unverified, not as passed.

## Refactor
- No behavior change is allowed. Do not edit tests to make them pass.
- Run the existing tests before and after, then `scan --files-file <changed> --baseline` as in *Light path* step 3.
- Any new finding or failing/changed test means behavior changed: stop and treat it as a behavior change (*Full path*).
- Run `mutation` when the refactor touches code with weak tests (TEST-001 PARTIAL/UNKNOWN).

## Exit codes
scan/mutation: 1 gate failed, 2 bad input/config, 3 rule or parse failure (takes precedence). Report the code and the diagnostics; do not retry blindly.
