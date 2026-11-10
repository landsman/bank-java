#!/usr/bin/env bash
# Releases the commit at the tip of origin/main: asks for the tag, pushes it, waits for release.yml to publish the
# jars, then writes the release page. A 0.x version is marked a pre-release.
#
#   release.sh              ask for the tag (TAG=v0.0.2 answers instead), confirm, release
#   release.sh next         the tag after the newest one: v0.0.1 -> v0.0.2, none -> v0.0.1
#   release.sh check <tag>  refuse a tag that is not vX.Y.Z, already exists, or would not land on origin/main
set -euo pipefail

next() {
    local last
    last=$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n 1)
    if [ -z "$last" ]; then
        echo v0.0.1
        return
    fi
    local major minor patch
    IFS=. read -r major minor patch <<<"${last#v}"
    echo "v$major.$minor.$((patch + 1))"
}

check() {
    local tag=$1
    [[ $tag =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "$tag is not vX.Y.Z"
    if git rev-parse -q --verify "refs/tags/$tag" >/dev/null || [ -n "$(git ls-remote --tags origin "refs/tags/$tag")" ]; then
        fail "$tag already exists; a published version cannot be published again"
    fi
    [ -z "$(git status --porcelain)" ] || fail "the working tree has changes; release from a clean checkout of main"
    [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] ||
        fail "HEAD is not origin/main; run: git switch main && git pull --ff-only"
}

release() {
    git fetch -q --tags origin main
    local tag=${TAG:-}
    if [ -z "$tag" ]; then
        [ -r /dev/tty ] || fail "no terminal to ask on; pass TAG=vX.Y.Z"
        local suggested
        suggested=$(next)
        read -r -p "tag [$suggested]: " tag </dev/tty
        tag=${tag:-$suggested}
    fi
    check "$tag"
    if [ -z "${TAG:-}" ]; then
        local answer
        read -r -p "release $tag at $(git log -1 --format='%h %s')? [y/N] " answer </dev/tty
        [ "$answer" = y ] || fail "not released"
    fi

    git tag -a "$tag" -m "$tag"
    git push -q origin "$tag"
    watch "$tag"

    local prerelease=()
    [[ $tag == v0.* ]] && prerelease=(--prerelease)
    gh release create "$tag" --verify-tag --generate-notes "${prerelease[@]}"
}

# The run a tag push starts takes a few seconds to appear.
watch() {
    local tag=$1 run=""
    for _ in $(seq 30); do
        run=$(gh run list --workflow release.yml --branch "$tag" --limit 1 --json databaseId --jq '.[0].databaseId // empty')
        [ -n "$run" ] && break
        sleep 2
    done
    [ -n "$run" ] || fail "release.yml did not start for $tag; the tag is pushed, look at the repo's Actions"
    gh run watch "$run" --exit-status ||
        fail "release.yml failed for $tag; no release page written. Fix it and release the next patch"
}

fail() {
    echo "release: $*" >&2
    exit 1
}

case "${1:-}" in
"") release ;;
next) next ;;
check) check "${2:?usage: release.sh check <tag>}" ;;
*) fail "unknown subcommand $1" ;;
esac
