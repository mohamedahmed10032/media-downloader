---
trigger: always_on
description: Universal Adaptive Run Policy — deterministic exit gating via dynamic task contracts and verification checks.
---

# Universal Adaptive Run Policy

This policy enforces deterministic verification before finishing any actionable task across any codebase or language.

## 1. Pure Q&A Bypass
If the user prompt is strictly a question, explanation, or discussion that requires **zero** file edits, build commands, or deliverables, do **not** create `.agents/run_contract.json`.

## 2. Dynamic Task Contract (`.agents/run_contract.json`)
Before making file edits or running build/automation tasks on **any** actionable prompt, create or overwrite `.agents/run_contract.json` in the repository/workspace root.

`.agents/run_contract.json` is excluded from git via `.git/info/exclude` and must never be committed.

Use this exact JSON schema:

```json
{
  "task_id": "<unique-short-id>",
  "task_type": "coding | bugfix | refactor | docs | automation",
  "goal_summary": "<one-line concrete goal>",
  "requirements": [
    {
      "id": "R1",
      "description": "<exact requirement from the user prompt>",
      "status": "pending | verified",
      "evidence": "<file path, test name, or command output proving completion>"
    }
  ],
  "verification_commands": [
    "<exact deterministic shell commands that verify this task>"
  ],
  "expected_artifacts": [
    "<relative paths of files that must exist and be non-empty>"
  ],
  "forbidden_git_patterns": [
    "*.apk",
    "*.keystore",
    ".env",
    ".agents/run_contract.json"
  ]
}
```

## 3. Mandatory Multi-File Guardrail CLI (Zero-UI-Setup)
For **all multi-file operations (2+ files)** — renaming, moving, modifying, creating, or deleting — you **must** execute `python3 tools/guardrail_mcp.py` directly via Terminal. Never use raw `mv`/`rm` shell loops.

1. **Multi-file renames/moves (2+ files):**
   ```bash
   python3 tools/guardrail_mcp.py rename --expected-count N --ops-json '[{"src":"old1","dst":"new1"},{"src":"old2","dst":"new2"}]'
   ```
   (or `--ops-file path/to/ops.json`). Only proceed if `"gate_status": "PASSED"` and exit code `0`.

2. **Multi-file modify / create / delete (2+ files):**
   - **Open a gate first** (persisted in `.agents/gate_state.json`):
     ```bash
     python3 tools/guardrail_mcp.py open --mode modify|create|delete [--glob PATTERN] [--regex PATTERN] [--root DIR] [--targets ...]
     ```
   - Perform the edits/deletions across the registered target files.
   - **Verify and close the gate** using the `task_id` returned by `open`:
     ```bash
     python3 tools/guardrail_mcp.py verify --task-id <ID> [--cmd "..."] [--forbidden-regex "..."] [--required-regex "..."]
     ```
   - Never manually edit or delete `.agents/gate_state.json`.

## 4. Completion Requirements
Before ending an actionable task:
1. Every item in `requirements` must have `"status": "verified"` and non-empty `"evidence"` referencing actual files, test names, or observed command output.
2. Every file listed in `expected_artifacts` must exist and be non-empty.
3. Every command in `verification_commands` must exit with code `0`.
4. All open guardrail gates in `.agents/gate_state.json` must be closed via `python3 tools/guardrail_mcp.py verify --task-id <ID>` exiting `0` with `"gate_status": "PASSED"`.
5. Newly added diff lines in code files must contain no lazy placeholders (`TODO`, `FIXME`, `NotImplementedError`, `pass # implement`, `// ... rest of`).
6. No staged files may match `forbidden_git_patterns` (including `.agents/run_contract.json` and `.agents/gate_state.json`).
7. Run `python3 .agents/hooks/run_policy_gate.py --cli` and confirm it exits `0` before reporting completion.

