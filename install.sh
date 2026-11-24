#!/data/data/com.termux/files/usr/bin/bash
# Установщик termux-downloader
#   git clone https://github.com/doromere/termux-downloader && bash termux-downloader/install.sh
# или (если репозиторий публичный):
#   curl -L https://raw.githubusercontent.com/doromere/termux-downloader/main/install.sh | bash
set -e

RAW="https://raw.githubusercontent.com/doromere/termux-downloader/main"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd)"

echo "==> Доступ к памяти телефона"
if [ ! -d "$HOME/storage/shared" ]; then
  termux-setup-storage
  for _ in $(seq 1 30); do [ -d "$HOME/storage/shared" ] && break; sleep 1; done
  [ -d "$HOME/storage/shared" ] || echo "   ! Нет доступа к памяти — разрешите его и запустите install.sh ещё раз."
fi

echo "==> Пакеты Termux"
pkg install -y ffmpeg curl git aria2
# YouTube требует JS-движок; Deno под Android не работает — ставим Node.js
command -v node >/dev/null 2>&1 || pkg install -y nodejs-lts

if command -v python3.13 >/dev/null 2>&1; then PY=python3.13
else pkg install -y python; PY=python
fi

echo "==> yt-dlp, gallery-dl, gdown ($PY)"
"$PY" -m pip install -U "yt-dlp[default]" gallery-dl gdown

# Скрипты сами передают «--js-runtimes node» в yt-dlp и spotDL. Строка в конфиге yt-dlp
# нужна только для ручного запуска yt-dlp из консоли. (~/.spotdl/config.json больше не
# трогаем: spotDL читает его лишь с --config / load_config, так что правка ничего не давала.)
if ! grep -qs -- '--js-runtimes' "$HOME/.config/yt-dlp/config"; then
  mkdir -p "$HOME/.config/yt-dlp"
  echo '--js-runtimes node' >> "$HOME/.config/yt-dlp/config"
  echo "   добавлено в ~/.config/yt-dlp/config: --js-runtimes node"
fi

echo "==> Скрипты в ~/bin"
CONF="$HOME/.config/termux-downloads"
mkdir -p "$HOME/bin" "$CONF/cookies"
chmod 700 "$CONF" "$CONF/cookies"           # cookies = доступ к аккаунтам
chmod 600 "$CONF"/cookies/* 2>/dev/null || true
for f in termux-url-opener termux-file-editor playlist-dump; do
  if [ -f "$SRC/bin/$f" ]; then
    cp "$SRC/bin/$f" "$HOME/bin/$f.new"
  else
    # установка через curl | bash: проверяем, что пришёл скрипт, а не HTML-страница ошибки
    curl -fsSL "$RAW/bin/$f" -o "$HOME/bin/$f.new" || {
      echo "✘ Не удалось скачать $f. Если репозиторий приватный — используйте git clone (см. README)."
      rm -f "$HOME/bin/$f.new"; exit 1; }
    head -n 1 "$HOME/bin/$f.new" | grep -q '^#!' || {
      echo "✘ Вместо $f получено что-то другое — установка прервана."; rm -f "$HOME/bin/$f.new"; exit 1; }
  fi
  chmod 700 "$HOME/bin/$f.new"
  mv -f "$HOME/bin/$f.new" "$HOME/bin/$f"   # атомарная замена
done

# журнал раньше лежал в общей памяти (его могли читать другие приложения) — переносим
OLD_LOG="$HOME/storage/shared/Download/Termux Downloads/.history.log"
if [ -f "$OLD_LOG" ]; then
  # umask 077 — файл сразу создаётся с правами 600; ошибка переноса не прерывает установку (set -e)
  if (umask 077; cat "$OLD_LOG" >> "$CONF/history.log"); then
    rm -f "$OLD_LOG"
    echo "   журнал загрузок перенесён в ~/.config/termux-downloads/history.log"
  else
    echo "   ! не удалось перенести старый журнал: $OLD_LOG"
  fi
  chmod 600 "$CONF/history.log" 2>/dev/null || true
fi

# PATH для bash (оболочка Termux по умолчанию), а также zsh, если он настроен
for rc in "$HOME/.bashrc" "$HOME/.zshrc"; do
  [ "$rc" = "$HOME/.bashrc" ] || [ -f "$rc" ] || continue
  grep -qs 'HOME/bin' "$rc" || echo 'export PATH="$HOME/bin:$PATH"' >> "$rc"
done
if [ -d "$HOME/.config/fish" ] && ! grep -qs 'HOME/bin' "$HOME/.config/fish/config.fish"; then
  echo 'fish_add_path -g $HOME/bin' >> "$HOME/.config/fish/config.fish"
fi

echo
echo "✔ Готово. Перезапустите Termux (или выполните: source ~/.bashrc)."
echo "  Делитесь ссылками через «Поделиться → Termux»."
echo "  Файлы: Download/Termux Downloads/<Сервис>/"
command -v spotdl >/dev/null 2>&1 || echo "  Для Spotify отдельно установите spotDL: см. README."
