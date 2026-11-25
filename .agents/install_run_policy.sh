#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GLOBAL_CFG="${ANTIGRAVITY_CONFIG_DIR:-$HOME/.gemini/config}"

mkdir -p "$GLOBAL_CFG/rules" "$GLOBAL_CFG/hooks"

chmod +x "$SCRIPT_DIR/hooks/run_policy_gate.py" "$SCRIPT_DIR/hooks/protect_policy.py" "$SCRIPT_DIR/install_run_policy.sh"
if [ -f "$WORKSPACE_ROOT/tools/guardrail_mcp.py" ]; then
  chmod +x "$WORKSPACE_ROOT/tools/guardrail_mcp.py"
fi

# Mirror rules and hooks into global Antigravity config directory
cp -f "$SCRIPT_DIR/rules/run-policy.md" "$GLOBAL_CFG/rules/run-policy.md"
cp -f "$SCRIPT_DIR/hooks/run_policy_gate.py" "$GLOBAL_CFG/hooks/run_policy_gate.py"
cp -f "$SCRIPT_DIR/hooks/protect_policy.py" "$GLOBAL_CFG/hooks/protect_policy.py"
chmod +x "$GLOBAL_CFG/hooks/run_policy_gate.py" "$GLOBAL_CFG/hooks/protect_policy.py"

# Wire hooks.json in both global config (safe merge) and local .agents/hooks.json
python3 - "$GLOBAL_CFG/hooks.json" "$GLOBAL_CFG/hooks" "$SCRIPT_DIR/hooks.json" "$SCRIPT_DIR/hooks" <<'PY'
import json
import os
import sys

global_hooks_json, global_hooks_dir, local_hooks_json, local_hooks_dir = sys.argv[1:5]

PRE_TOOL_MATCHER = "write_to_file|replace_file_content|multi_replace_file_content|run_command"


def make_entries(hooks_dir: str):
    protect_cmd = f"python3 {os.path.join(hooks_dir, 'protect_policy.py')}"
    gate_cmd = f"python3 {os.path.join(hooks_dir, 'run_policy_gate.py')}"
    pre_entry = {
        "matcher": PRE_TOOL_MATCHER,
        "hooks": [
            {
                "type": "command",
                "command": protect_cmd,
                "timeout": 15,
            }
        ],
    }
    stop_entry = {
        "hooks": [
            {
                "type": "command",
                "command": gate_cmd,
                "timeout": 120,
            }
        ],
    }
    return pre_entry, stop_entry


def is_our_entry(entry: dict, script_name: str) -> bool:
    if not isinstance(entry, dict):
        return False
    for h in entry.get("hooks", []):
        if isinstance(h, dict) and script_name in str(h.get("command", "")):
            return True
    return False


def merge_hooks_file(target_path: str, hooks_dir: str) -> None:
    data = {}
    if os.path.isfile(target_path):
        try:
            with open(target_path, "r", encoding="utf-8") as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                data = loaded
        except Exception:
            data = {}

    hooks = data.get("hooks")
    if not isinstance(hooks, dict):
        hooks = {}

    pre_entry, stop_entry = make_entries(hooks_dir)

    pre_list = hooks.get("PreToolUse")
    if not isinstance(pre_list, list):
        pre_list = []
    pre_list = [e for e in pre_list if not is_our_entry(e, "protect_policy.py")]
    pre_list.append(pre_entry)
    hooks["PreToolUse"] = pre_list

    stop_list = hooks.get("Stop")
    if not isinstance(stop_list, list):
        stop_list = []
    stop_list = [e for e in stop_list if not is_our_entry(e, "run_policy_gate.py")]
    stop_list.append(stop_entry)
    hooks["Stop"] = stop_list

    data["hooks"] = hooks
    with open(target_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


merge_hooks_file(global_hooks_json, os.path.realpath(global_hooks_dir))
merge_hooks_file(local_hooks_json, os.path.realpath(local_hooks_dir))
PY

# Configure git exclude and pre-push hook when inside a git repository
if git -C "$WORKSPACE_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  GIT_DIR="$(git -C "$WORKSPACE_ROOT" rev-parse --git-dir)"
  case "$GIT_DIR" in
    /*) ;;
    *) GIT_DIR="$WORKSPACE_ROOT/$GIT_DIR" ;;
  esac

  mkdir -p "$GIT_DIR/info" "$GIT_DIR/hooks"
  EXCLUDE_FILE="$GIT_DIR/info/exclude"
  touch "$EXCLUDE_FILE"
  for pattern in ".agents/run_contract.json" ".agents/gate_state.json"; do
    if ! grep -qxF "$pattern" "$EXCLUDE_FILE"; then
      printf "\n%s\n" "$pattern" >> "$EXCLUDE_FILE"
    fi
  done

  PRE_PUSH_HOOK="$GIT_DIR/hooks/pre-push"
  cat > "$PRE_PUSH_HOOK" <<'HOOK'
#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(git rev-parse --show-toplevel)"
if [ -f "$REPO_ROOT/.agents/hooks/run_policy_gate.py" ]; then
  python3 "$REPO_ROOT/.agents/hooks/run_policy_gate.py" --cli
fi
HOOK
  chmod +x "$PRE_PUSH_HOOK"
fi

echo "Universal Run Policy installed:"
echo "  Workspace kit: $SCRIPT_DIR"
echo "  Global config: $GLOBAL_CFG"
echo "  Global hooks:  $GLOBAL_CFG/hooks.json"
echo "  Local hooks:   $SCRIPT_DIR/hooks.json"
