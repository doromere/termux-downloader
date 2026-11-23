#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "============================================================"
echo "  Установка VK Video Download для Termux"
echo "============================================================"

if [ -n "${TERMUX_VERSION:-}" ] || [[ "${PREFIX:-}" == *"/com.termux/"* ]] || [ -d "/data/data/com.termux" ]; then
    echo "[1/4] Обновление пакетов и установка Python + FFmpeg..."
    pkg update -y
    pkg install -y python ffmpeg

    echo "[2/4] Проверка доступа к памяти телефона..."
    if [ ! -d "$HOME/storage/downloads" ]; then
        echo "Сейчас Android запросит разрешение на доступ к файлам — нажмите «Разрешить»."
        termux-setup-storage || true
    else
        echo "Доступ к ~/storage/downloads уже настроен."
    fi

    echo "[3/4] Установка Python-зависимостей (yt-dlp, requests)..."
    python -m pip install -U -r "$REPO_DIR/requirements.txt"

    echo "[4/4] Настройка быстрой команды vkdl и меню «Поделиться -> Termux»..."
    if [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ]; then
        cat > "$PREFIX/bin/vkdl" <<EOF
#!/usr/bin/env bash
exec python "$REPO_DIR/vk_video_download.py" "\$@"
EOF
        chmod +x "$PREFIX/bin/vkdl"
    fi

    mkdir -p "$HOME/bin"
    OPENER="$HOME/bin/termux-url-opener"
    if [ -f "$OPENER" ] && ! grep -q "vk_video_download.py" "$OPENER"; then
        cp "$OPENER" "${OPENER}.bak"
        echo "Существующий termux-url-opener сохранён в ${OPENER}.bak"
    fi

    cat > "$OPENER" <<EOF
#!/usr/bin/env bash
python "$REPO_DIR/vk_video_download.py" --cli "\$@"
echo ""
read -r -p "Нажмите Enter для закрытия..." _ || true
EOF
    chmod +x "$OPENER"

    echo ""
    echo "Готово! Теперь вы можете скачивать видео тремя способами:"
    echo "  1) Ввести команду: vkdl"
    echo "  2) Сразу со ссылкой: vkdl \"https://vk.com/video-...\""
    echo "  3) В приложении VK / Rutube / YouTube нажать «Поделиться» -> «Termux»"
    echo ""
    echo "Видео будут сохраняться в папку «Загрузки» телефона (~/storage/downloads)."
else
    echo "Среда Termux не обнаружена — устанавливаем только Python-зависимости..."
    python3 -m pip install -U -r "$REPO_DIR/requirements.txt"
    echo "Готово! Запуск: python3 \"$REPO_DIR/vk_video_download.py\""
fi
