#!/usr/bin/env bash
set -euo pipefail

# Vendors the PhantomDNS engine source into third_party/PhantomDns at the commit
# pinned in third_party/PhantomDns.UPSTREAM. Mirrors how CottenDns is vendored:
# the Go source is checked in so the Android binary can be reproducibly built and
# verified. Re-run after bumping the pinned commit.
#
# Usage: scripts/vendor-phantomdns.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIN_FILE="${REPO_ROOT}/third_party/PhantomDns.UPSTREAM"
DEST="${REPO_ROOT}/third_party/PhantomDns"

[[ -f "${PIN_FILE}" ]] || { echo "Missing ${PIN_FILE}" >&2; exit 1; }

repository="$(grep '^repository=' "${PIN_FILE}" | cut -d= -f2-)"
commit="$(grep '^commit=' "${PIN_FILE}" | cut -d= -f2-)"
[[ -n "${repository}" && -n "${commit}" ]] || { echo "Bad pin file" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

echo "Cloning ${repository}@${commit} ..."
git clone --quiet "${repository}" "${tmp}/src"
git -C "${tmp}/src" checkout --quiet "${commit}"
rm -rf "${tmp}/src/.git"

rm -rf "${DEST}"
mkdir -p "${DEST}"
cp -a "${tmp}/src/." "${DEST}/"
echo "Vendored PhantomDNS into ${DEST} at ${commit}"
echo "Next: run 'make PhantomDns' with the Android NDK to build the client binaries."
