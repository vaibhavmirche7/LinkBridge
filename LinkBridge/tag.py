#!/usr/bin/env python3

# Updates versionCode and versionName in build.gradle(.kts), commits, tags, and pushes
# Supports: manual version (vX.Y.Z), version bump (patch/minor/major)
# Supports: optional --changelog/-m (stored in Fastlane format under fastlane/metadata/.../changelogs/<versionCode>.txt)

# made by chatGPT

import re
import subprocess
import sys
import argparse
from pathlib import Path

MAX_CHANGELOG_SIZE = 500
FASTLANE_CHANGELOG_PATH = Path("fastlane/metadata/android/en-US/changelogs")

def fail(msg):
    print(f"Error: {msg}", file=sys.stderr)
    sys.exit(1)

def normalize_changelog(changelog: str) -> str:
    # Replace literal patterns (|\n|,  \n , \n) with actual newlines
    changelog = changelog.replace('|\\n|', '\n')
    changelog = changelog.replace(' \\n ', '\n')
    changelog = changelog.replace('\\n', '\n')
    return changelog


def run(cmd, dry_run=False):
    cmd_str = " ".join(cmd)
    if dry_run:
        print(f"[DRY RUN] Would execute: {cmd_str}")
        return ""
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.strip()

def has_uncommitted_changes():
    result = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True)
    return bool(result.stdout.strip())

def find_build_gradle():
    for pattern in ["app/build.gradle", "app/build.gradle.kts"]:
        path = Path(pattern)
        if path.exists():
            return path
    fail("Could not find app/build.gradle or app/build.gradle.kts")

def get_current_version(file_path):
    content = file_path.read_text()
    version_code_match = re.search(r"versionCode\s*=\s*(\d+)", content)
    version_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)

    if version_code_match and version_name_match:
        return version_name_match.group(1), int(version_code_match.group(1))
    else:
        fail("Could not parse current version from build.gradle")
        exit(1) # fail already exits, but for typing

def get_latest_git_tag():
    try:
        return run(["git", "describe", "--tags", "--abbrev=0"])
    except subprocess.CalledProcessError:
        return "v0.0.0"

def bump_version(tag: str, bump: str) -> str:
    if not re.match(r"^v\d+\.\d+\.\d+$", tag):
        fail(f"Invalid tag format: {tag}")

    major, minor, patch = map(int, tag[1:].split("."))

    if bump == "major":
        major += 1
        minor = 0
        patch = 0
    elif bump == "minor":
        minor += 1
        patch = 0
    elif bump == "patch":
        patch += 1
    else:
        fail(f"Invalid bump type: {bump}")

    return f"v{major}.{minor}.{patch}"

def update_version_file(file_path, version_name, version_code, dry_run=False):
    text = file_path.read_text()

    new_text, cnt_code = re.subn(
        r"versionCode\s*=\s*\d+",
        f"versionCode = {version_code}",
        text,
    )
    new_text, cnt_name = re.subn(
        r'versionName\s*=\s*"[^"]+"',
        f'versionName = "{version_name}"',
        new_text,
    )

    if cnt_code == 0 or cnt_name == 0:
        fail("Failed to update versionCode or versionName in " + str(file_path))

    if dry_run:
        print(f"[DRY RUN] Would update {file_path} with:")
        print(f"  versionCode = {version_code}")
        print(f"  versionName = \"{version_name}\"")
    else:
        file_path.write_text(new_text)

def write_fastlane_changelog(version_code: int, changelog: str, dry_run=False):
    changelog = normalize_changelog(changelog)

    encoded = changelog.encode("ascii")

    if len(encoded) > MAX_CHANGELOG_SIZE:
        fail(f"Changelog too long: {len(encoded)} bytes (max is {MAX_CHANGELOG_SIZE})")

    changelog_dir = FASTLANE_CHANGELOG_PATH
    changelog_file = changelog_dir / f"{version_code}.txt"

    if dry_run:
        print(f"[DRY RUN] Would write changelog to {changelog_file}:")
        print(changelog.strip())
        print(f"[DRY RUN] Would execute: git add {changelog_file}")
    else:
        changelog_dir.mkdir(parents=True, exist_ok=True)
        changelog_file.write_text(changelog.strip() + "\n")
        run(["git", "add", str(changelog_file)])

def main():
    parser = argparse.ArgumentParser(description="Tag version and update build.gradle & Fastlane changelog.")
    parser.add_argument("version_or_bump", nargs="?", help="vX.Y.Z or bump type: patch | minor | major")
    parser.add_argument("-m", "--changelog", help="Plain ASCII changelog (max 500 bytes) for Fastlane")
    parser.add_argument("-d", "--dry-run", action="store_true", help="Dry run mode, no changes made")
    parser.add_argument("-U", "--allow-uncommitted", action="store_true", help="Allow uncommitted changes")

    args = parser.parse_args()

    gradle_file = find_build_gradle()

    if not args.version_or_bump:
        version_name, version_code = get_current_version(gradle_file)

        print(f"Current versionName: {version_name}")
        print(f"Current versionCode: {version_code}")
        print(f"Latest Git tag: {get_latest_git_tag()}")
        if has_uncommitted_changes():
            print("+ Uncommitted changes present.")
        sys.exit(0)

    if not args.allow_uncommitted and has_uncommitted_changes():
        fail("Uncommitted changes present. Please commit or stash before tagging, or use -U/--allow-uncommitted.")

    if args.version_or_bump in ["patch", "minor", "major"]:
        current_tag = get_latest_git_tag()
        version = bump_version(current_tag, args.version_or_bump)
    else:
        version = args.version_or_bump
        if not re.match(r"^v\d+\.\d+\.\d+$", version):
            fail("Version must follow semantic versioning, e.g., v1.2.3")

    version_name = version[1:]
    major, minor, patch = map(int, version_name.split("."))
    version_code = major * 10000 + minor * 100 + patch

    # Warn if changelog missing
    if not args.changelog and not args.dry_run:
        confirm = input("No changelog provided. Continue without changelog? [y/N] ").strip().lower()
        if confirm != "y":
            print("Aborting.")
            sys.exit(1)

    print(f"Updating to versionName: {version_name}, versionCode: {version_code}")
    update_version_file(gradle_file, version_name, version_code, args.dry_run)

    run(["git", "add", str(gradle_file)], dry_run=args.dry_run)
    run(["git", "commit", "-m", version], dry_run=args.dry_run)

    if args.changelog:
        write_fastlane_changelog(version_code, args.changelog, args.dry_run)
        run(["git", "commit", "--amend", "--no-edit"], dry_run=args.dry_run)

    run(["git", "tag", version], dry_run=args.dry_run)
    run(["git", "push"], dry_run=args.dry_run)
    run(["git", "push", "origin", version], dry_run=args.dry_run)

    print(f"[{'DRY RUN' if args.dry_run else 'SUCCESS'}] Tagged and pushed {version} successfully.")

if __name__ == "__main__":
    main()