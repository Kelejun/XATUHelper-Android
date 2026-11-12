#!/usr/bin/env python3
"""Validate scheduled build decisions without contacting GitHub."""

import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "prerelease_context", Path(__file__).with_name("prerelease-context.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
CURRENT = "a" * 40
PREVIOUS = "b" * 40
RELEASE = {"assets": [{"name": "prerelease.update.json", "id": 42}]}


class PrereleaseContextTest(unittest.TestCase):
    def test_schedule_skips_identical_published_commit(self):
        with patch.object(module, "api", side_effect=[RELEASE, {"commit": CURRENT}]), \
                patch.object(module.subprocess, "run") as run:
            self.assertEqual(module.context("owner/repo", CURRENT, "schedule"), (False, ""))
            run.assert_not_called()

    def test_schedule_builds_new_commits_and_collects_unpublished_history(self):
        with patch.object(module, "api", side_effect=[RELEASE, {"commit": PREVIOUS}]), \
                patch.object(module.subprocess, "run", side_effect=[
                    subprocess.CompletedProcess([], 0),
                    subprocess.CompletedProcess([], 0, "- aaaaaaa 修复登录\n")]) as run:
            self.assertEqual(module.context("owner/repo", CURRENT, "schedule"),
                             (True, "- aaaaaaa 修复登录"))
            self.assertIn(f"{PREVIOUS}..{CURRENT}", run.call_args.args[0])

    def test_manual_build_is_allowed_without_new_commits(self):
        with patch.object(module, "api", side_effect=[RELEASE, {"commit": CURRENT}]), \
                patch.object(module.subprocess, "run", side_effect=[
                    subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 0, "")]):
            self.assertEqual(module.context("owner/repo", CURRENT, "workflow_dispatch"),
                             (True, "本次构建无新增提交。"))

    def test_first_build_uses_recent_history(self):
        with patch.object(module, "api", return_value=None), \
                patch.object(module.subprocess, "run", return_value=
                             subprocess.CompletedProcess([], 0, "- aaaaaaa Initial\n")) as run:
            self.assertTrue(module.context("owner/repo", CURRENT, "schedule")[0])
            self.assertIn("30", run.call_args.args[0])

    def test_rewritten_history_uses_recent_history(self):
        with patch.object(module, "api", side_effect=[RELEASE, {"commit": PREVIOUS}]), \
                patch.object(module.subprocess, "run", side_effect=[
                    subprocess.CompletedProcess([], 1),
                    subprocess.CompletedProcess([], 0, "- aaaaaaa Updated\n")]) as run:
            self.assertTrue(module.context("owner/repo", CURRENT, "schedule")[0])
            self.assertIn("30", run.call_args.args[0])

    def test_api_failure_is_not_treated_as_no_changes(self):
        with patch.object(module.subprocess, "run", return_value=
                         subprocess.CompletedProcess([], 1, "", "HTTP 403 Forbidden")):
            with self.assertRaises(RuntimeError):
                module.context("owner/repo", CURRENT, "schedule")

    def test_missing_manifest_asset_rebuilds(self):
        with patch.object(module, "api", return_value={"assets": []}), \
                patch.object(module.subprocess, "run", return_value=
                             subprocess.CompletedProcess([], 0, "- aaaaaaa Initial\n")):
            self.assertTrue(module.context("owner/repo", CURRENT, "schedule")[0])


if __name__ == "__main__":
    unittest.main()
