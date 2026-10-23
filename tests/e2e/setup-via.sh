#!/usr/bin/env bash
# Downloads ViaVersion + ViaBackwards (Paper builds) into a plugins directory.
#
# Why: mineflayer (the e2e bot) speaks at most Minecraft 26.1 while the server runs Paper 26.3.
# ViaVersion lets newer clients join older servers; ViaBackwards lets OLDER clients (26.1 bot)
# join a NEWER server (26.3). Both are needed on the server.
#
# Usage: tests/e2e/setup-via.sh <pluginsDir> [gameVersion]
#   gameVersion defaults to 26.3 (the Paper version of the server).
#   Env: VIA_OFFLINE=1  -> skip the Modrinth API and use the pinned fallback URLs below.
#
# The newest Paper build for <gameVersion> is resolved through the Modrinth API
# (https://api.modrinth.com/v2/project/<slug>/version?loaders=["paper"]&game_versions=["26.3"]).
# If the API is unreachable, pinned fallback URLs are used. Files are verified with the SHA-1
# published by Modrinth when available. Older ViaVersion*/ViaBackwards* jars in the target
# directory are renamed to *.jar.old (Paper refuses duplicate plugins).
set -euo pipefail

PLUGINS_DIR="${1:-}"
GAME_VERSION="${2:-26.3}"
if [ -z "$PLUGINS_DIR" ]; then
  echo "usage: $0 <pluginsDir> [gameVersion]" >&2
  exit 2
fi
mkdir -p "$PLUGINS_DIR"

# Pinned fallbacks (latest Paper/26.3 builds at the time of writing).
FALLBACK_viaversion_URL="https://cdn.modrinth.com/data/P1OZGk5p/versions/TEgYlalY/ViaVersion-5.12.1-SNAPSHOT.jar"
FALLBACK_viaversion_SHA1="c2ad43710d4925f2d0db45c5a29782e76eac772d"
FALLBACK_viabackwards_URL="https://cdn.modrinth.com/data/NpvuJQoq/versions/w6P38zDf/ViaBackwards-5.12.1-SNAPSHOT.jar"
FALLBACK_viabackwards_SHA1=""

UA="vibecraft-e2e/0.1 (setup-via.sh)"

sha1_of() {
  if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 1 "$1" | cut -d' ' -f1
  else node -e "const c=require('crypto'),f=require('fs');process.stdout.write(c.createHash('sha1').update(f.readFileSync(process.argv[1])).digest('hex'))" "$1"
  fi
}

# Prints "url<TAB>sha1<TAB>versionNumber" of the newest Paper build for $GAME_VERSION, or nothing.
resolve_latest() {
  local slug="$1"
  [ "${VIA_OFFLINE:-0}" = "1" ] && return 0
  local api="https://api.modrinth.com/v2/project/${slug}/version?loaders=%5B%22paper%22%5D&game_versions=%5B%22${GAME_VERSION}%22%5D"
  local json
  json="$(curl -fsSL --max-time 20 -A "$UA" "$api" 2>/dev/null)" || return 0
  printf '%s' "$json" | node -e '
    let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
      try {
        const versions = JSON.parse(s);
        if (!Array.isArray(versions) || !versions.length) return;
        versions.sort((a, b) => Date.parse(b.date_published) - Date.parse(a.date_published));
        const v = versions[0];
        const f = v.files.find(x => x.primary) || v.files[0];
        process.stdout.write([f.url, (f.hashes && f.hashes.sha1) || "", v.version_number].join("\t"));
      } catch (e) { /* fall back */ }
    });'
}

install_one() {
  local slug="$1" prefix="$2"
  local url sha1 ver resolved
  resolved="$(resolve_latest "$slug")"
  if [ -n "$resolved" ]; then
    IFS=$'\t' read -r url sha1 ver <<<"$resolved"
    echo "[$prefix] newest Paper/$GAME_VERSION build on Modrinth: $ver"
  else
    local u="FALLBACK_${slug}_URL" h="FALLBACK_${slug}_SHA1"
    url="${!u}"; sha1="${!h}"; ver="(pinned fallback)"
    echo "[$prefix] Modrinth API unavailable, using pinned fallback"
  fi
  local name dest
  name="$(basename "${url%%\?*}")"
  dest="$PLUGINS_DIR/$name"

  if [ -f "$dest" ] && [ -n "$sha1" ] && [ "$(sha1_of "$dest")" = "$sha1" ]; then
    echo "[$prefix] already up to date: $dest"
    return 0
  fi

  local tmp="$dest.part"
  echo "[$prefix] downloading $url"
  curl -fSL --retry 3 --max-time 180 -A "$UA" -o "$tmp" "$url"
  if [ -n "$sha1" ]; then
    local got; got="$(sha1_of "$tmp")"
    if [ "$got" != "$sha1" ]; then
      rm -f "$tmp"
      echo "[$prefix] SHA-1 mismatch (expected $sha1, got $got)" >&2
      return 1
    fi
    echo "[$prefix] SHA-1 verified"
  fi
  # Retire other builds of the same plugin (duplicate plugin names make Paper skip one of them).
  for old in "$PLUGINS_DIR"/"$prefix"*.jar; do
    [ -e "$old" ] || continue
    [ "$old" = "$dest" ] && continue
    echo "[$prefix] retiring older jar: $old -> $old.old"
    mv -f "$old" "$old.old"
  done
  mv -f "$tmp" "$dest"
  echo "[$prefix] installed $dest"
}

install_one viaversion ViaVersion
install_one viabackwards ViaBackwards
echo "Done. Restart the server to load ViaVersion + ViaBackwards (needed for the mineflayer 26.1 bot)."
