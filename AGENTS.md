# Agent Workspace Instructions

## Universal Adaptive Run Policy
This repository ships with the portable **Universal Adaptive Run Policy** kit under `.agents/`.

1. **Session startup (Cloud VM):** Run `.agents/install_run_policy.sh` at the start of any new cloud session to wire global Antigravity rules, hooks, `.git/info/exclude`, and the `pre-push` gate.
2. **Task contract:** For any actionable task (edits, builds, scripts, docs), maintain `.agents/run_contract.json` per `.agents/rules/run-policy.md`. Skip the contract for pure Q&A turns with no workspace changes.
3. **Completion gate:** Before reporting task completion, run:
   ```bash
   python3 .agents/hooks/run_policy_gate.py --cli
   ```
   and ensure it exits with code `0`.
