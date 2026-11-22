#!/usr/bin/env python3
"""Universal Adaptive Run Policy — PreToolUse Anti-Tampering Guard.

Reads PreToolUse JSON from stdin and denies attempts to modify, delete, or
disable hooks.json, run_policy_gate.py, or protect_policy.py unless
ALLOW_POLICY_EDIT=1 is set in the environment.
"""

from __future__ import annotations

import json
import os
import re
import sys
from typing import Any

DENY_REASON = "Modifying or disabling Run Policy hooks or scripts is forbidden during task execution."

PROTECTED_FILES = (
    "hooks.json",
    "run_policy_gate.py",
    "protect_policy.py",
)

FILE_PATH_KEYS = {
    "targetfile",
    "target_file",
    "filepath",
    "file_path",
    "absolutepath",
    "absolute_path",
    "path",
    "paths",
    "file",
    "filename",
}

COMMAND_KEYS = {
    "commandline",
    "command_line",
    "command",
    "cmd",
    "script",
}

READ_ONLY_TOOLS = {
    "view_file",
    "read_file",
    "list_dir",
    "find_by_name",
    "grep_search",
}

MUTATION_CMD_RE = re.compile(
    r"(?:^|[;&|]\s*|&&\s*|\|\|\s*)(?:rm|mv|cp|tee|truncate|chmod|chown|sed\s+-i|perl\s+-i|install|dd|unlink)\b"
    r"|[>|]{1,2}\s*[^\s]*"
    r"|\bDISABLE_RUN_POLICY\s*=\s*1\b"
    r"|\bALLOW_POLICY_EDIT\s*=\s*1\b",
    re.IGNORECASE,
)

SAFE_GATE_RUN_RE = re.compile(
    r"^\s*(?:python3?|/[^\s]*python3?)\s+[^\s;|&>]*run_policy_gate\.py(?:\s+--cli|\s+--workspace\s+\S+)*\s*$"
)


def emit(decision: str, reason: str | None = None) -> int:
    out: dict[str, str] = {"decision": decision}
    if reason:
        out["reason"] = reason
    sys.stdout.write(json.dumps(out) + "\n")
    sys.stdout.flush()
    return 0


def read_payload() -> dict[str, Any]:
    try:
        if sys.stdin is None or sys.stdin.isatty():
            return {}
        raw = sys.stdin.read()
        if not raw or not raw.strip():
            return {}
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def is_protected_path(value: str) -> bool:
    norm = value.replace("\\", "/").strip()
    base = os.path.basename(norm)
    return base in PROTECTED_FILES


def collect_values(obj: Any, keys: set[str], acc: list[str]) -> None:
    if isinstance(obj, dict):
        for k, v in obj.items():
            if str(k).lower() in keys:
                if isinstance(v, str):
                    acc.append(v)
                elif isinstance(v, list):
                    for item in v:
                        if isinstance(item, str):
                            acc.append(item)
            collect_values(v, keys, acc)
    elif isinstance(obj, list):
        for item in obj:
            collect_values(item, keys, acc)


def command_targets_policy(cmd: str) -> bool:
    stripped = cmd.strip()
    if not stripped:
        return False

    # Always block explicit attempts to disable the run policy via env var
    if re.search(r"\bDISABLE_RUN_POLICY\s*=\s*1\b", stripped):
        return True

    # Allow clean invocation of run_policy_gate.py --cli
    if SAFE_GATE_RUN_RE.match(stripped):
        return False

    mentions_protected = any(name in stripped for name in PROTECTED_FILES)
    if not mentions_protected:
        return False

    # Block any mutating shell operation or inline interpreter write mentioning a protected file
    if MUTATION_CMD_RE.search(stripped):
        return True
    if re.search(r"\b(?:python3?|node|ruby|perl)\s+-[ce]\b", stripped):
        return True

    return False


def main() -> int:
    payload = read_payload()

    if os.environ.get("ALLOW_POLICY_EDIT") == "1":
        return emit("allow")

    tool_name = str(
        payload.get("tool_name")
        or payload.get("toolName")
        or payload.get("name")
        or payload.get("tool")
        or ""
    ).strip().lower()

    if tool_name in READ_ONLY_TOOLS:
        return emit("allow")

    target_paths: list[str] = []
    collect_values(payload, FILE_PATH_KEYS, target_paths)
    for path_val in target_paths:
        if is_protected_path(path_val):
            return emit("deny", DENY_REASON)

    commands: list[str] = []
    collect_values(payload, COMMAND_KEYS, commands)
    for cmd_val in commands:
        if command_targets_policy(cmd_val):
            return emit("deny", DENY_REASON)

    return emit("allow")


if __name__ == "__main__":
    sys.exit(main())
