#!/usr/bin/env python3
"""Prepare signed CI APKs and update metadata; never publish or invoke Git."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil


ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def prepare(apk_dir, output_dir, channel, repository, commit, changelog=""):
    if channel not in ("prerelease", "release"):
        raise ValueError("Unknown release channel")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid repository")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a full commit SHA")

    metadata = json.loads((apk_dir / "output-metadata.json").read_text())
    if metadata.get("applicationId") != "com.xatune.app":
        raise ValueError("Expected the production application ID")
    elements = metadata["elements"]
    if len(elements) != len(ABIS):
        raise ValueError("Expected exactly four ABI APKs")
    version_name = elements[0]["versionName"]
    version_code = elements[0]["versionCode"]
    if not isinstance(version_name, str) or not re.fullmatch(r"\d+\.\d+\.\d+", version_name):
        raise ValueError("Expected a numeric versionName")
    if type(version_code) is not int or not 0 < version_code <= 2100000000:
        raise ValueError("Invalid Android versionCode")

    sources = {}
    for element in elements:
        if (element["versionName"], element["versionCode"]) != (version_name, version_code):
            raise ValueError("All ABI APKs must have the same version")
        filters = element["filters"]
        if len(filters) != 1 or filters[0]["filterType"] != "ABI":
            raise ValueError("Expected an ABI split, not a universal APK")
        abi = filters[0]["value"]
        if abi not in ABIS or abi in sources:
            raise ValueError("Unexpected or duplicate ABI")
        if element["outputFile"] != f"app-{abi}-release-unsigned.apk":
            raise ValueError("Unexpected unsigned APK filename")
        source = apk_dir / f"app-{abi}-release-signed.apk"
        if not source.is_file() or source.stat().st_size == 0:
            raise ValueError(f"Missing or empty signed APK: {source.name}")
        sources[abi] = source

    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError("Output directory must be empty to avoid stale release assets")
    output_dir.mkdir(parents=True, exist_ok=True)
    stable = channel == "release"
    prefix = (f"XATune-v{version_name}-release" if stable else
              f"XATune-v{version_name}.r{version_code}.{commit[:7]}")
    tag = f"v{version_name}" if stable else "Prerelease"
    release_url = f"https://github.com/{repository}/releases/tag/{tag}"
    download_url = f"https://github.com/{repository}/releases/download/{tag}"
    assets = {}
    for abi in ABIS:
        filename = f"{prefix}-{abi}.apk"
        destination = output_dir / filename
        shutil.copyfile(sources[abi], destination)
        assets[abi] = {
            "name": filename,
            "url": f"{download_url}/{filename}",
            "size": destination.stat().st_size,
            "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
        }

    notes = f"XATune {version_name}（构建 {version_code}）"
    manifest = {
        "schemaVersion": 1,
        "channel": "stable" if stable else "prerelease",
        "applicationId": metadata["applicationId"],
        "versionCode": version_code,
        "versionName": version_name,
        "commit": commit,
        "releaseUrl": release_url,
        "notes": notes,
        "assets": assets,
    }
    manifest_name = f"{channel}.update.json"
    (output_dir / manifest_name).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "SHA256SUMS").write_text(
        "".join(f"{asset['sha256']}  {asset['name']}\n" for asset in assets.values()),
        encoding="utf-8")
    (output_dir / "release-notes.md").write_text(
        ("**⚠️ 此处提供的是开发版的自动构建，可能出现不稳定、功能没做完等情况。除非你能接受这些问题，否则我们建议你下载下方带正式版本号的 Release**\n\n"
         if not stable else "") +
        f"**对于大多数用户，如果你不知道该下哪个，请下载{assets['arm64-v8a']['name']}**\n\n"
        f"{notes}\n\nCommit: `{commit}`\n\n"
        + (f"### 提交记录\n\n{changelog.strip() or '本次构建无新增提交。'}\n\n" if not stable else "")
        + "\n".join(f"- `{abi}`：`{asset['name']}`" for abi, asset in assets.items()) + "\n",
        encoding="utf-8")
    return {"prefix": prefix, "tag": tag, "version_name": version_name,
            "manifest_name": manifest_name}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--channel", choices=("prerelease", "release"), required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--changelog-file", type=Path)
    args = parser.parse_args()
    try:
        changelog = args.changelog_file.read_text(encoding="utf-8") if args.changelog_file else ""
        outputs = prepare(args.apk_dir, args.output_dir, args.channel, args.repository, args.commit,
                          changelog)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, f"Cannot prepare release assets: {error}\n")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write("".join(f"{key}={value}\n" for key, value in outputs.items()))
    print(json.dumps(outputs))


if __name__ == "__main__":
    main()
