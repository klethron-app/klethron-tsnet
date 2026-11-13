#!/usr/bin/env bash
# javadoc.io's versions page requires explicit sync/upload form submissions; visiting /doc is not enough.
set -euo pipefail

version=$1
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Not a release version: $version" >&2; exit 1; }
versions=https://javadoc.io/versions/com.klethron/tsnet
docs=https://javadoc.io/static/com.klethron/tsnet/$version/index.html
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

request() {
    curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
        --cookie "$work/cookies" --cookie-jar "$work/cookies" "$@"
}

post_form() {
    local token
    token=$(sed -n 's/.*name="csrfToken" value="\([^"]*\)".*/\1/p' "$work/page" | head -n 1)
    [[ -n $token ]] || { echo "No javadoc.io CSRF token found" >&2; return 1; }
    request --location --data-urlencode "csrfToken=$token" "$@" --output "$work/page"
}

SECONDS=0
for attempt in {1..6}; do
    # Start at 5 minutes, then every 5 minutes through minute 30, allowing for request time.
    delay=$((attempt * 300 - SECONDS))
    if ((delay > 0)); then sleep "$delay"; fi
    echo "Importing com.klethron:tsnet:$version (attempt $attempt/6)"
    if request --location "$versions" --output "$work/page" &&
        post_form "$versions/sync" &&
        grep -Fq "name=\"versionId\" value=\"$version\"" "$work/page"; then
        post_form --data-urlencode "versionId=$version" "$versions/upload" ||
            echo "Upload request failed; checking whether documentation is already available" >&2
    fi
    # The wrapper can redirect to the versions list with HTTP 200 before docs exist.
    # Require HTTP 200 from the exact version's static page, without following redirects.
    if status=$(request --output /dev/null --write-out '%{http_code}' "$docs") && [[ $status == 200 ]]; then
        echo "Javadoc available: https://javadoc.io/doc/com.klethron/tsnet/$version/"
        exit 0
    fi
done

echo "::error::Javadoc for $version is still unavailable after six attempts over 30 minutes: $versions"
exit 1
