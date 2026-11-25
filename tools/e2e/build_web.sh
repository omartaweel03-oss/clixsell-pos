#!/usr/bin/env bash
# Builds the Flutter web app used by the browser suites and writes its fingerprint
# (web_stamp.sh) and the git commit into build/web/.clixsell_web_stamp.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"
flutter build web --no-web-resources-cdn
{
  echo "stamp=$("$HERE/web_stamp.sh")"
  echo "commit=$(git rev-parse HEAD)"
  echo "flutter=$(flutter --version --machine 2>/dev/null | sed -n 's/.*"frameworkVersion": "\(.*\)".*/\1/p')"
  echo "dirty=$([ -n "$(git status --porcelain -- lib web pubspec.yaml pubspec.lock)" ] && echo yes || echo no)"
} > build/web/.clixsell_web_stamp
cat build/web/.clixsell_web_stamp
