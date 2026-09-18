#!/usr/bin/env bash
# Local-only source revision history for miku-player-kotlin.
# The main .git tracks distributable APK artifacts and blanket-ignores source
# (.gitignore = '*'). This .gitsrc repo tracks the SOURCE for revision history
# and has NO remote (source stays private per project policy).
#
# Because the shared worktree .gitignore is '*', ignore-precedence can't un-ignore
# source here — so we force-add a curated file list (source only, never build
# output, APKs, keystores, or secrets).
#
#   ./src-history.sh save "message"   # snapshot current source
#   ./src-history.sh log              # revision list
#   ./src-history.sh <any git args>   # raw git against the source repo
set -euo pipefail
cd "$(dirname "$0")"
export GIT_DIR="$PWD/.gitsrc"
export GIT_WORK_TREE="$PWD"

stage_source() {
  # Curated source set. -print0/-z keeps paths with spaces/emoji safe.
  find . \
    -type d \( -name build -o -name .gradle -o -name .cxx -o -name .gitsrc -o -name .idea -o -name captures \) -prune -o \
    -type f \( \
        -name '*.kt' -o -name '*.java' -o -name '*.kts' -o -name '*.gradle' \
        -o -name '*.xml' -o -name '*.pro' -o -name '*.properties' \
        -o -name '*.json' -o -name '*.toml' -o -name '*.md' -o -name '*.txt' \
        -o -name '*.png' -o -name '*.webp' -o -name '*.jpg' -o -name '*.svg' \
        -o -name '*.ttf' -o -name '*.otf' -o -name '*.ogg' -o -name '*.mp3' \
        -o -name '*.sh' -o -name '*.milk' -o -name '*.cpp' -o -name '*.h' \
        -o -name '*.hpp' -o -name '*.c' -o -name '*.cmake' -o -name 'CMakeLists.txt' \
        -o -name '*.glsl' -o -name '*.frag' -o -name '*.vert' -o -name '*.pro' \
     \) \
    ! -name '*.apk' ! -name '*.aab' ! -name '*.keystore' ! -name '*.jks' \
    ! -name 'local.properties' ! -name 'arco.properties' \
    -print0 | xargs -0 git add -f --
  # Record deletions of files already tracked but now gone.
  git add -u
}

case "${1:-}" in
  save)
    stage_source
    if git diff --cached --quiet; then echo "no source changes"; exit 0; fi
    git commit -q -m "${2:-snapshot}" && git --no-pager log --oneline -1
    ;;
  log)   git --no-pager log --oneline "${@:2}" ;;
  *)     git "$@" ;;
esac
