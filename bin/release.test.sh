#!/usr/bin/env bash
# What release.sh would get wrong silently: tagging a commit that is not on main, reusing a published version,
# suggesting the wrong next tag, or writing the release page when the publish failed. git runs for real against a
# local bare origin; gh is a stub that logs its arguments.
set -uo pipefail

script="$(cd "$(dirname "$0")" && pwd)/release.sh"
t=$(mktemp -d)
trap 'rm -rf "$t"' EXIT
rc=0

ok() { echo "ok   $1"; }
bad() {
    echo "FAIL $1"
    rc=1
}
# expect <behaviour> <command...>: the command succeeds; refuse: it fails.
expect() {
    local what=$1
    shift
    if "$@" >/dev/null 2>&1; then ok "$what"; else bad "$what"; fi
}
refuse() {
    local what=$1
    shift
    if "$@" >/dev/null 2>&1; then bad "$what"; else ok "$what"; fi
}

export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.invalid GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.invalid
git init -q --bare -b main "$t/origin.git"
git clone -q "$t/origin.git" "$t/repo" 2>/dev/null
cd "$t/repo" || exit 1
git commit -q --allow-empty -m one
git push -q origin main

mkdir "$t/bin"
cat >"$t/bin/gh" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$GH_LOG"
case "$1 $2" in
"run list") echo 42 ;;
"run watch") exit "${GH_WATCH_EXIT:-0}" ;;
esac
EOF
chmod +x "$t/bin/gh"
export PATH="$t/bin:$PATH" GH_LOG="$t/gh.log"

expect "suggests v0.0.1 when nothing is tagged" [ "$("$script" next)" = v0.0.1 ]
git tag v0.0.9 && git tag v0.0.10
expect "suggests the patch after the newest, compared as versions" [ "$("$script" next)" = v0.0.11 ]
git tag -d v0.0.9 v0.0.10 >/dev/null

refuse "refuses a tag that is not vX.Y.Z" "$script" check 0.0.2
git tag v0.0.1 && git push -q origin v0.0.1 && git tag -d v0.0.1 >/dev/null
refuse "refuses a tag that exists only on origin" "$script" check v0.0.1
git commit -q --allow-empty -m unpushed
refuse "refuses a commit that is not origin/main" "$script" check v0.0.2
git reset -q --hard origin/main
touch dirty
refuse "refuses a working tree with changes" "$script" check v0.0.2
rm dirty
expect "accepts a new tag on origin/main" "$script" check v0.0.2

TAG=v0.0.2 "$script" >/dev/null 2>&1
expect "pushes the tag to origin" git --git-dir="$t/origin.git" rev-parse -q --verify refs/tags/v0.0.2
expect "marks a 0.x release a pre-release" grep -q "release create v0.0.2 --verify-tag --generate-notes --prerelease" "$GH_LOG"

git commit -q --allow-empty -m two && git push -q origin main
: >"$GH_LOG"
GH_WATCH_EXIT=1 TAG=v0.0.3 "$script" >/dev/null 2>&1
refuse "writes no release page when the publish failed" grep -q "release create" "$GH_LOG"

git commit -q --allow-empty -m three && git push -q origin main
: >"$GH_LOG"
TAG=v1.0.0 "$script" >/dev/null 2>&1
refuse "does not mark 1.0.0 a pre-release" grep -q -- --prerelease "$GH_LOG"

exit $rc
