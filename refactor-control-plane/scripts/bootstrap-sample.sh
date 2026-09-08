#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="$project_root/samples/workspaces/springmvc-blog"
repository="https://github.com/eyupgevenim/SpringMvcBlog.git"
commit="5e62608af333b5757bf767e05b19e2b0d56f0afb"

if [[ ! -d "$target/.git" ]]; then
  mkdir -p "$(dirname "$target")"
  git clone --no-checkout "$repository" "$target"
fi

git -C "$target" fetch origin
git -C "$target" checkout --detach "$commit"
actual="$(git -C "$target" rev-parse HEAD)"
[[ "$actual" == "$commit" ]] || { echo "SHA mismatch: $actual" >&2; exit 1; }
printf 'Legacy sample ready at %s (%s)\n' "$target" "$commit"
