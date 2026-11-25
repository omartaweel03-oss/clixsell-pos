#!/usr/bin/env bash
# Prints a fingerprint of everything the Flutter web build is made from: the CONTENT of every
# file under lib/ and web/ (tracked or new, committed or not) plus pubspec.yaml / pubspec.lock.
# Only file contents: the same sources always give the same fingerprint, whatever the shell. Used by build_web.sh (written next to the build) and run.sh
# (compared before any browser suite), so a stale build/web can never be tested by mistake.
# Content hashes, not modification times.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
{
  git ls-files --cached --others --exclude-standard -- lib web pubspec.yaml pubspec.lock analysis_options.yaml \
    | LC_ALL=C sort -u | while IFS= read -r f; do
        [ -f "$f" ] && printf '%s %s\n' "$(git hash-object -- "$f")" "$f"
      done
} | sha256sum | cut -d' ' -f1
