#!/usr/bin/env python3
"""End-to-end tests for zero-UI-setup guardrail CLI and Run Policy gate hooks."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parent.parent
GUARDRAIL_CLI = str(REPO_ROOT / "tools" / "guardrail_mcp.py")
GATE_HOOK = str(REPO_ROOT / ".agents" / "hooks" / "run_policy_gate.py")
PROTECT_HOOK = str(REPO_ROOT / ".agents" / "hooks" / "protect_policy.py")

sys.path.insert(0, str(REPO_ROOT / "tools"))
import guardrail_mcp  # noqa: E402


def run_cli(
    script: str,
    args: list[str],
    cwd: str,
    stdin_text: str | None = None,
    env_overrides: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env.pop("DISABLE_RUN_POLICY", None)
    env.pop("GUARDRAIL_GATE_STATE_FILE", None)
    if env_overrides:
        env.update(env_overrides)
    return subprocess.run(
        [sys.executable, script, *args],
        cwd=cwd,
        input=stdin_text,
        capture_output=True,
        text=True,
        env=env,
        timeout=30,
    )


def init_temp_git_repo(tmp_dir: str) -> None:
    subprocess.run(["git", "init", "-q"], cwd=tmp_dir, check=True)
    subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=tmp_dir, check=True)
    subprocess.run(["git", "config", "user.name", "Test User"], cwd=tmp_dir, check=True)
    (Path(tmp_dir) / ".agents").mkdir(parents=True, exist_ok=True)
    (Path(tmp_dir) / ".gitignore").write_text(
        ".agents/gate_state.json\n.agents/run_contract.json\n__pycache__/\n*.pyc\n",
        encoding="utf-8",
    )
    subprocess.run(["git", "add", ".gitignore"], cwd=tmp_dir, check=True)
    subprocess.run(["git", "commit", "-q", "-m", "init"], cwd=tmp_dir, check=True)


class GuardrailCliEndToEndTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.ws = os.path.join(self.tmp.name, "repo")
        os.makedirs(self.ws, exist_ok=True)
        init_temp_git_repo(self.ws)
        self.cb_state = os.path.join(self.tmp.name, "cb_state.json")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_rename_success_ops_json_and_ops_file(self) -> None:
        (Path(self.ws) / "a.txt").write_text("alpha", encoding="utf-8")
        (Path(self.ws) / "b.txt").write_text("beta", encoding="utf-8")

        ops = [
            {"src": "a.txt", "dst": "sub/a_renamed.txt"},
            {"src": "b.txt", "dst": "sub/b_renamed.txt"},
        ]
        res = run_cli(
            GUARDRAIL_CLI,
            ["rename", "--expected-count", "2", "--ops-json", json.dumps(ops), "--root", self.ws],
            cwd=self.ws,
        )
        self.assertEqual(res.returncode, 0, msg=res.stdout + res.stderr)
        payload = json.loads(res.stdout)
        self.assertEqual(payload["gate_status"], "PASSED")
        self.assertEqual(payload["moved_count"], 2)
        self.assertFalse((Path(self.ws) / "a.txt").exists())
        self.assertEqual((Path(self.ws) / "sub" / "a_renamed.txt").read_text(encoding="utf-8"), "alpha")
        self.assertEqual((Path(self.ws) / "sub" / "b_renamed.txt").read_text(encoding="utf-8"), "beta")

        # Swap back using --ops-file
        ops_back = [
            {"src": "sub/a_renamed.txt", "dst": "a.txt"},
            {"src": "sub/b_renamed.txt", "dst": "b.txt"},
        ]
        ops_file = Path(self.ws) / "ops.json"
        ops_file.write_text(json.dumps(ops_back), encoding="utf-8")
        res2 = run_cli(
            GUARDRAIL_CLI,
            ["rename", "--expected-count", "2", "--ops-file", str(ops_file), "--root", self.ws],
            cwd=self.ws,
        )
        self.assertEqual(res2.returncode, 0, msg=res2.stdout + res2.stderr)
        self.assertEqual(json.loads(res2.stdout)["gate_status"], "PASSED")
        self.assertEqual((Path(self.ws) / "a.txt").read_text(encoding="utf-8"), "alpha")

    def test_rename_swap_cycle_supported(self) -> None:
        (Path(self.ws) / "one.txt").write_text("1", encoding="utf-8")
        (Path(self.ws) / "two.txt").write_text("2", encoding="utf-8")

        ops = [{"src": "one.txt", "dst": "two.txt"}, {"src": "two.txt", "dst": "one.txt"}]
        res = run_cli(
            GUARDRAIL_CLI,
            ["rename", "--expected-count", "2", "--ops-json", json.dumps(ops), "--root", self.ws],
            cwd=self.ws,
        )
        self.assertEqual(res.returncode, 0, msg=res.stdout + res.stderr)
        self.assertEqual((Path(self.ws) / "one.txt").read_text(encoding="utf-8"), "2")
        self.assertEqual((Path(self.ws) / "two.txt").read_text(encoding="utf-8"), "1")

    def test_rename_blocks_on_expected_count_mismatch(self) -> None:
        (Path(self.ws) / "a.txt").write_text("alpha", encoding="utf-8")
        (Path(self.ws) / "b.txt").write_text("beta", encoding="utf-8")

        ops = [{"src": "a.txt", "dst": "a2.txt"}]
        res = run_cli(
            GUARDRAIL_CLI,
            ["rename", "--expected-count", "2", "--ops-json", json.dumps(ops), "--root", self.ws],
            cwd=self.ws,
        )
        self.assertNotEqual(res.returncode, 0)
        payload = json.loads(res.stdout)
        self.assertEqual(payload["gate_status"], "BLOCKED")
        self.assertTrue((Path(self.ws) / "a.txt").exists())
        self.assertFalse((Path(self.ws) / "a2.txt").exists())

    def test_rename_midflight_failure_rolls_back_all_files(self) -> None:
        f1 = Path(self.ws) / "f1.txt"
        f2 = Path(self.ws) / "f2.txt"
        f1.write_text("content1", encoding="utf-8")
        f2.write_text("content2", encoding="utf-8")

        real_move = shutil.move
        call_count = 0

        def flaky_move(src: str, dst: str) -> str:
            nonlocal call_count
            call_count += 1
            # Fail on Phase 2 second move (call #4: 2 staging moves + 1st final + 2nd final)
            if call_count == 4:
                raise OSError("Simulated disk failure during second move")
            return real_move(src, dst)

        ops = json.dumps([{"src": "f1.txt", "dst": "g1.txt"}, {"src": "f2.txt", "dst": "g2.txt"}])
        with mock.patch("guardrail_mcp.shutil.move", side_effect=flaky_move):
            result = guardrail_mcp.guardrail_rename(expected_count=2, ops_json=ops, root=self.ws)

        self.assertEqual(result["gate_status"], "ROLLED_BACK")
        self.assertTrue(result["rolled_back"])
        self.assertTrue(f1.exists())
        self.assertTrue(f2.exists())
        self.assertEqual(f1.read_text(encoding="utf-8"), "content1")
        self.assertEqual(f2.read_text(encoding="utf-8"), "content2")
        self.assertFalse((Path(self.ws) / "g1.txt").exists())
        self.assertFalse((Path(self.ws) / "g2.txt").exists())

    def test_open_blocks_when_zero_targets_match(self) -> None:
        res = run_cli(
            GUARDRAIL_CLI,
            ["open", "--mode", "modify", "--glob", "*.nonexistent", "--root", self.ws],
            cwd=self.ws,
        )
        self.assertNotEqual(res.returncode, 0)
        payload = json.loads(res.stdout)
        self.assertEqual(payload["gate_status"], "BLOCKED")
        self.assertFalse((Path(self.ws) / ".agents" / "gate_state.json").exists())

    def test_open_blocks_gate_until_verified_end_to_end(self) -> None:
        src_dir = Path(self.ws) / "src"
        src_dir.mkdir()
        f1 = src_dir / "mod1.py"
        f2 = src_dir / "mod2.py"
        f3 = src_dir / "untouched.py"
        f1.write_text("value = old_api()\n", encoding="utf-8")
        f2.write_text("value = old_api()\n", encoding="utf-8")
        f3.write_text("value = stable_api()\n", encoding="utf-8")
        subprocess.run(["git", "add", "src"], cwd=self.ws, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "add src"], cwd=self.ws, check=True)

        # 1. Open gate via glob + regex
        open_res = run_cli(
            GUARDRAIL_CLI,
            [
                "open",
                "--mode",
                "modify",
                "--glob",
                "*.py",
                "--regex",
                "old_api",
                "--root",
                self.ws,
                "--task-id",
                "migrate-api",
            ],
            cwd=self.ws,
        )
        self.assertEqual(open_res.returncode, 0, msg=open_res.stdout + open_res.stderr)
        open_payload = json.loads(open_res.stdout)
        self.assertEqual(open_payload["gate_status"], "OPEN")
        self.assertEqual(open_payload["task_id"], "migrate-api")
        self.assertEqual(sorted(open_payload["targets"]), ["src/mod1.py", "src/mod2.py"])

        # 2. run_policy_gate --cli MUST BLOCK and print task_id + targets even when git status is clean
        gate_cli_blocked = run_cli(
            GATE_HOOK,
            ["--cli", "--workspace", self.ws],
            cwd=self.ws,
            env_overrides={"RUN_POLICY_STATE_FILE": self.cb_state},
        )
        self.assertEqual(gate_cli_blocked.returncode, 1)
        self.assertIn("migrate-api", gate_cli_blocked.stderr)
        self.assertIn("src/mod1.py", gate_cli_blocked.stderr)
        self.assertIn("src/mod2.py", gate_cli_blocked.stderr)

        # 3. run_policy_gate hook mode MUST return decision=continue with task_id + targets
        gate_hook_blocked = run_cli(
            GATE_HOOK,
            ["--workspace", self.ws],
            cwd=self.ws,
            stdin_text=json.dumps({"fullyIdle": True}),
            env_overrides={"RUN_POLICY_STATE_FILE": self.cb_state},
        )
        self.assertEqual(gate_hook_blocked.returncode, 0)
        hook_json = json.loads(gate_hook_blocked.stdout)
        self.assertEqual(hook_json["decision"], "continue")
        self.assertIn("migrate-api", hook_json["reason"])
        self.assertIn("src/mod1.py", hook_json["reason"])

        # 4. Partial edit: mod1 updated, mod2 still has old_api -> verify MUST BLOCK
        f1.write_text("value = new_api()\n", encoding="utf-8")
        verify_fail = run_cli(
            GUARDRAIL_CLI,
            [
                "verify",
                "--task-id",
                "migrate-api",
                "--forbidden-regex",
                "old_api",
                "--required-regex",
                "new_api",
                "--cmd",
                "python3 -m py_compile src/mod1.py src/mod2.py",
                "--root",
                self.ws,
            ],
            cwd=self.ws,
        )
        self.assertNotEqual(verify_fail.returncode, 0)
        verify_fail_json = json.loads(verify_fail.stdout)
        self.assertEqual(verify_fail_json["gate_status"], "BLOCKED")
        self.assertIn("src/mod2.py", verify_fail_json["failures"][0])

        # Gate must STILL be open and block run_policy_gate.py
        gate_still_blocked = run_cli(
            GATE_HOOK,
            ["--cli", "--workspace", self.ws],
            cwd=self.ws,
            env_overrides={"RUN_POLICY_STATE_FILE": self.cb_state},
        )
        self.assertEqual(gate_still_blocked.returncode, 1)

        # 5. Complete edit on mod2, verify MUST PASS and close gate
        f2.write_text("value = new_api()\n", encoding="utf-8")
        verify_ok = run_cli(
            GUARDRAIL_CLI,
            [
                "verify",
                "--task-id",
                "migrate-api",
                "--forbidden-regex",
                "old_api",
                "--required-regex",
                "new_api",
                "--cmd",
                "python3 -m py_compile src/mod1.py src/mod2.py",
                "--root",
                self.ws,
            ],
            cwd=self.ws,
        )
        self.assertEqual(verify_ok.returncode, 0, msg=verify_ok.stdout + verify_ok.stderr)
        verify_ok_json = json.loads(verify_ok.stdout)
        self.assertEqual(verify_ok_json["gate_status"], "PASSED")
        self.assertEqual(verify_ok_json["remaining_open_gates"], 0)

        # Commit src changes so contract isn't required, then both --cli and hook mode MUST PASS
        subprocess.run(["git", "add", "src"], cwd=self.ws, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "migrated"], cwd=self.ws, check=True)

        gate_cli_ok = run_cli(
            GATE_HOOK,
            ["--cli", "--workspace", self.ws],
            cwd=self.ws,
            env_overrides={"RUN_POLICY_STATE_FILE": self.cb_state},
        )
        self.assertEqual(gate_cli_ok.returncode, 0, msg=gate_cli_ok.stderr)

        gate_hook_ok = run_cli(
            GATE_HOOK,
            ["--workspace", self.ws],
            cwd=self.ws,
            stdin_text=json.dumps({"fullyIdle": True}),
            env_overrides={"RUN_POLICY_STATE_FILE": self.cb_state},
        )
        self.assertEqual(gate_hook_ok.returncode, 0)
        self.assertEqual(json.loads(gate_hook_ok.stdout)["decision"], "stop")

    def test_corrupted_gate_state_fails_closed(self) -> None:
        gate_state = Path(self.ws) / ".agents" / "gate_state.json"
        gate_state.write_text("{corrupted json", encoding="utf-8")

        cli_res = run_cli(
            GATE_HOOK,
            ["--cli", "--workspace", self.ws],
            cwd=self.ws,
            env_overrides={"RUN_POLICY_STATE_FILE": self.cb_state},
        )
        self.assertEqual(cli_res.returncode, 1)
        self.assertIn("corrupted", cli_res.stderr.lower())

        hook_res = run_cli(
            GATE_HOOK,
            ["--workspace", self.ws],
            cwd=self.ws,
            stdin_text=json.dumps({"fullyIdle": True}),
            env_overrides={"RUN_POLICY_STATE_FILE": self.cb_state},
        )
        self.assertEqual(hook_res.returncode, 0)
        hook_json = json.loads(hook_res.stdout)
        self.assertEqual(hook_json["decision"], "continue")
        self.assertIn("corrupted", hook_json["reason"].lower())

        # guardrail_mcp open/verify must also fail-closed on corrupted gate_state.json
        open_res = run_cli(
            GUARDRAIL_CLI,
            ["open", "--mode", "create", "--targets", "new1.txt", "new2.txt", "--root", self.ws],
            cwd=self.ws,
        )
        self.assertNotEqual(open_res.returncode, 0)
        self.assertEqual(json.loads(open_res.stdout)["gate_status"], "BLOCKED")

    def test_create_and_delete_modes(self) -> None:
        # create mode
        open_create = run_cli(
            GUARDRAIL_CLI,
            [
                "open",
                "--mode",
                "create",
                "--targets",
                "created_a.txt",
                "created_b.txt",
                "--root",
                self.ws,
                "--task-id",
                "create-task",
            ],
            cwd=self.ws,
        )
        self.assertEqual(open_create.returncode, 0)

        # verify fails before files exist
        v_fail = run_cli(
            GUARDRAIL_CLI,
            ["verify", "--task-id", "create-task", "--root", self.ws],
            cwd=self.ws,
        )
        self.assertNotEqual(v_fail.returncode, 0)

        (Path(self.ws) / "created_a.txt").write_text("ok\n", encoding="utf-8")
        (Path(self.ws) / "created_b.txt").write_text("ok\n", encoding="utf-8")
        v_ok = run_cli(
            GUARDRAIL_CLI,
            ["verify", "--task-id", "create-task", "--required-regex", "^ok$", "--root", self.ws],
            cwd=self.ws,
        )
        self.assertEqual(v_ok.returncode, 0)
        self.assertEqual(json.loads(v_ok.stdout)["gate_status"], "PASSED")

        # delete mode
        open_del = run_cli(
            GUARDRAIL_CLI,
            [
                "open",
                "--mode",
                "delete",
                "--targets",
                "created_a.txt",
                "created_b.txt",
                "--root",
                self.ws,
                "--task-id",
                "delete-task",
            ],
            cwd=self.ws,
        )
        self.assertEqual(open_del.returncode, 0)
        v_del_fail = run_cli(
            GUARDRAIL_CLI,
            ["verify", "--task-id", "delete-task", "--root", self.ws],
            cwd=self.ws,
        )
        self.assertNotEqual(v_del_fail.returncode, 0)

        (Path(self.ws) / "created_a.txt").unlink()
        (Path(self.ws) / "created_b.txt").unlink()
        v_del_ok = run_cli(
            GUARDRAIL_CLI,
            ["verify", "--task-id", "delete-task", "--root", self.ws],
            cwd=self.ws,
        )
        self.assertEqual(v_del_ok.returncode, 0)
        self.assertEqual(json.loads(v_del_ok.stdout)["gate_status"], "PASSED")

    def test_protect_policy_guard(self) -> None:
        # Writing gate_state.json directly must be denied
        deny_write = run_cli(
            PROTECT_HOOK,
            [],
            cwd=self.ws,
            stdin_text=json.dumps(
                {"tool_name": "write_to_file", "tool_input": {"TargetFile": ".agents/gate_state.json"}}
            ),
        )
        self.assertEqual(json.loads(deny_write.stdout)["decision"], "deny")

        # Deleting gate_state.json via shell must be denied
        deny_rm = run_cli(
            PROTECT_HOOK,
            [],
            cwd=self.ws,
            stdin_text=json.dumps(
                {"tool_name": "run_command", "tool_input": {"CommandLine": "rm -f .agents/gate_state.json"}}
            ),
        )
        self.assertEqual(json.loads(deny_rm.stdout)["decision"], "deny")

        # Chained shell injection on guardrail_mcp.py must be denied
        deny_chain = run_cli(
            PROTECT_HOOK,
            [],
            cwd=self.ws,
            stdin_text=json.dumps(
                {
                    "tool_name": "run_command",
                    "tool_input": {
                        "CommandLine": "python3 tools/guardrail_mcp.py open --mode modify --targets a.py; rm .agents/gate_state.json"
                    },
                }
            ),
        )
        self.assertEqual(json.loads(deny_chain.stdout)["decision"], "deny")

        # Clean guardrail CLI invocation (including quoted && or | in --cmd/--forbidden-regex) must be allowed
        allow_guardrail = run_cli(
            PROTECT_HOOK,
            [],
            cwd=self.ws,
            stdin_text=json.dumps(
                {
                    "tool_name": "run_command",
                    "tool_input": {
                        "CommandLine": 'python3 tools/guardrail_mcp.py verify --task-id t1 --forbidden-regex "a|b" --cmd "echo ok && true"'
                    },
                }
            ),
        )
        self.assertEqual(json.loads(allow_guardrail.stdout)["decision"], "allow")


if __name__ == "__main__":
    unittest.main()
