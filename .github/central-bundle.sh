#!/usr/bin/env bash
# Signs the release publication in a Gradle-built Maven repository and zips it as a Central Portal
# bundle without running build code. The key exists only in a temporary keyring, removed on exit.
#
#   SIGNING_KEY=<ASCII-armoured secret key> SIGNING_PASSPHRASE=<its passphrase, may be empty> \
#     .github/central-bundle.sh <version> <repository> <bundle.zip>
set -euo pipefail
export LC_ALL=C

version=$1 repository=$2 bundle=$3
if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Not a release version: $version" >&2
    exit 1
fi
path=com/klethron/tsnet/$version
if [[ ! -d $repository/$path ]]; then
    echo "No $path in $repository: tag only a commit whose gradle.properties sets version=$version" >&2
    exit 1
fi
artifacts=$(printf "tsnet-$version%s\n" .aar .module .pom -javadoc.jar -sources.jar | sort)
found=$(cd "$repository/$path" && find . -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' \
    ! -name '*.sha512' -printf '%P\n' | sort)
if [[ $found != "$artifacts" ]]; then
    printf 'Expected exactly these artifacts in %s:\n%s\nFound:\n%s\n' "$repository/$path" "$artifacts" "$found" >&2
    exit 1
fi

work=$(mktemp -d)
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$work"' EXIT
export GNUPGHOME=$work/gnupg
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --import <<<"$SIGNING_KEY"

mkdir -p "$work/bundle/$path"
cp "$repository/$path"/* "$work/bundle/$path/"
for artifact in $artifacts; do
    file=$work/bundle/$path/$artifact
    for sum in md5 sha1 sha256 sha512; do
        [[ $("${sum}sum" "$file" | cut -d' ' -f1) == "$(cat "$file.$sum")" ]] || { echo "Bad $sum: $artifact" >&2; exit 1; }
    done
    gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 --armor --detach-sign "$file" 3<<<"$SIGNING_PASSPHRASE"
    gpg --batch --quiet --verify "$file.asc" "$file" 2>/dev/null
done
(cd "$work/bundle" && zip -qrX - com) >"$bundle"
gpg --batch --list-keys --with-colons | awk -F: '$1 == "fpr" { print "Signed with " $10; exit }'
