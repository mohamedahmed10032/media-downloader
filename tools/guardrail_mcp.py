#!/usr/bin/env python3
"""Zero-UI-Setup Deterministic Guardrail CLI & Library.

Provides transactional multi-file rename and tracked open/verify gates
persisted in `.agents/gate_state.json` without requiring an MCP client UI.

Subcommands:
  - rename: Transactional multi-file rename with expected-count guard and automatic rollback.
  - open:   Discover/register target files for modify|create|delete and record an open gate.
  - verify: Validate target files, forbidden/required regexes, and commands to close a gate.

Exit codes:
  - 0 ONLY on `"gate_status": "PASSED"` (or successful `open` with `"gate_status": "OPEN"`).
  - Non-zero (1) on `"BLOCKED"` or `"ROLLED_BACK"`.
"""

from __future__ import annotations

import argparse
import datetime
import fnmatch
import json
import os
import re
import shutil
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Any

DEFAULT_STATE_REL_PATH = os.path.join(".agents", "gate_state.json")
IGNORED_WALK_DIRS = {
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    "__pycache__",
    "build",
    "node_modules",
    ".agents",
    ".agent",
}
CMD_TIMEOUT_SECONDS = 120
OUTPUT_TAIL_LINES = 25


def utc_now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def find_workspace_root(start_dir: str) -> str:
    """Resolve workspace root via git top-level, `.agents` parent, or start_dir."""
    abs_start = os.path.realpath(start_dir or os.getcwd())
    check_dir = abs_start if os.path.isdir(abs_start) else os.path.dirname(abs_start)
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            cwd=check_dir,
            capture_output=True,
            text=True,
            timeout=10,
        )
        if proc.returncode == 0 and proc.stdout.strip():
            return os.path.realpath(proc.stdout.strip())
    except Exception:
        pass

    cur = check_dir
    while True:
        if os.path.isdir(os.path.join(cur, ".agents")):
            return cur
        parent = os.path.dirname(cur)
        if parent == cur:
            break
        cur = parent
    return check_dir


def resolve_state_file(workspace_root: str, state_file: str | None = None) -> str:
    if state_file:
        return os.path.abspath(state_file)
    env_path = os.environ.get("GUARDRAIL_GATE_STATE_FILE", "").strip()
    if env_path:
        return os.path.abspath(env_path)
    return os.path.join(workspace_root, DEFAULT_STATE_REL_PATH)


def load_gate_state(state_path: str) -> tuple[dict[str, Any] | None, str | None]:
    """Load gate_state.json fail-closed. Returns (data, error_message)."""
    if not os.path.exists(state_path):
        return {"version": 1, "open_gates": {}}, None

    try:
        with open(state_path, "r", encoding="utf-8") as f:
            raw = f.read()
        if not raw.strip():
            return None, f"Gate state file `{state_path}` is empty/corrupted."
        data = json.loads(raw)
    except Exception as exc:
        return None, f"Corrupted JSON in gate state `{state_path}`: {exc}"

    if not isinstance(data, dict):
        return None, f"Invalid gate state `{state_path}`: top-level JSON must be an object."

    open_gates = data.get("open_gates")
    if open_gates is None:
        data["open_gates"] = {}
    elif not isinstance(open_gates, dict):
        return None, f"Invalid gate state `{state_path}`: `open_gates` must be an object."

    if "version" not in data:
        data["version"] = 1
    return data, None


def save_gate_state(state_path: str, data: dict[str, Any]) -> None:
    """Atomically write gate_state.json."""
    parent = os.path.dirname(state_path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    tmp_path = f"{state_path}.tmp.{uuid.uuid4().hex[:8]}"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    os.replace(tmp_path, state_path)


def emit_json(payload: dict[str, Any]) -> int:
    sys.stdout.write(json.dumps(payload, indent=2) + "\n")
    sys.stdout.flush()
    status = str(payload.get("gate_status") or "").upper()
    return 0 if status in {"PASSED", "OPEN"} else 1


def _parse_ops_payload(raw_data: Any) -> tuple[list[tuple[str, str]] | None, str | None]:
    """Normalize rename operations JSON into [(src, dst), ...]."""
    items: list[Any]
    if isinstance(raw_data, dict):
        if "ops" in raw_data and isinstance(raw_data["ops"], list):
            items = raw_data["ops"]
        elif "operations" in raw_data and isinstance(raw_data["operations"], list):
            items = raw_data["operations"]
        else:
            ops_pairs: list[tuple[str, str]] = []
            for k, v in raw_data.items():
                if not isinstance(k, str) or not isinstance(v, str) or not k.strip() or not v.strip():
                    return None, "Mapping-style ops JSON must map non-empty string src -> dst."
                ops_pairs.append((k.strip(), v.strip()))
            return ops_pairs, None
    elif isinstance(raw_data, list):
        items = raw_data
    else:
        return None, "Operations JSON must be a list of {src, dst} objects or a mapping."

    pairs: list[tuple[str, str]] = []
    for idx, item in enumerate(items):
        if isinstance(item, (list, tuple)) and len(item) == 2:
            src, dst = item[0], item[1]
        elif isinstance(item, dict):
            src = (
                item.get("src")
                or item.get("from")
                or item.get("old")
                or item.get("source")
                or item.get("src_path")
            )
            dst = (
                item.get("dst")
                or item.get("to")
                or item.get("new")
                or item.get("destination")
                or item.get("dst_path")
            )
        else:
            return None, f"Operation #{idx} must be an object with src/dst fields."

        if not isinstance(src, str) or not isinstance(dst, str) or not src.strip() or not dst.strip():
            return None, f"Operation #{idx} has missing or non-string src/dst."
        pairs.append((src.strip(), dst.strip()))

    return pairs, None


def _resolve_under_root(path_str: str, root_dir: str) -> str:
    if os.path.isabs(path_str):
        return os.path.normpath(path_str)
    return os.path.normpath(os.path.join(root_dir, path_str))


def guardrail_rename(
    expected_count: int,
    ops_json: str | None = None,
    ops_file: str | None = None,
    root: str = ".",
    dry_run: bool = False,
) -> dict[str, Any]:
    """Execute transactional multi-file rename with expected-count check and rollback."""
    root_dir = os.path.realpath(root or ".")

    if expected_count <= 0:
        return {
            "gate_status": "BLOCKED",
            "operation": "rename",
            "expected_count": expected_count,
            "actual_count": 0,
            "moved_count": 0,
            "error": "--expected-count must be a positive integer.",
        }

    if bool(ops_json is not None) == bool(ops_file is not None):
        return {
            "gate_status": "BLOCKED",
            "operation": "rename",
            "expected_count": expected_count,
            "moved_count": 0,
            "error": "Provide exactly one of --ops-json or --ops-file.",
        }

    try:
        if ops_file is not None:
            with open(ops_file, "r", encoding="utf-8") as f:
                raw_data = json.load(f)
        else:
            raw_data = json.loads(ops_json or "")
    except Exception as exc:
        return {
            "gate_status": "BLOCKED",
            "operation": "rename",
            "expected_count": expected_count,
            "moved_count": 0,
            "error": f"Failed to parse operations JSON: {exc}",
        }

    pairs, parse_err = _parse_ops_payload(raw_data)
    if parse_err or pairs is None:
        return {
            "gate_status": "BLOCKED",
            "operation": "rename",
            "expected_count": expected_count,
            "moved_count": 0,
            "error": parse_err or "Invalid operations payload.",
        }

    if len(pairs) != expected_count:
        return {
            "gate_status": "BLOCKED",
            "operation": "rename",
            "expected_count": expected_count,
            "actual_count": len(pairs),
            "moved_count": 0,
            "error": f"Operation count mismatch: expected {expected_count}, got {len(pairs)}.",
        }

    resolved_ops: list[tuple[str, str]] = []
    seen_srcs: set[str] = set()
    seen_dsts: set[str] = set()
    for src_raw, dst_raw in pairs:
        src_abs = _resolve_under_root(src_raw, root_dir)
        dst_abs = _resolve_under_root(dst_raw, root_dir)
        if src_abs == dst_abs:
            return {
                "gate_status": "BLOCKED",
                "operation": "rename",
                "expected_count": expected_count,
                "actual_count": len(pairs),
                "moved_count": 0,
                "error": f"Source and destination are identical: `{src_raw}`.",
            }
        if src_abs in seen_srcs:
            return {
                "gate_status": "BLOCKED",
                "operation": "rename",
                "expected_count": expected_count,
                "actual_count": len(pairs),
                "moved_count": 0,
                "error": f"Duplicate source path: `{src_raw}`.",
            }
        if dst_abs in seen_dsts:
            return {
                "gate_status": "BLOCKED",
                "operation": "rename",
                "expected_count": expected_count,
                "actual_count": len(pairs),
                "moved_count": 0,
                "error": f"Duplicate destination path: `{dst_raw}`.",
            }
        seen_srcs.add(src_abs)
        seen_dsts.add(dst_abs)
        resolved_ops.append((src_abs, dst_abs))

    for src_abs, dst_abs in resolved_ops:
        if not os.path.exists(src_abs):
            return {
                "gate_status": "BLOCKED",
                "operation": "rename",
                "expected_count": expected_count,
                "actual_count": len(resolved_ops),
                "moved_count": 0,
                "error": f"Source path does not exist: `{src_abs}`.",
            }
        if os.path.exists(dst_abs) and dst_abs not in seen_srcs:
            return {
                "gate_status": "BLOCKED",
                "operation": "rename",
                "expected_count": expected_count,
                "actual_count": len(resolved_ops),
                "moved_count": 0,
                "error": f"Destination path already exists: `{dst_abs}`.",
            }

    if dry_run:
        return {
            "gate_status": "PASSED",
            "operation": "rename",
            "dry_run": True,
            "expected_count": expected_count,
            "moved_count": len(resolved_ops),
            "operations": [{"src": s, "dst": d} for s, d in resolved_ops],
        }

    batch_tag = uuid.uuid4().hex[:8]
    staged: list[tuple[str, str]] = []
    finalized: list[tuple[str, str]] = []
    created_dirs: list[str] = []

    def _rollback(error_msg: str) -> dict[str, Any]:
        rollback_errors: list[str] = []
        for tmp_path, dst_path in reversed(finalized):
            try:
                if os.path.exists(dst_path):
                    shutil.move(dst_path, tmp_path)
            except Exception as exc:
                rollback_errors.append(f"restore {dst_path}->{tmp_path}: {exc}")

        for src_path, tmp_path in reversed(staged):
            try:
                if os.path.exists(tmp_path):
                    shutil.move(tmp_path, src_path)
            except Exception as exc:
                rollback_errors.append(f"restore {tmp_path}->{src_path}: {exc}")

        for d in reversed(created_dirs):
            try:
                if os.path.isdir(d) and not os.listdir(d):
                    os.rmdir(d)
            except OSError:
                pass

        res: dict[str, Any] = {
            "gate_status": "ROLLED_BACK",
            "operation": "rename",
            "rolled_back": True,
            "expected_count": expected_count,
            "moved_count": 0,
            "error": error_msg,
        }
        if rollback_errors:
            res["rollback_errors"] = rollback_errors
        return res

    # Phase 1: stage all sources to unique temp paths (supports swaps & chains)
    for idx, (src_abs, _dst_abs) in enumerate(resolved_ops):
        parent = os.path.dirname(src_abs) or "."
        base = os.path.basename(src_abs)
        tmp_abs = os.path.join(parent, f".{base}.guardrail_tmp_{batch_tag}_{idx}")
        try:
            shutil.move(src_abs, tmp_abs)
            staged.append((src_abs, tmp_abs))
        except Exception as exc:
            return _rollback(f"Staging failed on `{src_abs}`: {exc}")

    # Phase 2: move staged paths to final destinations
    for (_src_abs, tmp_abs), (_orig_src, dst_abs) in zip(staged, resolved_ops):
        dst_parent = os.path.dirname(dst_abs)
        try:
            if dst_parent and not os.path.isdir(dst_parent):
                os.makedirs(dst_parent, exist_ok=True)
                created_dirs.append(dst_parent)
            shutil.move(tmp_abs, dst_abs)
            finalized.append((tmp_abs, dst_abs))
        except Exception as exc:
            return _rollback(f"Move failed to `{dst_abs}`: {exc}")

    if len(finalized) != expected_count or any(not os.path.exists(d) for _, d in finalized):
        return _rollback(
            f"Post-move verification failed: expected {expected_count}, verified {len(finalized)}."
        )

    return {
        "gate_status": "PASSED",
        "operation": "rename",
        "expected_count": expected_count,
        "moved_count": len(finalized),
        "operations": [{"src": s, "dst": d} for s, d in resolved_ops],
    }


def _match_glob(rel_posix: str, filename: str, pattern: str) -> bool:
    norm_pat = pattern.replace("\\", "/").lstrip("./")
    if "/" not in norm_pat and "**" not in norm_pat:
        return fnmatch.fnmatch(filename, norm_pat)
    if fnmatch.fnmatch(rel_posix, norm_pat):
        return True
    try:
        return Path(rel_posix).match(norm_pat)
    except Exception:
        return False


def _discover_files(
    root_dir: str,
    globs: list[str],
    regex_pattern: re.Pattern[str] | None,
) -> list[str]:
    """Walk root_dir and return sorted relative POSIX paths matching globs and regex."""
    matched: list[str] = []
    for dirpath, dirnames, filenames in os.walk(root_dir):
        dirnames[:] = sorted(
            d for d in dirnames
            if d not in IGNORED_WALK_DIRS and not d.startswith(".guardrail_tmp_")
        )
        for fname in sorted(filenames):
            full = os.path.join(dirpath, fname)
            rel_posix = os.path.relpath(full, root_dir).replace("\\", "/")
            if globs and not any(_match_glob(rel_posix, fname, g) for g in globs):
                continue
            if regex_pattern is not None:
                try:
                    with open(full, "r", encoding="utf-8", errors="replace") as f:
                        content = f.read()
                except OSError:
                    continue
                if not regex_pattern.search(content):
                    continue
            matched.append(rel_posix)
    return matched


def guardrail_open(
    mode: str,
    globs: list[str] | None = None,
    regex: str | None = None,
    root: str = ".",
    targets: list[str] | None = None,
    task_id: str | None = None,
    state_file: str | None = None,
) -> dict[str, Any]:
    """Open a tracked guardrail gate in `.agents/gate_state.json`."""
    if mode not in {"modify", "create", "delete"}:
        return {
            "gate_status": "BLOCKED",
            "operation": "open",
            "error": f"Unsupported mode `{mode}`. Expected modify|create|delete.",
        }

    root_dir = os.path.realpath(root or ".")
    if not os.path.isdir(root_dir):
        return {
            "gate_status": "BLOCKED",
            "operation": "open",
            "error": f"Root directory does not exist: `{root}`.",
        }

    workspace_root = find_workspace_root(root_dir)
    state_path = resolve_state_file(workspace_root, state_file)

    state_data, load_err = load_gate_state(state_path)
    if load_err or state_data is None:
        return {
            "gate_status": "BLOCKED",
            "operation": "open",
            "error": load_err or "Failed to load gate state.",
        }

    compiled_regex: re.Pattern[str] | None = None
    if regex is not None:
        try:
            compiled_regex = re.compile(regex)
        except re.error as exc:
            return {
                "gate_status": "BLOCKED",
                "operation": "open",
                "error": f"Invalid --regex pattern `{regex}`: {exc}",
            }

    globs_list = [g for g in (globs or []) if g and g.strip()]
    explicit_targets = [t.strip() for t in (targets or []) if t and t.strip()]

    if not globs_list and compiled_regex is None and not explicit_targets:
        return {
            "gate_status": "BLOCKED",
            "operation": "open",
            "error": "Must specify at least one of --glob, --regex, or --targets.",
        }

    resolved_targets: list[str] = []
    if explicit_targets:
        seen: set[str] = set()
        for raw_t in explicit_targets:
            cand = _resolve_under_root(raw_t, root_dir)
            if mode in {"modify", "delete"} and not os.path.isfile(cand):
                return {
                    "gate_status": "BLOCKED",
                    "operation": "open",
                    "mode": mode,
                    "error": f"Target file does not exist for mode `{mode}`: `{raw_t}`.",
                }
            if mode in {"modify", "delete"} and compiled_regex is not None:
                try:
                    with open(cand, "r", encoding="utf-8", errors="replace") as f:
                        if not compiled_regex.search(f.read()):
                            continue
                except OSError:
                    continue
            if mode in {"modify", "delete"} and globs_list:
                rel_p = os.path.relpath(cand, root_dir).replace("\\", "/")
                if not any(_match_glob(rel_p, os.path.basename(cand), g) for g in globs_list):
                    continue
            rel_store = (
                os.path.relpath(cand, root_dir).replace("\\", "/")
                if os.path.commonpath([root_dir, cand]) == root_dir
                else cand
            )
            if rel_store not in seen:
                seen.add(rel_store)
                resolved_targets.append(rel_store)
    else:
        if mode == "create":
            return {
                "gate_status": "BLOCKED",
                "operation": "open",
                "mode": mode,
                "error": "Mode `create` requires explicit --targets.",
            }
        resolved_targets = _discover_files(root_dir, globs_list, compiled_regex)

    if not resolved_targets:
        return {
            "gate_status": "BLOCKED",
            "operation": "open",
            "mode": mode,
            "root": root_dir,
            "target_count": 0,
            "targets": [],
            "error": "No target files matched the provided filters.",
        }

    tid = (task_id or "").strip() or f"task-{uuid.uuid4().hex[:8]}"
    open_gates = state_data["open_gates"]
    if tid in open_gates:
        return {
            "gate_status": "BLOCKED",
            "operation": "open",
            "task_id": tid,
            "error": f"Task `{tid}` is already open in `{state_path}`. Verify it before reopening.",
        }

    gate_entry: dict[str, Any] = {
        "task_id": tid,
        "mode": mode,
        "root": root_dir,
        "targets": resolved_targets,
        "expected_count": len(resolved_targets),
        "glob": globs_list,
        "regex": regex,
        "status": "OPEN",
        "opened_at": utc_now_iso(),
    }
    open_gates[tid] = gate_entry
    save_gate_state(state_path, state_data)

    return {
        "gate_status": "OPEN",
        "operation": "open",
        "task_id": tid,
        "mode": mode,
        "root": root_dir,
        "target_count": len(resolved_targets),
        "targets": resolved_targets,
        "state_file": state_path,
    }


def _tail_lines(text: str, max_lines: int = OUTPUT_TAIL_LINES) -> str:
    lines = text.splitlines()
    if len(lines) <= max_lines:
        return "\n".join(lines).strip()
    return "\n".join(lines[-max_lines:]).strip()


def guardrail_verify(
    task_id: str,
    cmds: list[str] | None = None,
    forbidden_regexes: list[str] | None = None,
    required_regexes: list[str] | None = None,
    root: str | None = None,
    state_file: str | None = None,
) -> dict[str, Any]:
    """Verify an open guardrail gate and close it ONLY if all checks pass."""
    tid = (task_id or "").strip()
    if not tid:
        return {
            "gate_status": "BLOCKED",
            "operation": "verify",
            "error": "--task-id is required.",
        }

    base_dir = os.path.realpath(root or os.getcwd())
    workspace_root = find_workspace_root(base_dir)
    state_path = resolve_state_file(workspace_root, state_file)

    state_data, load_err = load_gate_state(state_path)
    if load_err or state_data is None:
        return {
            "gate_status": "BLOCKED",
            "operation": "verify",
            "task_id": tid,
            "error": load_err or "Failed to load gate state.",
        }

    open_gates = state_data["open_gates"]
    if tid not in open_gates or not isinstance(open_gates[tid], dict):
        return {
            "gate_status": "BLOCKED",
            "operation": "verify",
            "task_id": tid,
            "error": f"No open gate found for task_id `{tid}` in `{state_path}`.",
        }

    gate = open_gates[tid]
    mode = str(gate.get("mode") or "modify")
    gate_root = os.path.realpath(root or gate.get("root") or workspace_root)
    targets = gate.get("targets")
    if not isinstance(targets, list) or not targets:
        return {
            "gate_status": "BLOCKED",
            "operation": "verify",
            "task_id": tid,
            "error": f"Gate `{tid}` has invalid or empty targets list.",
        }

    compiled_forbidden: list[tuple[str, re.Pattern[str]]] = []
    for raw_re in forbidden_regexes or []:
        try:
            compiled_forbidden.append((raw_re, re.compile(raw_re)))
        except re.error as exc:
            return {
                "gate_status": "BLOCKED",
                "operation": "verify",
                "task_id": tid,
                "error": f"Invalid --forbidden-regex `{raw_re}`: {exc}",
            }

    compiled_required: list[tuple[str, re.Pattern[str]]] = []
    for raw_re in required_regexes or []:
        try:
            compiled_required.append((raw_re, re.compile(raw_re)))
        except re.error as exc:
            return {
                "gate_status": "BLOCKED",
                "operation": "verify",
                "task_id": tid,
                "error": f"Invalid --required-regex `{raw_re}`: {exc}",
            }

    failures: list[str] = []

    for rel_target in targets:
        full_target = _resolve_under_root(str(rel_target), gate_root)
        if mode == "delete":
            if os.path.exists(full_target):
                failures.append(f"Target still exists for delete mode: `{rel_target}`.")
            continue

        if not os.path.isfile(full_target):
            failures.append(f"Target file missing for {mode} mode: `{rel_target}`.")
            continue

        if compiled_forbidden or compiled_required:
            try:
                with open(full_target, "r", encoding="utf-8", errors="replace") as f:
                    content = f.read()
            except OSError as exc:
                failures.append(f"Could not read target `{rel_target}`: {exc}")
                continue

            for raw_re, pat in compiled_forbidden:
                if pat.search(content):
                    failures.append(
                        f"Forbidden regex `{raw_re}` matched in `{rel_target}`."
                    )

            for raw_re, pat in compiled_required:
                if not pat.search(content):
                    failures.append(
                        f"Required regex `{raw_re}` missing in `{rel_target}`."
                    )

    cmd_results: list[dict[str, Any]] = []
    for cmd in cmds or []:
        cmd_str = cmd.strip()
        if not cmd_str:
            continue
        try:
            proc = subprocess.run(
                cmd_str,
                cwd=gate_root,
                shell=True,
                capture_output=True,
                text=True,
                timeout=CMD_TIMEOUT_SECONDS,
            )
            cmd_results.append(
                {
                    "cmd": cmd_str,
                    "returncode": proc.returncode,
                    "stdout_tail": _tail_lines(proc.stdout or ""),
                    "stderr_tail": _tail_lines(proc.stderr or ""),
                }
            )
            if proc.returncode != 0:
                combined = "\n".join(
                    p for p in ((proc.stdout or "").strip(), (proc.stderr or "").strip()) if p
                )
                msg = f"Verification command failed (exit {proc.returncode}): `{cmd_str}`"
                tail = _tail_lines(combined)
                if tail:
                    msg += f" | {tail}"
                failures.append(msg)
        except subprocess.TimeoutExpired:
            failures.append(f"Verification command timed out after {CMD_TIMEOUT_SECONDS}s: `{cmd_str}`")
        except Exception as exc:
            failures.append(f"Verification command error `{cmd_str}`: {exc}")

    if failures:
        res: dict[str, Any] = {
            "gate_status": "BLOCKED",
            "operation": "verify",
            "task_id": tid,
            "mode": mode,
            "targets": targets,
            "failures": failures,
            "error": failures[0],
        }
        if cmd_results:
            res["command_results"] = cmd_results
        return res

    del open_gates[tid]
    save_gate_state(state_path, state_data)

    res = {
        "gate_status": "PASSED",
        "operation": "verify",
        "task_id": tid,
        "mode": mode,
        "verified_targets": targets,
        "remaining_open_gates": len(open_gates),
        "state_file": state_path,
    }
    if cmd_results:
        res["command_results"] = cmd_results
    return res


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="guardrail_mcp.py",
        description="Zero-UI-setup deterministic guardrail CLI for multi-file operations.",
    )
    sub = parser.add_subparsers(dest="subcommand", required=True)

    p_rename = sub.add_parser(
        "rename",
        help="Transactionally rename N files with expected-count validation and rollback.",
    )
    p_rename.add_argument("--expected-count", type=int, required=True, help="Expected number of rename ops.")
    p_rename.add_argument("--ops-json", default=None, help="JSON string describing rename ops.")
    p_rename.add_argument("--ops-file", default=None, help="Path to JSON file describing rename ops.")
    p_rename.add_argument("--root", default=".", help="Base directory for relative paths (default: .).")
    p_rename.add_argument("--dry-run", action="store_true", help="Validate only without modifying files.")

    p_open = sub.add_parser(
        "open",
        help="Discover/register targets and open a tracked guardrail gate in .agents/gate_state.json.",
    )
    p_open.add_argument("--mode", choices=["modify", "create", "delete"], required=True)
    p_open.add_argument("--glob", action="append", default=[], help="Glob pattern(s) to match files.")
    p_open.add_argument("--regex", default=None, help="Regex pattern to match file contents.")
    p_open.add_argument("--root", default=".", help="Root directory for target discovery (default: .).")
    p_open.add_argument("--targets", nargs="*", default=[], help="Explicit target file paths.")
    p_open.add_argument("--task-id", default=None, help="Optional explicit task ID.")
    p_open.add_argument("--state-file", default=None, help="Optional gate_state.json override.")

    p_verify = sub.add_parser(
        "verify",
        help="Verify target invariants and commands for an open gate, closing it only on pass.",
    )
    p_verify.add_argument("--task-id", required=True, help="Task ID returned by `open`.")
    p_verify.add_argument("--cmd", action="append", default=[], help="Verification shell command(s).")
    p_verify.add_argument(
        "--forbidden-regex",
        action="append",
        default=[],
        help="Regex that must NOT appear in any target file.",
    )
    p_verify.add_argument(
        "--required-regex",
        action="append",
        default=[],
        help="Regex that MUST appear in every target file.",
    )
    p_verify.add_argument("--root", default=None, help="Optional root directory override.")
    p_verify.add_argument("--state-file", default=None, help="Optional gate_state.json override.")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.subcommand == "rename":
        result = guardrail_rename(
            expected_count=args.expected_count,
            ops_json=args.ops_json,
            ops_file=args.ops_file,
            root=args.root,
            dry_run=args.dry_run,
        )
        return emit_json(result)

    if args.subcommand == "open":
        result = guardrail_open(
            mode=args.mode,
            globs=args.glob,
            regex=args.regex,
            root=args.root,
            targets=args.targets,
            task_id=args.task_id,
            state_file=args.state_file,
        )
        return emit_json(result)

    if args.subcommand == "verify":
        result = guardrail_verify(
            task_id=args.task_id,
            cmds=args.cmd,
            forbidden_regexes=args.forbidden_regex,
            required_regexes=args.required_regex,
            root=args.root,
            state_file=args.state_file,
        )
        return emit_json(result)

    return emit_json({"gate_status": "BLOCKED", "error": f"Unknown subcommand: {args.subcommand}"})


if __name__ == "__main__":
    sys.exit(main())
