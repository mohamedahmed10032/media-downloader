#!/usr/bin/env python3
"""Universal Adaptive Run Policy — Dual-Mode Deterministic Exit Gate.

Modes:
  1. Antigravity Stop Hook Mode (default):
     - Reads JSON payload from stdin safely.
     - Writes all logs ONLY to stderr or /tmp/antigravity_run_policy.log.
     - Prints ONLY valid JSON to stdout and exits 0:
         {"decision": "continue", "reason": "..."}
         {"decision": "stop"}
  2. CLI / Git Hook Mode (--cli):
     - Prints human-readable failure details to stderr.
     - Exits 1 on failure, 0 on pass.
"""

from __future__ import annotations

import argparse
import datetime
import fnmatch
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

LOG_FILE = "/tmp/antigravity_run_policy.log"
STATE_FILE = "/tmp/antigravity_run_policy_state.json"
CONTRACT_REL_PATH = os.path.join(".agents", "run_contract.json")
GATE_STATE_REL_PATH = os.path.join(".agents", "gate_state.json")
MAX_RETRIES = 4
CMD_TIMEOUT_SECONDS = 90
OUTPUT_TAIL_LINES = 25

DEFAULT_FORBIDDEN_PATTERNS = [
    "*.apk",
    "*.keystore",
    ".env",
    ".agents/run_contract.json",
    ".agents/gate_state.json",
]

CODE_EXTENSIONS = {
    ".py", ".js", ".ts", ".tsx", ".jsx", ".mjs", ".cjs",
    ".kt", ".kts", ".java", ".go", ".rs",
    ".c", ".cc", ".cpp", ".cxx", ".h", ".hpp",
    ".cs", ".rb", ".php", ".sh", ".bash", ".zsh",
    ".swift", ".scala", ".dart", ".lua", ".sql",
    ".html", ".css", ".scss", ".vue", ".svelte",
}

SKIP_DIFF_BASENAMES = {
    "run_policy_gate.py",
    "protect_policy.py",
    "test_run_policy.py",
}

STUB_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("TODO", re.compile(r"\bTODO\b")),
    ("FIXME", re.compile(r"\bFIXME\b")),
    ("NotImplementedError", re.compile(r"\bNotImplementedError\b")),
    ("pass # implement", re.compile(r"pass\s*#\s*implement", re.IGNORECASE)),
    ("// ... rest of", re.compile(r"//\s*\.\.\.\s*rest\s+of", re.IGNORECASE)),
]


def log_message(msg: str) -> None:
    """Append a timestamped log line to LOG_FILE without touching stdout."""
    ts = datetime.datetime.now(datetime.timezone.utc).isoformat()
    line = f"[{ts}] {msg}\n"
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line)
    except OSError:
        pass


def read_stdin_json() -> dict[str, Any]:
    """Read JSON from stdin safely; never crash on empty or malformed input."""
    try:
        if sys.stdin is None or sys.stdin.isatty():
            return {}
        raw = sys.stdin.read()
        if not raw or not raw.strip():
            return {}
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except Exception as exc:
        log_message(f"stdin JSON parse ignored: {exc}")
        return {}


def run_cmd(
    args: list[str] | str,
    cwd: str,
    shell: bool = False,
    timeout: int = 30,
) -> tuple[int, str, str]:
    """Execute a subprocess and return (returncode, stdout, stderr)."""
    try:
        proc = subprocess.run(
            args,
            cwd=cwd,
            shell=shell,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return proc.returncode, proc.stdout or "", proc.stderr or ""
    except subprocess.TimeoutExpired as exc:
        out = exc.stdout if isinstance(exc.stdout, str) else (exc.stdout.decode("utf-8", "replace") if exc.stdout else "")
        err = exc.stderr if isinstance(exc.stderr, str) else (exc.stderr.decode("utf-8", "replace") if exc.stderr else "")
        return 124, out, f"{err}\nCommand timed out after {timeout}s."
    except Exception as exc:
        return 1, "", str(exc)


def detect_workspace_root(start_dir: str) -> tuple[str, bool]:
    """Return (workspace_root, is_git_repo)."""
    rc, out, _ = run_cmd(["git", "rev-parse", "--show-toplevel"], cwd=start_dir, timeout=10)
    if rc == 0 and out.strip():
        return os.path.realpath(out.strip()), True
    return os.path.realpath(start_dir), False


def get_git_status_porcelain(workspace: str, is_git: bool) -> list[str]:
    """Return non-empty porcelain status lines."""
    if not is_git:
        return []
    rc, out, _ = run_cmd(["git", "status", "--porcelain"], cwd=workspace, timeout=15)
    if rc != 0:
        return []
    return [line for line in out.splitlines() if line.strip()]


def resolve_gate_state_path(workspace: str) -> str:
    env_path = os.environ.get("GUARDRAIL_GATE_STATE_FILE", "").strip()
    if env_path:
        return os.path.abspath(env_path)
    return os.path.join(workspace, GATE_STATE_REL_PATH)


def check_guardrail_gate_state(gate_state_path: str) -> list[str]:
    """Fail-closed check of .agents/gate_state.json for corruption or open gates."""
    if not os.path.exists(gate_state_path):
        return []

    try:
        with open(gate_state_path, "r", encoding="utf-8") as f:
            raw = f.read()
        if not raw.strip():
            return [
                f"Guardrail gate state `{GATE_STATE_REL_PATH}` is empty/corrupted (fail-closed). "
                "Restore valid state or verify pending tasks via `python3 tools/guardrail_mcp.py`."
            ]
        data = json.loads(raw)
    except Exception as exc:
        return [
            f"Guardrail gate state `{GATE_STATE_REL_PATH}` contains corrupted JSON (fail-closed): {exc}"
        ]

    if not isinstance(data, dict):
        return [
            f"Guardrail gate state `{GATE_STATE_REL_PATH}` must be a JSON object (fail-closed)."
        ]

    failures: list[str] = []
    if "open_gates" in data:
        open_gates = data.get("open_gates")
        if not isinstance(open_gates, dict):
            return [
                f"Guardrail gate state `{GATE_STATE_REL_PATH}` has invalid `open_gates` field (fail-closed)."
            ]
        for tid, info in open_gates.items():
            if not isinstance(info, dict):
                failures.append(
                    f"Guardrail gate `{tid}` in `{GATE_STATE_REL_PATH}` has corrupted entry (fail-closed)."
                )
                continue
            task_id = str(info.get("task_id") or tid)
            targets = info.get("targets") if isinstance(info.get("targets"), list) else []
            failures.append(
                f"Open guardrail gate pending verification: task_id={task_id}, "
                f"targets={json.dumps(targets)}. "
                f"Run `python3 tools/guardrail_mcp.py verify --task-id {task_id}` before completing."
            )

    if str(data.get("status") or "").strip().upper() == "OPEN":
        task_id = str(data.get("task_id") or "unknown")
        targets = data.get("targets") if isinstance(data.get("targets"), list) else []
        failures.append(
            f"Open guardrail gate pending verification: task_id={task_id}, "
            f"targets={json.dumps(targets)}. "
            f"Run `python3 tools/guardrail_mcp.py verify --task-id {task_id}` before completing."
        )

    return failures


def compute_diff_fingerprint(
    workspace: str,
    is_git: bool,
    contract_path: str,
    gate_state_path: str = "",
) -> str:
    """Compute a deterministic fingerprint of the current workspace diff, contract, and gate state."""
    hasher = hashlib.sha256()
    if is_git:
        for cmd in (
            ["git", "status", "--porcelain"],
            ["git", "diff", "--no-color"],
            ["git", "diff", "--cached", "--no-color"],
        ):
            _, out, _ = run_cmd(cmd, cwd=workspace, timeout=15)
            hasher.update(out.encode("utf-8", "replace"))
            hasher.update(b"\0")
    for extra_path in (contract_path, gate_state_path):
        if extra_path and os.path.isfile(extra_path):
            try:
                with open(extra_path, "rb") as f:
                    hasher.update(f.read())
                hasher.update(b"\0")
            except OSError:
                pass
    return hasher.hexdigest()[:16]


def load_state(state_path: str) -> dict[str, int]:
    try:
        if os.path.isfile(state_path):
            with open(state_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                return {str(k): int(v) for k, v in data.items() if isinstance(v, int)}
    except Exception:
        pass
    return {}


def save_state(state_path: str, state: dict[str, int]) -> None:
    try:
        with open(state_path, "w", encoding="utf-8") as f:
            json.dump(state, f, indent=2)
    except OSError as exc:
        log_message(f"failed to save circuit breaker state: {exc}")


def check_contract_completeness(
    workspace: str,
    contract_path: str,
    has_changes: bool,
) -> tuple[dict[str, Any] | None, list[str]]:
    """Validate .agents/run_contract.json existence, requirements, and expected_artifacts."""
    failures: list[str] = []
    if not os.path.isfile(contract_path):
        if has_changes:
            failures.append(
                "Missing task contract `.agents/run_contract.json` despite modified/untracked workspace files. "
                "Create `.agents/run_contract.json` with task_id, task_type, goal_summary, requirements, "
                "verification_commands, expected_artifacts, and forbidden_git_patterns."
            )
        return None, failures

    try:
        with open(contract_path, "r", encoding="utf-8") as f:
            contract = json.load(f)
    except Exception as exc:
        failures.append(f"Invalid JSON in `.agents/run_contract.json`: {exc}")
        return None, failures

    if not isinstance(contract, dict):
        failures.append("`.agents/run_contract.json` must contain a JSON object.")
        return None, failures

    reqs = contract.get("requirements")
    if not isinstance(reqs, list) or len(reqs) == 0:
        failures.append("Contract `requirements` must be a non-empty list.")
    else:
        for idx, item in enumerate(reqs, start=1):
            if not isinstance(item, dict):
                failures.append(f"Requirement #{idx} is not a valid JSON object.")
                continue
            rid = str(item.get("id") or f"R{idx}")
            status = str(item.get("status") or "").strip().lower()
            evidence = str(item.get("evidence") or "").strip()
            missing_parts: list[str] = []
            if status != "verified":
                missing_parts.append(f"status='{status or 'missing'}' (expected 'verified')")
            if not evidence:
                missing_parts.append("empty evidence")
            if missing_parts:
                failures.append(f"Requirement {rid} is incomplete: {', '.join(missing_parts)}.")

    artifacts = contract.get("expected_artifacts") or []
    if not isinstance(artifacts, list):
        failures.append("Contract `expected_artifacts` must be a list of relative file paths.")
    else:
        for rel_path in artifacts:
            if not isinstance(rel_path, str) or not rel_path.strip():
                failures.append("Contract `expected_artifacts` contains an empty or non-string path.")
                continue
            full_path = os.path.join(workspace, rel_path)
            if not os.path.isfile(full_path):
                failures.append(f"Expected artifact missing: `{rel_path}`.")
            else:
                try:
                    if os.path.getsize(full_path) <= 0:
                        failures.append(f"Expected artifact is empty (0 bytes): `{rel_path}`.")
                except OSError as exc:
                    failures.append(f"Could not stat expected artifact `{rel_path}`: {exc}")

    return contract, failures


def tail_lines(text: str, max_lines: int = OUTPUT_TAIL_LINES) -> str:
    lines = text.splitlines()
    if len(lines) <= max_lines:
        return "\n".join(lines).strip()
    return "\n".join(lines[-max_lines:]).strip()


def check_verification_commands(
    workspace: str,
    contract: dict[str, Any] | None,
    status_lines: list[str],
) -> list[str]:
    """Run verification_commands or adaptive fallbacks for coding/bugfix/refactor tasks."""
    if not contract:
        return []

    failures: list[str] = []
    raw_cmds = contract.get("verification_commands") or []
    if not isinstance(raw_cmds, list):
        return ["Contract `verification_commands` must be a list of shell commands."]

    cmds = [str(c).strip() for c in raw_cmds if isinstance(c, str) and c.strip()]
    task_type = str(contract.get("task_type") or "").strip().lower()

    if cmds:
        for cmd in cmds:
            rc, out, err = run_cmd(cmd, cwd=workspace, shell=True, timeout=CMD_TIMEOUT_SECONDS)
            if rc != 0:
                combined = "\n".join(part for part in (out.strip(), err.strip()) if part)
                snippet = tail_lines(combined, OUTPUT_TAIL_LINES)
                msg = f"Verification command failed (exit {rc}): `{cmd}`"
                if snippet:
                    msg += f"\n  Last {OUTPUT_TAIL_LINES} lines:\n  " + snippet.replace("\n", "\n  ")
                failures.append(msg)
        return failures

    if task_type in {"coding", "bugfix", "refactor"}:
        auto_checks_ran = 0

        invariants_script = os.path.join(workspace, "app", "src", "test", "scripts", "check_source_invariants.py")
        if os.path.isfile(invariants_script):
            auto_checks_ran += 1
            rc, out, err = run_cmd(
                [sys.executable, invariants_script],
                cwd=workspace,
                timeout=CMD_TIMEOUT_SECONDS,
            )
            if rc != 0:
                combined = "\n".join(part for part in (out.strip(), err.strip()) if part)
                failures.append(
                    f"Auto-check failed (exit {rc}): `python3 app/src/test/scripts/check_source_invariants.py`\n"
                    f"  {tail_lines(combined, OUTPUT_TAIL_LINES)}"
                )

        modified_py: list[str] = []
        for line in status_lines:
            if len(line) < 4:
                continue
            status_code = line[:2]
            if "D" in status_code:
                continue
            rel = line[3:].strip()
            if " -> " in rel:
                rel = rel.split(" -> ", 1)[1].strip()
            if rel.endswith(".py"):
                full = os.path.join(workspace, rel)
                if os.path.isfile(full):
                    modified_py.append(rel)

        for py_rel in sorted(set(modified_py)):
            auto_checks_ran += 1
            rc, out, err = run_cmd(
                [sys.executable, "-m", "py_compile", py_rel],
                cwd=workspace,
                timeout=30,
            )
            if rc != 0:
                combined = "\n".join(part for part in (out.strip(), err.strip()) if part)
                failures.append(
                    f"Auto-check failed (exit {rc}): `python3 -m py_compile {py_rel}`\n"
                    f"  {tail_lines(combined, OUTPUT_TAIL_LINES)}"
                )

        if auto_checks_ran == 0:
            failures.append(
                f"No `verification_commands` specified for `{task_type}` task and no automated fallback checks ran. "
                "Add at least one concrete verification command to `verification_commands`."
            )

    return failures


def should_scan_file(rel_path: str) -> bool:
    norm = rel_path.replace("\\", "/")
    base = os.path.basename(norm)
    if base in SKIP_DIFF_BASENAMES:
        return False
    if "/fixtures/" in f"/{norm}" or norm.startswith("fixtures/"):
        return False
    _, ext = os.path.splitext(base)
    return ext.lower() in CODE_EXTENSIONS


def scan_diff_for_stubs(workspace: str, is_git: bool) -> list[str]:
    """Scan newly added lines (+) in staged and unstaged git diffs for lazy placeholders."""
    if not is_git:
        return []

    hits: list[str] = []
    seen: set[tuple[str, str, str]] = set()

    for diff_cmd in (
        ["git", "diff", "-U0", "--no-color"],
        ["git", "diff", "--cached", "-U0", "--no-color"],
    ):
        rc, out, _ = run_cmd(diff_cmd, cwd=workspace, timeout=20)
        if rc != 0 or not out:
            continue

        current_file = ""
        for line in out.splitlines():
            if line.startswith("+++ b/"):
                current_file = line[6:].strip()
                continue
            if line.startswith("+++ "):
                current_file = ""
                continue
            if not line.startswith("+") or line.startswith("+++"):
                continue
            if not current_file or not should_scan_file(current_file):
                continue

            added = line[1:]
            for label, pattern in STUB_PATTERNS:
                if pattern.search(added):
                    snippet = added.strip()
                    if len(snippet) > 100:
                        snippet = snippet[:97] + "..."
                    key = (current_file, label, snippet)
                    if key not in seen:
                        seen.add(key)
                        hits.append(f"Anti-stub violation in `{current_file}` ({label}): `{snippet}`")
                    break

    return hits


def check_git_hygiene(
    workspace: str,
    is_git: bool,
    contract: dict[str, Any] | None,
) -> list[str]:
    """Block staged files matching forbidden_git_patterns."""
    if not is_git:
        return []

    rc, out, _ = run_cmd(["git", "diff", "--cached", "--name-only"], cwd=workspace, timeout=15)
    if rc != 0 or not out.strip():
        return []

    patterns: list[str] = list(DEFAULT_FORBIDDEN_PATTERNS)
    if contract and isinstance(contract.get("forbidden_git_patterns"), list):
        for pat in contract["forbidden_git_patterns"]:
            if isinstance(pat, str) and pat.strip() and pat.strip() not in patterns:
                patterns.append(pat.strip())

    failures: list[str] = []
    for staged_file in [line.strip() for line in out.splitlines() if line.strip()]:
        norm = staged_file.replace("\\", "/")
        base = os.path.basename(norm)
        for pat in patterns:
            if fnmatch.fnmatch(norm, pat) or fnmatch.fnmatch(base, pat):
                failures.append(f"Forbidden staged file `{staged_file}` matches pattern `{pat}`.")
                break

    return failures


def emit_hook_decision(decision: str, reason: str | None = None) -> int:
    obj: dict[str, str] = {"decision": decision}
    if reason:
        obj["reason"] = reason
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Universal Adaptive Run Policy Exit Gate")
    parser.add_argument("--cli", action="store_true", help="Run in CLI / Git hook mode (exit 1 on failure)")
    parser.add_argument("--workspace", default="", help="Optional workspace directory override")
    args = parser.parse_args()

    cli_mode = args.cli
    payload = {} if cli_mode else read_stdin_json()

    # 1. Safe Bypass Guards
    if not cli_mode:
        if payload.get("terminationReason") == "error" or payload.get("fullyIdle") is False:
            log_message(
                f"Bypassing gate (terminationReason={payload.get('terminationReason')}, "
                f"fullyIdle={payload.get('fullyIdle')})"
            )
            return emit_hook_decision("stop")

    if os.environ.get("DISABLE_RUN_POLICY") == "1":
        log_message("Bypassing gate (DISABLE_RUN_POLICY=1)")
        if cli_mode:
            return 0
        return emit_hook_decision("stop")

    start_dir = args.workspace or os.getcwd()
    workspace, is_git = detect_workspace_root(start_dir)
    contract_path = os.path.join(workspace, CONTRACT_REL_PATH)
    gate_state_path = resolve_gate_state_path(workspace)
    status_lines = get_git_status_porcelain(workspace, is_git)

    # 2a. Guardrail Gate State (fail-closed on corruption or open tasks)
    gate_failures = check_guardrail_gate_state(gate_state_path)

    if not os.path.isfile(contract_path) and len(status_lines) == 0 and not gate_failures:
        log_message(f"Pure Q&A bypass in {workspace} (no contract, clean workspace, no open gates)")
        if cli_mode:
            return 0
        return emit_hook_decision("stop")

    # 3. Contract Completeness
    contract, failures = check_contract_completeness(
        workspace=workspace,
        contract_path=contract_path,
        has_changes=len(status_lines) > 0,
    )
    failures.extend(gate_failures)

    # 4. Adaptive Verification Commands
    failures.extend(check_verification_commands(workspace, contract, status_lines))

    # 5. Anti-Stub & Shortcut Diff Scan
    failures.extend(scan_diff_for_stubs(workspace, is_git))

    # 6. Git Hygiene Check
    failures.extend(check_git_hygiene(workspace, is_git, contract))

    # Build circuit breaker key
    task_id = "no-contract"
    if isinstance(contract, dict) and contract.get("task_id"):
        task_id = str(contract["task_id"])
    diff_fp = compute_diff_fingerprint(workspace, is_git, contract_path, gate_state_path)
    state_key = f"{workspace}::{task_id}::{diff_fp}"
    state_path = os.environ.get("RUN_POLICY_STATE_FILE", STATE_FILE)

    if not failures:
        if not cli_mode:
            state = load_state(state_path)
            if state_key in state:
                del state[state_key]
                save_state(state_path, state)
            log_message(f"Gate PASSED for {workspace} (task_id={task_id})")
            return emit_hook_decision("stop")
        return 0

    bullet_reason = "Run Policy Gate blocked completion:\n" + "\n".join(f"- {item}" for item in failures)

    if cli_mode:
        sys.stderr.write(bullet_reason + "\n")
        sys.stderr.flush()
        return 1

    # 2. Circuit Breaker (Infinite Loop Guard) in hook mode
    state = load_state(state_path)
    retry_count = state.get(state_key, 0) + 1
    state[state_key] = retry_count
    save_state(state_path, state)

    if retry_count > MAX_RETRIES:
        msg = (
            f"Circuit breaker tripped after {MAX_RETRIES} failed retries "
            f"(key={state_key}). Allowing stop. Remaining failures:\n{bullet_reason}"
        )
        log_message(msg)
        sys.stderr.write(msg + "\n")
        sys.stderr.flush()
        return emit_hook_decision("stop")

    log_message(f"Gate BLOCKED (retry {retry_count}/{MAX_RETRIES}, key={state_key}): {failures}")
    return emit_hook_decision("continue", bullet_reason)


if __name__ == "__main__":
    sys.exit(main())
