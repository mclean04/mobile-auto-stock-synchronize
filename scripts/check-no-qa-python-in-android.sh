#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
allowed="$repo_root/tools/generate_mobile_dtos.py"

if [ ! -f "$allowed" ]; then
  echo "Missing Android product generator: tools/generate_mobile_dtos.py" >&2
  exit 1
fi

unexpected=$(find "$repo_root/tools" -maxdepth 1 -type f -name '*.py' ! -path "$allowed" -print)
if [ -n "$unexpected" ]; then
  echo "QA Python belongs in /Users/tuanh/finance root/Test/TestProject/tools:" >&2
  echo "$unexpected" >&2
  exit 1
fi

echo "Android tools Python ownership guard passed"
