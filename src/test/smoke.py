"""Run against a disposable IDE sandbox, never the working IDE.

python3 src/test/smoke.py --url http://127.0.0.1:16432/stream \
    --host-project /absolute/open/project --project /absolute/disposable/project

The test opens and closes --project and merges ALL sandbox project windows.
Trust prompts remain interactive. The host project and other projects stay open.
Select an existing sandbox project once before running, so the default-target
scenario has prior IDE activity even when another application is foreground.
"""

import argparse
import itertools
import json
import tempfile
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--host-project", required=True, type=Path)
    parser.add_argument("--project", required=True, type=Path)
    args = parser.parse_args()
    host, project = args.host_project.resolve(strict=True), args.project.resolve(strict=True)
    assert host != project and host.is_dir() and project.is_dir()
    sequence = itertools.count(1)
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}

    def rpc(method, params):
        request = urllib.request.Request(
            args.url,
            json.dumps({"jsonrpc": "2.0", "id": next(sequence), "method": method, "params": params}).encode(),
            headers,
        )
        with urllib.request.urlopen(request, timeout=45) as response:
            if method == "initialize":
                headers["Mcp-Session-Id"] = response.headers["Mcp-Session-Id"]
            reply = json.load(response)
        assert "error" not in reply, reply
        return reply["result"]

    def tool(name, **arguments):
        response = rpc("tools/call", {"name": name, "arguments": {"projectPath": str(host), **arguments}})
        assert not response.get("isError"), response
        return json.loads(response["content"][0]["text"])

    def inventory():
        return {p["directory"]: p for p in tool("worktree_status")["projects"]}

    rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                       "clientInfo": {"name": "worktree-sandbox-regression", "version": "1"}})
    # The host expires HTTP sessions without a persistent GET event stream.
    stream_request = urllib.request.Request(args.url, headers={
        "Accept": "text/event-stream", "Mcp-Session-Id": headers["Mcp-Session-Id"]})
    with urllib.request.urlopen(stream_request, timeout=45), tempfile.TemporaryDirectory() as temporary:
        initial = inventory()
        assert str(host) in initial and str(project) not in initial, "Use an unopened disposable project"
        assert tool("worktree_status").get("lastActiveDirectory") in initial, "Select an existing project once before running"
        expected_tools = {"worktree_open", "worktree_close", "worktree_merge", "worktree_action", "worktree_status"}
        assert expected_tools <= {t["name"] for t in rpc("tools/list", {})["tools"]}
        alias = Path(temporary) / "alias"
        alias.symlink_to(project, target_is_directory=True)
        with ThreadPoolExecutor(max_workers=2) as pool:
            calls = [pool.submit(tool, "worktree_open", directory=str(path)) for path in (project, alias)]
            outcomes = [call.result() for call in calls]
        assert sorted(r["status"] for r in outcomes) == ["opened", "reused"], outcomes
        assert all(r["directory"] == str(project) and "merge" not in r for r in outcomes), outcomes
        opened = inventory()
        assert set(opened) == set(initial) | {str(project)}, opened
        assert all(opened[p]["frame"] == initial[p]["frame"] for p in initial)
        assert tool("worktree_open", directory=str(Path(temporary) / "missing"))["status"] == "invalid_directory"
        assert tool("worktree_action", actionId="Worktree.Nonexistent", directory=str(project))["status"] == "unknown_action"
        assert tool("worktree_action", actionId="EditorCopy", directory=str(project))["status"] == "unavailable"
        assert tool("worktree_merge", targetDirectory=str(host))["status"] == "merged"
        assert tool("worktree_merge", targetDirectory=str(host))["status"] == "not_needed"
        merged = inventory()[str(host)]
        assert all(merged[k] == initial[str(host)][k] for k in ("frame", "x", "y", "width", "height")), merged
        assert tool("worktree_status")["lastActiveDirectory"] == str(host)
        assert tool("worktree_close", directory=str(alias))["status"] == "closed"
        assert set(inventory()) == set(initial)
        assert tool("worktree_close", directory=str(project))["status"] == "already_closed"
        combined = tool("worktree_open", directory=str(project), merge=True)
        assert combined["status"] == "opened" and combined["merge"]["status"] == "merged", combined
        assert combined["merge"]["directory"] == str(host), combined
        assert tool("worktree_close", directory=str(project))["status"] == "closed"
        assert set(inventory()) == set(initial)
        print("PASS: canonical concurrent reuse, invalid path, explicit-context rejection, merge/no-op, geometry, exact close, open+merge")


if __name__ == "__main__":
    main()
