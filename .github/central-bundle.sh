#!/usr/bin/env bash
# Signs the release publication in a Gradle-built Maven repository and zips it as a Central Portal
# bundle without running build code. The key exists only in a temporary keyring, removed on exit, and
# every signature must verify against the published release-signing-key.asc.
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

public_key=$(dirname "$0")/../release-signing-key.asc
work=$(mktemp -d)
trap 'gpgconf --kill all || :; gpgconf --homedir "$work/public" --kill all || :; rm -rf "$work"' EXIT
export GNUPGHOME=$work/gnupg
mkdir -m 700 "$GNUPGHOME" "$work/public"
gpg --batch --quiet --import <<<"$SIGNING_KEY"
gpg --homedir "$work/public" --batch --quiet --import "$public_key"

mkdir -p "$work/bundle/$path"
cp "$repository/$path"/* "$work/bundle/$path/"
for artifact in $artifacts; do
    file=$work/bundle/$path/$artifact
    gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 --armor --detach-sign "$file" 3<<<"$SIGNING_PASSPHRASE"
    gpg --homedir "$work/public" --batch --quiet --verify "$file.asc" "$file" 2>/dev/null ||
        { echo "SIGNING_KEY did not make a signature that verifies with $public_key" >&2; exit 1; }
done
(cd "$work/bundle" && zip -qrX - com) >"$bundle"
gpg --homedir "$work/public" --batch --list-keys --with-colons | awk -F: '$1 == "fpr" { print "Signed for " $10; exit }'
