---
name: iter-fix
description: Iteratively diagnose and repair an existing defect until explicit acceptance checks pass. Use when a program builds, test, runtime behavior, integration, or regression is failing and the user wants an inspect-patch-run-verify loop rather than a one-shot suggestion. Preserve unrelated work, find root causes, apply the smallest complete fix, and stop on verified success or a concrete blocker.
---

# Iter-Fix

Run an evidence-driven repair loop. Treat passing validation—not editing code—as the finish line.

## Establish the repair contract

Before editing, state a compact contract:

- **Observed failure:** the reproducible symptom or failing command.
- **Expected behavior:** the externally visible result that should replace it.
- **Acceptance checks:** commands, tests, assertions, screenshots, or log conditions that can prove success.
- **Scope:** relevant components and explicit exclusions.

Derive the contract from existing evidence when it is unambiguous. Ask the user only when different interpretations would lead to materially different fixes.

If the failure cannot yet be reproduced, make reproduction the first acceptance check. Do not substitute “the code looks correct” for observable evidence.

## Protect the workspace

Inspect repository state before modifying files.

- Preserve all pre-existing changes and untracked files.
- Do not stage, commit, discard, move, or rewrite user changes unless explicitly requested.
- Never run blanket staging commands such as `git add -A` as part of this skill.
- Do not create repository logs or change journals by default. Keep iteration notes in the conversation.
- Avoid generated files and ignored paths unless the build or test workflow requires them.
- Request approval before destructive operations, broad migrations, dependency upgrades, or changes outside the stated scope.

Use version control as read-only evidence unless the user separately authorizes commits.

## Discover the project workflow

Read only what is needed to reproduce and understand the failure:

1. Repository instructions and the nearest applicable guidance files.
2. Build, test, and task definitions relevant to the failing component.
3. The failure output, stack trace, logs, recent related diff, and call path.
4. Focused source files and tests implicated by that evidence.

Prefer repository-defined commands over guessed language defaults. Do not scan every source file in a large repository.

Record the exact reproduction command and its exit code or observable result.

## Iterate

Repeat the following loop while each round produces new evidence.

### 1. Diagnose

- Reproduce the failure with the narrowest reliable check.
- Trace the failing value, state transition, or control path backward to its source.
- Distinguish the root cause from downstream symptoms.
- Form one falsifiable hypothesis: “Because X, Y fails; changing Z should make check C pass.”
- Add temporary instrumentation only when existing evidence is insufficient.

Do not patch several unrelated hypotheses in one round.

### 2. Patch

- Make the smallest **complete** change that addresses the root cause.
- Prefer correcting the violated invariant or contract over suppressing an error.
- Add or update a regression test when the behavior can be tested economically.
- Preserve public behavior outside the repair contract.
- Avoid opportunistic cleanup. A necessary refactor is allowed when it reduces risk or makes the root-cause fix possible; explain why it is necessary.

### 3. Verify narrowly

Run the fastest check that can falsify the hypothesis:

- the new regression test;
- the previously failing test or command;
- a focused build or type check;
- a deterministic runtime probe.

Capture the command, exit code, and meaningful result. If it fails, use the new evidence to revise the hypothesis rather than layering on another guess.

### 4. Verify broadly

After the narrow check passes, run proportionate regression checks:

- the affected test suite;
- required lint, formatting, type, or build checks;
- an end-to-end or runtime check when the bug is integration- or UI-dependent.

For GUI behavior, prefer an automated assertion or inspectable artifact. If only a human can judge the result, run all machine-checkable validations first and then request the smallest specific manual check.

### 5. Clean up

Remove temporary instrumentation, forced timeouts, test hooks, debug output, and generated artifacts that are not part of the final fix. Re-run any check whose behavior cleanup could affect.

## Decide when to stop

Finish only when all acceptance checks pass and no required cleanup remains.

Stop and report a blocker when any of these conditions holds:

- the next step requires credentials, unavailable hardware, external service state, or user interaction;
- a safe fix requires a product or architecture decision with multiple materially different options;
- the same hypothesis class fails twice without new evidence;
- three consecutive rounds make no measurable progress;
- continuing would require expanding scope or taking destructive action without authorization.

Do not use an arbitrary iteration count as proof that the problem is unsolvable.

## Report progress

Keep intermediate updates compact:

```text
Round N — hypothesis: ...
Evidence: command/result ...
Change: file/behavior ...
Status: narrow check pass|fail; next evidence sought ...
```

In the final response, lead with the verified outcome and include:

- root cause in one sentence;
- changed files or behaviors;
- exact validation commands and results;
- remaining limitations or manual checks, if any.

Never claim success from an unexecuted check, stale output, or inference alone.
