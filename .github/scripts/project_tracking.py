#!/usr/bin/env python3
"""Reconcile trusted GitHub metadata; never fetch or execute pull-request code."""

import argparse
import json
import os
import sys
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

REPOSITORY = "ValTM/intellij-worktree-mcp"
PROJECT_ID = "PVT_kwHOAMa5Fc4BrAdI"
CHECK = "project-tracking"


def api(path, payload=None, *, project=False, method=None):
    token = os.environ["PROJECT_TOKEN" if project else "GITHUB_TOKEN"]
    if not token:
        raise RuntimeError("Missing automation token")
    request = Request(
        "https://api.github.com/" + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        method=method,
        headers={"Authorization": f"Bearer {token}",
                 "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28"},
    )
    try:
        with urlopen(request, timeout=30) as response:
            result = json.load(response)
    except HTTPError as error:
        raise RuntimeError(f"GitHub API failed: HTTP {error.code}") from None
    if "errors" in result:
        raise RuntimeError("GitHub GraphQL query failed")
    return result.get("data", result)


def connection(query, variables, path, *, project=False):
    cursor = None
    while True:
        data = api("graphql", {"query": query, "variables": {**variables, "cursor": cursor}},
                   project=project)
        for key in path:
            data = data[key]
        yield from data["nodes"]
        if not data["pageInfo"]["hasNextPage"]:
            return
        cursor = data["pageInfo"]["endCursor"]


def complete(connection):
    # Nested connections fail closed instead of accepting a truncated relationship list.
    if connection["pageInfo"]["hasNextPage"]:
        raise RuntimeError("Relationship list exceeds 100; paginate before enabling this PR")
    return connection["nodes"]


def violation(pr, members):
    issues = complete(pr["closingIssuesReferences"])
    if not issues:
        return "Link an issue with a closing relationship (for example, Closes #20)."
    if any(issue["repository"]["nameWithOwner"] != REPOSITORY for issue in issues):
        return "Every closing issue must belong to this repository."
    if any(issue["id"] not in members for issue in issues):
        return "Every closing issue must belong to Worktree Lifecycle MCP."
    return None


def desired_status(issue, active, current):
    merged = any(pr["merged"] and pr["repository"]["nameWithOwner"] == REPOSITORY
                 for pr in complete(issue["closedByPullRequestsReferences"]))
    research = any(label["name"] == "research" for label in complete(issue["labels"]))
    if issue["state"] == "CLOSED" and (merged or research):
        return "Done"
    if issue["id"] in active:
        return "In Progress"
    # Preserve manual work-in-progress before a PR exists, but undo stale Done states.
    return current if current and current != "Done" else "Todo"


PRS = """query($cursor:String) {
  repository(owner:"ValTM", name:"intellij-worktree-mcp") {
    pullRequests(first:100, after:$cursor, states:OPEN, baseRefName:"main") {
      nodes { number headRefOid closingIssuesReferences(first:100) {
        nodes { id repository { nameWithOwner } } pageInfo { hasNextPage }
      } } pageInfo { hasNextPage endCursor }
    }
  }
}"""
ITEMS = """query($cursor:String, $project:ID!) {
  node(id:$project) { ... on ProjectV2 {
    items(first:100, after:$cursor) { nodes {
      id fieldValueByName(name:"Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
      content { ... on Issue {
        id number state repository { nameWithOwner }
        labels(first:100) { nodes { name } pageInfo { hasNextPage } }
        closedByPullRequestsReferences(first:100, includeClosedPrs:true) {
          nodes { merged repository { nameWithOwner } } pageInfo { hasNextPage }
        }
      } }
    } pageInfo { hasNextPage endCursor } }
  } }
}"""


def project_items():
    return list(connection(ITEMS, {"project": PROJECT_ID}, ("node", "items"), project=True))


def publish(pr, conclusion, summary):
    check_id = pr.get("check_id")
    result = api(f"repos/{REPOSITORY}/check-runs" + (f"/{check_id}" if check_id else ""), {
        "name": CHECK,
        **({} if check_id else {"head_sha": pr["headRefOid"]}),
        "status": "in_progress" if conclusion is None else "completed",
        **({"conclusion": conclusion} if conclusion else {}),
        "details_url": (f"https://github.com/{REPOSITORY}/actions/runs/"
                        + os.environ["GITHUB_RUN_ID"]),
        "output": {"title": CHECK, "summary": summary},
    }, method="PATCH" if check_id else "POST")
    pr["check_id"] = result["id"]


def enroll(event, dry_run=False):
    if event.get("action") != "opened" or "issue" not in event:
        raise ValueError("Enrollment requires a newly opened issue event")
    issue_id = event["issue"]["node_id"]
    issue = api("graphql", {"query": """query($issue:ID!) {
      node(id:$issue) { ... on Issue { repository { nameWithOwner } } }
    }""", "variables": {"issue": issue_id}})["node"]
    if issue["repository"]["nameWithOwner"] != REPOSITORY:
        raise ValueError("Issue belongs to another repository")
    print(f"Enroll issue #{event['issue']['number']}")
    if not dry_run:
        api("graphql", {"query": """mutation($project:ID!, $issue:ID!) {
          addProjectV2ItemById(input:{projectId:$project, contentId:$issue}) { item { id } }
        }""", "variables": {"project": PROJECT_ID, "issue": issue_id}}, project=True)


def reconcile(dry_run=False):
    prs = list(connection(PRS, {}, ("repository", "pullRequests")))
    # Invalidate old successes before reading project metadata or performing mutations.
    if not dry_run:
        for pr in prs:
            publish(pr, None, "Rechecking issue/project relationships.")
    try:
        items = project_items()
        members = {item["content"]["id"] for item in items
                   if item["content"] and item["content"].get("repository", {}).get("nameWithOwner") == REPOSITORY}
        results = [(pr, violation(pr, members)) for pr in prs]
        active = {issue["id"] for pr in prs for issue in complete(pr["closingIssuesReferences"])}
        changes = []
        for item in items:
            issue = item["content"]
            if not issue or issue.get("repository", {}).get("nameWithOwner") != REPOSITORY:
                continue
            current = (item["fieldValueByName"] or {}).get("name")
            status = desired_status(issue, active, current)
            if status != current:
                changes.append((item, status))
        if changes and not dry_run:
            fields = api("graphql", {"query": """query($project:ID!) {
              node(id:$project) { ... on ProjectV2 { field(name:"Status") {
                ... on ProjectV2SingleSelectField { id options { id name } }
              } } }
            }""", "variables": {"project": PROJECT_ID}}, project=True)["node"]["field"]
            options = {option["name"]: option["id"] for option in fields["options"]}
        for item, status in changes:
            print(f"Issue #{item['content']['number']}: {status}")
            if not dry_run:
                api("graphql", {"query": """mutation($project:ID!, $item:ID!, $field:ID!, $value:String!) {
                  updateProjectV2ItemFieldValue(input:{projectId:$project, itemId:$item,
                    fieldId:$field, value:{singleSelectOptionId:$value}}) { projectV2Item { id } }
                }""", "variables": {"project": PROJECT_ID, "item": item["id"],
                                     "field": fields["id"], "value": options[status]}}, project=True)
        for pr, error in results:
            print(f"PR #{pr['number']}: {error or 'PASS'}")
            if not dry_run:
                publish(pr, "failure" if error else "success", error or "All closing issues belong to this project.")
        print(f"Checked {len(prs)} open PRs and {len(members)} project issues.")
        return not any(error for _, error in results)
    except Exception:
        if not dry_run:
            for pr in prs:
                publish(pr, "failure", "Tracking could not be verified. Check the workflow run and token permissions.")
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Read live metadata without writing checks or project fields")
    parser.add_argument("--enroll-only", action="store_true", help="Enroll a newly opened issue without reconciling PRs")
    args = parser.parse_args()
    if os.environ["GITHUB_REPOSITORY"] != REPOSITORY:
        sys.exit("This workflow is scoped to ValTM/intellij-worktree-mcp")
    if args.enroll_only:
        enroll(json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text()), args.dry_run)
    else:
        sys.exit(0 if reconcile(args.dry_run) else 1)
