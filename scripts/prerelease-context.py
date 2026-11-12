#!/usr/bin/env python3
"""Compare HEAD with the published prerelease manifest and collect commit subjects."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import uuid


def api(path, *options):
    result = subprocess.run(["gh", "api", path, *options], capture_output=True, text=True)
    if result.returncode:
        if "(HTTP 404)" in result.stderr:
            return None
        raise RuntimeError(result.stderr.strip())
    return json.loads(result.stdout)


def context(repository, commit, event):
    previous = None
    release = api(f"repos/{repository}/releases/tags/Prerelease")
    if release:
        asset = next((item for item in release["assets"]
                      if item["name"] == "prerelease.update.json"), None)
        if asset:
            manifest = api(f"repos/{repository}/releases/assets/{asset['id']}",
                           "-H", "Accept: application/octet-stream")
            if manifest is None:
                raise RuntimeError("Published prerelease manifest disappeared")
            previous = manifest["commit"]
            if not isinstance(previous, str) or not re.fullmatch(r"[0-9a-f]{40}", previous):
                raise ValueError("Invalid published prerelease commit")

    should_build = event != "schedule" or previous != commit
    changelog = ""
    if should_build:
        revision = commit
        limit = ["-n", "30"]
        if previous:
            ancestor = subprocess.run(["git", "merge-base", "--is-ancestor", previous, commit],
                                      capture_output=True)
            if ancestor.returncode == 0:
                revision = f"{previous}..{commit}"
                limit = []
        result = subprocess.run(["git", "log", *limit, "--format=- %h %s", revision, "--"],
                                check=True, capture_output=True, text=True)
        changelog = result.stdout.strip() or "本次构建无新增提交。"
    return should_build, changelog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--event", required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()
    should_build, changelog = context(args.repository, args.commit, args.event)
    delimiter = "changelog_" + uuid.uuid4().hex
    with args.github_output.open("a", encoding="utf-8") as output:
        output.write(f"should_build={str(should_build).lower()}\n")
        output.write(f"changelog<<{delimiter}\n{changelog}\n{delimiter}\n")
    print("Build prerelease" if should_build else "No new commits; skipping prerelease")


if __name__ == "__main__":
    main()
