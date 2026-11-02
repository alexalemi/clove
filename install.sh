#!/bin/sh
# install.sh — build and install clove.
#
# One line, no checkout needed (downloads the latest tagged source):
#
#   curl -fsSL https://raw.githubusercontent.com/alexalemi/clove/main/install.sh | sh
#   curl -fsSL .../install.sh | sh -s -- --version v0.9.0 --prefix /usr/local
#
# From a source checkout (builds that checkout):
#
#   ./install.sh                     # install to ~/.local (no sudo)
#   PREFIX=/usr/local ./install.sh   # system-wide (may need sudo)
#
# Options (or the matching environment variables):
#   --prefix DIR     PREFIX         install root (default ~/.local)
#   --version V      CLOVE_VERSION  tag to install, e.g. v0.9.0, or "main"
#                                   (default: the newest vX.Y.Z tag)
#   --test           CLOVE_TEST=1   run the test suite before installing
#   --no-test        CLOVE_TEST=0   skip it (default when downloading)
#
# Requires: cc (gcc or clang) and make; downloading also needs tar and curl
# or wget. clove is one C file, so the build takes seconds.
set -e

REPO=alexalemi/clove
PREFIX="${PREFIX:-$HOME/.local}"
VERSION="${CLOVE_VERSION:-}"
TEST="${CLOVE_TEST:-}"

while [ $# -gt 0 ]; do
    case "$1" in
        --prefix)  PREFIX="$2"; shift 2 ;;
        --prefix=*) PREFIX="${1#--prefix=}"; shift ;;
        --version) VERSION="$2"; shift 2 ;;
        --version=*) VERSION="${1#--version=}"; shift ;;
        --test)    TEST=1; shift ;;
        --no-test) TEST=0; shift ;;
        -h|--help) sed -n '2,23p' "$0" 2>/dev/null || echo "see https://github.com/$REPO"; exit 0 ;;
        *) echo "install.sh: unknown option: $1" >&2; exit 2 ;;
    esac
done

die() { echo "error: $*" >&2; exit 1; }

command -v cc >/dev/null 2>&1 || die "no C compiler found (install gcc or clang; on macOS: xcode-select --install)"
command -v make >/dev/null 2>&1 || die "make not found"

fetch() {  # fetch URL [OUTFILE]; stdout when no OUTFILE
    if command -v curl >/dev/null 2>&1; then
        if [ -n "$2" ]; then curl -fsSL -o "$2" "$1"; else curl -fsSL "$1"; fi
    elif command -v wget >/dev/null 2>&1; then
        if [ -n "$2" ]; then wget -qO "$2" "$1"; else wget -qO- "$1"; fi
    else
        die "need curl or wget to download clove"
    fi
}

# Newest vX.Y.Z tag. git needs no API token and has no rate limit; the GitHub
# API is the fallback. Sorted numerically per field: lexical order would put
# v0.10.0 before v0.9.0.
latest_tag() {
    { if command -v git >/dev/null 2>&1; then
          git ls-remote --tags --refs "https://github.com/$REPO.git" 2>/dev/null | sed 's#.*refs/tags/##'
      fi
      fetch "https://api.github.com/repos/$REPO/tags?per_page=100" 2>/dev/null |
          sed -n 's/.*"name": *"\([^"]*\)".*/\1/p'
    } | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sed 's/^v//' |
        sort -u -t. -k1,1n -k2,2n -k3,3n | tail -1 | sed 's/^/v/'
}

# A checkout is this script sitting next to clove.c. Piped into sh, $0 is
# the shell's name, so that case always downloads.
SRC=""
if [ -f "$0" ] && [ -f "$(dirname "$0")/clove.c" ]; then
    SRC="$(cd "$(dirname "$0")" && pwd)"
    [ -n "$TEST" ] || TEST=1
else
    command -v tar >/dev/null 2>&1 || die "tar not found"
    if [ -z "$VERSION" ]; then
        VERSION="$(latest_tag)"
        [ -n "$VERSION" ] || die "could not look up the latest release tag; pass --version (e.g. --version main)"
    fi
    case "$VERSION" in
        main|master) URL="https://github.com/$REPO/archive/refs/heads/$VERSION.tar.gz" ;;
        *)           URL="https://github.com/$REPO/archive/refs/tags/$VERSION.tar.gz" ;;
    esac
    TMP="$(mktemp -d 2>/dev/null || mktemp -d -t clove)"
    trap 'rm -rf "$TMP"' EXIT INT TERM
    echo "downloading clove $VERSION..."
    fetch "$URL" "$TMP/clove.tar.gz" || die "download failed: $URL"
    tar -xzf "$TMP/clove.tar.gz" -C "$TMP"
    SRC="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -1)"
    [ -f "$SRC/clove.c" ] || die "downloaded archive has no clove.c"
    [ -n "$TEST" ] || TEST=0
fi

cd "$SRC"
echo "building clove (PREFIX=$PREFIX)..."
make clean >/dev/null 2>&1 || true
make PREFIX="$PREFIX" >/dev/null

if [ "$TEST" = 1 ]; then
    echo "running test suite..."
    make test
fi

if ! out="$(make PREFIX="$PREFIX" install 2>&1)"; then
    echo "$out" >&2
    die "install into $PREFIX failed; if it is not writable, rerun with sudo or pick a writable --prefix (default ~/.local)"
fi

echo
echo "clove installed."
echo "  binary:    $PREFIX/bin/clove"
echo "  batteries: $PREFIX/share/clove  (load-file/require find them anywhere)"
case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) echo "  NOTE: $PREFIX/bin is not on your PATH; add this to your shell profile:"
       echo "        export PATH=\"$PREFIX/bin:\$PATH\"" ;;
esac
"$PREFIX/bin/clove" --version
