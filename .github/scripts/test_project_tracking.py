import unittest
from unittest.mock import patch

import project_tracking as tracking


def connection(nodes, truncated=False):
    return {"nodes": nodes, "pageInfo": {"hasNextPage": truncated}}


def issue_link(identifier="issue-20", repository=tracking.REPOSITORY):
    return {"id": identifier, "repository": {"nameWithOwner": repository}}


class TrackingTests(unittest.TestCase):
    def test_all_closing_issues_must_be_local_project_members(self):
        cases = [([], set(), False),
                 ([issue_link()], set(), False),
                 ([issue_link()], {"issue-20"}, True),
                 ([issue_link(), issue_link("missing")], {"issue-20"}, False),
                 ([issue_link(repository="other/repo")], {"issue-20"}, False)]
        for links, members, accepted in cases:
            with self.subTest(links=links, members=members):
                error = tracking.violation({"closingIssuesReferences": connection(links)}, members)
                self.assertEqual(error is None, accepted)
        with self.assertRaises(RuntimeError):
            tracking.violation({"closingIssuesReferences": connection([issue_link()], True)}, {"issue-20"})

    def test_done_requires_closed_issue_and_merged_pr_or_explicit_research(self):
        issue = {"id": "issue-20", "state": "OPEN", "labels": connection([]),
                 "closedByPullRequestsReferences": connection([])}
        self.assertEqual(tracking.desired_status(issue, set(), None), "Todo")
        self.assertEqual(tracking.desired_status(issue, set(), "In Progress"), "In Progress")
        self.assertEqual(tracking.desired_status(issue, {"issue-20"}, "Todo"), "In Progress")
        issue["state"] = "CLOSED"
        self.assertEqual(tracking.desired_status(issue, set(), "Done"), "Todo")
        issue["closedByPullRequestsReferences"] = connection([{**issue_link(), "merged": False}])
        self.assertEqual(tracking.desired_status(issue, set(), "Todo"), "Todo")
        issue["closedByPullRequestsReferences"]["nodes"][0]["merged"] = True
        self.assertEqual(tracking.desired_status(issue, set(), "In Progress"), "Done")
        issue["state"] = "OPEN"
        self.assertEqual(tracking.desired_status(issue, set(), "Done"), "Todo")
        issue["state"] = "CLOSED"
        issue["closedByPullRequestsReferences"] = connection([])
        issue["labels"] = connection([{"name": "research"}])
        self.assertEqual(tracking.desired_status(issue, set(), "Todo"), "Done")

    def test_removed_membership_fails_without_reenrollment(self):
        pr = {"number": 21, "headRefOid": "a" * 40,
              "closingIssuesReferences": connection([issue_link()])}
        with patch.object(tracking, "connection", return_value=iter([pr])), \
                patch.object(tracking, "project_items", return_value=[]), \
                patch.object(tracking, "api") as api, patch.object(tracking, "publish") as publish:
            self.assertFalse(tracking.reconcile())
            self.assertEqual([call.args[1] for call in publish.call_args_list], [None, "failure"])
            api.assert_not_called()

    def test_project_api_failure_invalidates_previous_success(self):
        pr = {"number": 21, "headRefOid": "a" * 40}
        with patch.object(tracking, "connection", return_value=iter([pr])), \
                patch.object(tracking, "project_items", side_effect=RuntimeError("unavailable")), \
                patch.object(tracking, "publish") as publish:
            with self.assertRaises(RuntimeError):
                tracking.reconcile()
            self.assertEqual([call.args[1] for call in publish.call_args_list], [None, "failure"])

    def test_enrollment_rejects_foreign_issues_and_non_creation_events(self):
        event = {"action": "opened", "issue": {"node_id": "foreign", "number": 20}}
        with patch.object(tracking, "api", return_value={"node": issue_link(repository="other/repo")}) as api:
            with self.assertRaises(ValueError):
                tracking.enroll(event)
            self.assertEqual(api.call_count, 1)  # Only the ownership read; no project mutation.
        with self.assertRaises(ValueError):
            tracking.enroll({**event, "action": "edited"})


if __name__ == "__main__":
    unittest.main()
