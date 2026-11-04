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
import time
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

    def call_tool(name, **arguments):
        response = rpc("tools/call", {"name": name, "arguments": {"projectPath": str(host), **arguments}})
        assert not response.get("isError"), response
        return response

    def tool(name, **arguments):
        response = call_tool(name, **arguments)
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
        expected_tools = {"worktree_open", "worktree_close", "worktree_merge", "worktree_action",
                          "worktree_status", "worktree_search_actions"}
        assert expected_tools <= {t["name"] for t in rpc("tools/list", {})["tools"]}
        def search(query, **options):
            return tool("worktree_search_actions", query=query, directory=str(host), **options)

        by_id = search("rEfOrMaTcOdE")
        by_name = search("Reformat Code")
        for result in (by_id, by_name):
            assert result["status"] == "ok", result
            reformat = next(a for a in result["actions"] if a["actionId"] == "ReformatCode")
            assert "Reformat" in reformat["name"] and not reformat["enabled"], reformat
        assert tool("worktree_action", actionId="ReformatCode", directory=str(host))["status"] == "unavailable"
        assert search("Worktree.Nonexistent")["actions"] == []
        bounded = search("Editor", limit=1)
        assert len(bounded["actions"]) == 1 and bounded["truncated"], bounded
        assert search(" ")["status"] == "invalid_query"
        assert search("Editor", limit=0)["status"] == "invalid_query"
        assert search("Editor", limit=101)["status"] == "invalid_query"
        assert tool("worktree_search_actions", query="Reformat", directory=str(project))["status"] == "target_not_open"
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
        with tempfile.TemporaryDirectory(prefix="worktree-explicit-", dir=project) as files:
            files = Path(files)
            requested, selected, unopened = (files / name for name in ("requested.json", "selected.json", "unopened.json"))
            unformatted = '{"explicit":true,"nested":{"value":1}}'
            for file in (requested, selected, unopened):
                file.write_text(unformatted)
            binary = files / "binary.png"
            binary.write_bytes(b"\x00\x01\x02")
            escape = files / "escape.json"
            outside = Path(temporary) / "outside.json"
            outside.write_text(unformatted)
            escape.symlink_to(outside)

            def project_tool(name, **arguments):
                return tool(name, projectPath=str(project), **arguments)

            def file_search(file):
                return project_tool("worktree_search_actions", query="ReformatCode",
                                    directory=str(project), filePath=str(file))

            def editor_text(file):
                response = call_tool("read_file", projectPath=str(project), file_path=str(file))
                return "\n".join(line.split(": ", 1)[1] for line in response["content"][0]["text"].splitlines())

            for file in (outside, escape, binary, files, files / "missing.json"):
                assert file_search(file)["status"] == "invalid_file", file
                assert project_tool("worktree_action", actionId="ReformatCode", directory=str(project),
                                    filePath=str(file))["status"] == "invalid_file", file
            assert project_tool("worktree_action", actionId="ReformatCode", directory=str(host),
                                filePath=str(requested))["status"] == "invalid_file"
            assert file_search(unopened)["status"] == "file_not_open"
            for file in (requested, selected):
                call_tool("open_file_in_editor", projectPath=str(project), filePath=str(file))
            editors_before = project_tool("get_all_open_file_paths")
            assert editors_before["activeFilePath"].endswith("selected.json"), editors_before
            available = file_search(requested.relative_to(project))
            assert available["status"] == "ok", available
            assert next(a for a in available["actions"] if a["actionId"] == "ReformatCode")["enabled"], available
            assert file_search(unopened)["status"] == "file_not_open"
            assert project_tool("get_all_open_file_paths") == editors_before, "Search changed editor tabs"
            assert requested.read_text() == selected.read_text() == unformatted, "Search mutated files"
            assert project_tool("worktree_action", actionId="ReformatCode", directory=str(project),
                                filePath=str(requested))["status"] == "dispatched"
            deadline = time.monotonic() + 15
            while True:
                formatted = editor_text(requested)
                if formatted != unformatted or time.monotonic() >= deadline:
                    break
                time.sleep(0.1)
            assert json.loads(formatted) == json.loads(unformatted), formatted
            assert formatted != unformatted, "Requested background file was not reformatted"
            assert editor_text(selected) == unformatted, "Focused file was changed instead"
            assert project_tool("get_all_open_file_paths") == editors_before, "Formatting changed editor selection"
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
        print("PASS: action search, explicit background-file formatting, invalid ownership/symlink/binary rejection, unopened-file rejection, no tab selection, limits, canonical reuse, merge/no-op, geometry, exact close, open+merge")


if __name__ == "__main__":
    main()
