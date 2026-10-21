#!/bin/bash
# Fails when the real sync password (VIBE_PASSWORD) appears in tracked files or in any commit about to be pushed.
# The value comes from the environment, then ./.env, then a sibling VibeStation-Web/.env, and is never printed.
# Reads pre-push ref lines ("<local ref> <local sha> <remote ref> <remote sha>") on stdin to know which commits to scan.

zero="0000000000000000000000000000000000000000"
cd "$(git rev-parse --show-toplevel)" || exit 1

secret="${VIBE_PASSWORD:-}"
if [ -z "$secret" ]; then
    for env_file in .env ../VibeStation-Web/.env; do
        if [ -f "$env_file" ]; then
            secret="$(grep -m1 '^VIBE_PASSWORD=' "$env_file" | cut -d= -f2- | tr -d '\r' | sed -e "s/^[\"']//" -e "s/[\"']\$//")"
            [ -n "$secret" ] && break
        fi
    done
fi

if [ -z "$secret" ]; then
    echo "check-no-secret: no VIBE_PASSWORD found (env var or .env), so the secret scan was SKIPPED." >&2
    exit 0
fi

failed=false

files="$(git grep -lIF -e "$secret")"
if [ -n "$files" ]; then
    echo "check-no-secret: the real VIBE_PASSWORD is written in these tracked files:" >&2
    echo "$files" | sed 's/^/    /' >&2
    failed=true
fi

while read -r _local_ref local_sha _remote_ref remote_sha; do
    [ -z "$local_sha" ] || [ "$local_sha" = "$zero" ] && continue
    if [ "$remote_sha" = "$zero" ]; then
        range=("$local_sha" --not --remotes)
    else
        range=("$remote_sha..$local_sha")
    fi
    commits="$(git log -S"$secret" --format='%h %s' "${range[@]}")"
    if [ -n "$commits" ]; then
        echo "check-no-secret: the real VIBE_PASSWORD appears in the history being pushed (commits that add or remove it):" >&2
        echo "$commits" | sed 's/^/    /' >&2
        failed=true
    fi
done

if [ "$failed" = true ]; then
    echo "check-no-secret: push aborted. Tests should use a dummy password; keep the real one only in .env." >&2
    exit 1
fi
exit 0
