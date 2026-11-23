#!/data/data/com.termux/files/usr/bin/bash
# Установщик termux-downloader
#   git clone https://github.com/doromere/termux-downloader && bash termux-downloader/install.sh
# или (если репозиторий публичный):
#   curl -L https://raw.githubusercontent.com/doromere/termux-downloader/main/install.sh | bash
set -e

RAW="https://raw.githubusercontent.com/doromere/termux-downloader/main"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd)"

echo "==> Доступ к памяти телефона"
[ -d "$HOME/storage/shared" ] || { termux-setup-storage; sleep 4; }

echo "==> Пакеты Termux"
pkg install -y ffmpeg curl git aria2
# YouTube требует JS-движок; Deno под Android не работает — ставим Node.js
command -v node >/dev/null 2>&1 || pkg install -y nodejs-lts

if command -v python3.13 >/dev/null 2>&1; then PY=python3.13
else pkg install -y python; PY=python
fi

echo "==> yt-dlp, gallery-dl, gdown ($PY)"
"$PY" -m pip install -U "yt-dlp[default]" gallery-dl gdown

echo "==> yt-dlp и spotDL: использовать Node.js вместо Deno"
mkdir -p "$HOME/.config/yt-dlp"
grep -qs -- '--js-runtimes' "$HOME/.config/yt-dlp/config" || echo '--js-runtimes node' >> "$HOME/.config/yt-dlp/config"
if command -v spotdl >/dev/null 2>&1; then
  [ -f "$HOME/.spotdl/config.json" ] || spotdl --generate-config >/dev/null 2>&1 || true
  "$PY" - <<'PYEOF' || true
import json, pathlib
p = pathlib.Path.home() / ".spotdl" / "config.json"
if p.exists():
    c = json.loads(p.read_text())
    args = c.get("yt_dlp_args") or ""
    if "--js-runtimes" not in args:
        c["yt_dlp_args"] = (args + " --js-runtimes node").strip()
        p.write_text(json.dumps(c, indent=4, ensure_ascii=False))
        print("   spotDL: yt_dlp_args =", c["yt_dlp_args"])
PYEOF
fi

echo "==> Скрипты в ~/bin"
mkdir -p "$HOME/bin" "$HOME/.config/termux-downloads/cookies"
for f in termux-url-opener termux-file-editor playlist-dump; do
  if [ -f "$SRC/bin/$f" ]; then cp "$SRC/bin/$f" "$HOME/bin/$f"
  else curl -fsSL "$RAW/bin/$f" -o "$HOME/bin/$f"
  fi
  chmod +x "$HOME/bin/$f"
done

grep -q 'HOME/bin' "$HOME/.bashrc" 2>/dev/null || echo 'export PATH="$HOME/bin:$PATH"' >> "$HOME/.bashrc"

echo
echo "✔ Готово. Перезапустите Termux (или выполните: source ~/.bashrc)."
echo "  Делитесь ссылками через «Поделиться → Termux»."
echo "  Файлы: Download/Termux Downloads/<Сервис>/"
command -v spotdl >/dev/null 2>&1 || echo "  Для Spotify отдельно установите spotDL: см. README."
