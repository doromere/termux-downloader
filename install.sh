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

if command -v python3.13 >/dev/null 2>&1; then PY=python3.13
else pkg install -y python; PY=python
fi

echo "==> yt-dlp, gallery-dl, gdown ($PY)"
"$PY" -m pip install -U yt-dlp gallery-dl gdown

echo "==> Скрипты в ~/bin"
mkdir -p "$HOME/bin" "$HOME/.config/termux-downloads/cookies"
for f in termux-url-opener termux-file-editor; do
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
