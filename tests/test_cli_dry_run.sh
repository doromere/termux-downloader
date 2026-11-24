#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

export HOME="$TMP/home"
mkdir -p "$HOME"

OPENER="$ROOT/bin/termux-url-opener"
EDITOR="$ROOT/bin/termux-file-editor"

assert_contains() {
  local haystack="$1" needle="$2" msg="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    echo "FAIL: $msg (expected '$needle')" >&2
    echo "--- output ---" >&2
    printf '%s\n' "$haystack" >&2
    exit 1
  fi
}

echo "==> termux-url-opener: --help and invalid input"
out="$("$OPENER" --help)"
assert_contains "$out" "termux-url-opener" "--help prints usage"

if TUO_DRY_RUN=1 "$OPENER" "--exec=id" >/dev/null 2>&1; then
  echo "FAIL: option-like input --exec=id must be rejected" >&2
  exit 1
fi

echo "==> termux-url-opener: dry-run routing"
out="$(TUO_DRY_RUN=1 TUO_CHOICE=1 "$OPENER" "https://youtu.be/dQw4w9WgXcQ")"
assert_contains "$out" "━━━ YouTube ━━━" "YouTube detected"
assert_contains "$out" "[dry-run] yt-dlp" "yt-dlp invoked"

out="$(TUO_DRY_RUN=1 "$OPENER" "youtu.be/dQw4w9WgXcQ")"
assert_contains "$out" "https://youtu.be/dQw4w9WgXcQ" "bare domain normalized"

out="$(TUO_DRY_RUN=1 "$OPENER" "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")"
assert_contains "$out" "━━━ Spotify ━━━" "Spotify detected"
assert_contains "$out" "spotdl download" "spotdl invoked"

out="$(TUO_DRY_RUN=1 "$OPENER" "https://www.deezer.com/album/302127")"
assert_contains "$out" "━━━ Deezer ━━━" "Deezer detected"
assert_contains "$out" "playlist-dump link" "playlist-dump link invoked"

out="$(TUO_DRY_RUN=1 "$OPENER" "https://example.com/archive.zip")"
assert_contains "$out" "━━━ Files ━━━" "direct file detected"
assert_contains "$out" "[dry-run] curl" "curl invoked"
assert_contains "$out" " -- https://example.com/archive.zip" "curl receives -- separator"

out="$(TUO_DRY_RUN=1 "$OPENER" "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")"
assert_contains "$out" "━━━ Torrents ━━━" "magnet detected"
assert_contains "$out" "aria2c" "aria2c invoked"

echo "==> termux-file-editor: routing tests"
FAKE_HOME="$TMP/editor_home"
mkdir -p "$FAKE_HOME/bin" "$FAKE_HOME/shared"
cat > "$FAKE_HOME/bin/termux-url-opener" <<'EOF'
#!/usr/bin/env bash
printf 'OPENER:%s\n' "$*"
EOF
cat > "$FAKE_HOME/bin/playlist-dump" <<'EOF'
#!/usr/bin/env bash
printf 'PLD:%s\n' "$*"
EOF
chmod +x "$FAKE_HOME/bin/termux-url-opener" "$FAKE_HOME/bin/playlist-dump"

# 1. Plain 3-line note (no URLs, no "Artist - Title") -> Shared Files (must NOT go to playlist-dump)
f_note="$TMP/note.txt"
printf 'Купить молоко\nПозвонить другу\nСходить в зал\n' > "$f_note"
out="$(HOME="$FAKE_HOME" TUO_ROOT="$FAKE_HOME/shared" "$EDITOR" "$f_note" </dev/null)"
assert_contains "$out" "Shared Files/" "plain 3-line text saved to Shared Files"

# 2. 3+ bare-domain links -> OPENER (must NOT be hijacked by playlist-dump)
f_bare="$TMP/bare.txt"
printf 'youtube.com/watch?v=1\nyoutube.com/watch?v=2\nyoutube.com/watch?v=3\n' > "$f_bare"
out="$(HOME="$FAKE_HOME" TUO_ROOT="$FAKE_HOME/shared" "$EDITOR" "$f_bare" </dev/null)"
assert_contains "$out" "OPENER:youtube.com/watch?v=1" "bare domains routed to termux-url-opener"

# 3. 3+ track lines -> PLD
f_tracks="$TMP/tracks.txt"
printf 'Artist One - Song A\nArtist Two - Song B\nArtist Three - Song C\n' > "$f_tracks"
out="$(HOME="$FAKE_HOME" TUO_ROOT="$FAKE_HOME/shared" "$EDITOR" "$f_tracks" </dev/null)"
assert_contains "$out" "PLD:--keep-dump" "track list routed to playlist-dump"

# 4. 3+ Spotify track links -> PLD
f_sp="$TMP/spotify.txt"
printf 'https://open.spotify.com/track/1111111111111111111111\nhttps://open.spotify.com/track/2222222222222222222222\nhttps://open.spotify.com/track/3333333333333333333333\n' > "$f_sp"
out="$(HOME="$FAKE_HOME" TUO_ROOT="$FAKE_HOME/shared" "$EDITOR" "$f_sp" </dev/null)"
assert_contains "$out" "PLD:--keep-dump" "Spotify track list routed to playlist-dump"

echo "✔ All CLI dry-run & routing tests passed"
