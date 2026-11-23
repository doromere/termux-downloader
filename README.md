# termux-downloader

Универсальный загрузчик для Android через **Termux**: делитесь ссылкой из любого приложения
(«Поделиться → Termux») — видео, музыка, фото и файлы скачиваются в отдельную папку для каждого сервиса.

```
Внутренняя память/Download/Termux Downloads/
├── Spotify/  YouTube/  YouTube Music/  Twitter/  Instagram/  TikTok/  VK/ …
├── Files/          ← прямые ссылки на файлы (.pdf, .apk, .zip, .mp4…)
├── Shared Files/   ← файлы, которыми поделились из других приложений
├── Other/          ← любые другие сайты
└── .history.log    ← журнал загрузок
```

## Установка

Termux нужен из **F-Droid** или **GitHub** (версия из Google Play устарела).

```bash
pkg install -y git
git clone https://github.com/doromere/termux-downloader
bash termux-downloader/install.sh
```

Установщик ставит `ffmpeg`, `aria2`, `git`, `yt-dlp`, `gallery-dl`, `gdown`, копирует скрипты
(`termux-url-opener`, `termux-file-editor`, `playlist-dump`) в `~/bin`
и добавляет `~/bin` в `PATH`.

**Spotify** (необязательно, занимает много места при сборке):

```bash
curl -L https://raw.githubusercontent.com/spotDL/spotify-downloader/master/scripts/termux.sh | sh
```

## Использование

- Ссылка → «Поделиться» → **Termux**.
- Текст со ссылкой внутри или файл → «Поделиться» → **Termux** → кнопка **Edit**.
- Из консоли: `termux-url-opener "ссылка"` (можно несколько ссылок в одном тексте).

| Тип | Сервисы | Как скачивается |
|---|---|---|
| Музыка | Spotify | spotDL, альбомы/плейлисты — в подпапки |
| Видео (меню качества) | YouTube, VK, OK, Rutube, Dzen, Facebook, Twitch, Vimeo, Dailymotion, Bilibili, Coub | yt-dlp: лучшее / 1080p / 720p / только MP3 |
| Аудио | YouTube Music, SoundCloud, Bandcamp, Mixcloud, Yandex Music | yt-dlp → MP3 с обложкой и тегами |
| Музыка с DRM | Apple Music, Deezer, Tidal, Amazon Music (и Yandex Music, если yt-dlp не справился) | DRM не взламывается: трек/альбом находится через song.link и качается из Spotify (spotDL) или YouTube Music |
| Фото + видео | Twitter/X, Instagram, Threads, Pinterest, Bluesky, Tumblr, Imgur, Pixiv, DeviantArt, ArtStation | gallery-dl → yt-dlp |
| Соцсети | TikTok, Reddit, Telegram (публичные посты) | yt-dlp → gallery-dl |
| Файлы | Google Drive, GitHub, magnet / .torrent, прямые ссылки | gdown, git, aria2c, curl |
| Остальное | любые сайты | yt-dlp → gallery-dl → curl |

Короткие ссылки (`t.co`, `vk.cc`, `spotify.link`, `pin.it`, `reddit.com/…/s/…`) раскрываются автоматически.

## Плейлисты из дампов CSV / TXT (`playlist-dump`)

Скачивание по выгрузкам из **TuneMyMusic**, **Exportify**, Soundiiz или по своему списку
«Исполнитель - Название» (по одному треку в строке). Так можно скачать даже «Любимые треки»
Spotify без Premium и без API-ключа: выгрузите их в CSV/TXT через tunemymusic.com.

```bash
playlist-dump                          # выбрать файл из папки Download
playlist-dump "~/storage/shared/Download/Liked Songs.csv"
playlist-dump файл.csv --numbered      # нумеровать треки по порядку
playlist-dump файл.csv --check         # только показать, что распознано
```

Или в файловом менеджере: файл → «Поделиться» → **Termux** → **Edit**.

Как работает:
1. Треки со Spotify ID (колонки `Spotify - id`, `Track URI`, ссылки `open.spotify.com/track/…`) идут сразу в spotDL.
2. Остальные ищутся в Spotify с проверкой совпадения названия и исполнителя.
3. Найденные скачиваются spotDL одним пакетом (ошибка одного трека не останавливает остальные).
4. Не найденные — с YouTube (MP3 с обложкой); их список сохраняется в `.not-found.txt`.
5. Создаётся плейлист `<имя>.m3u8` для музыкальных плееров.

Результат: `Download/Termux Downloads/Spotify/<имя плейлиста>/`. Повторный запуск докачивает
только недостающее (результаты поиска кэшируются в `.playlist-dump.json`).

**Выгрузка** публичного плейлиста/альбома Spotify в CSV (формат TuneMyMusic) и TXT:

```bash
playlist-dump export https://open.spotify.com/playlist/…    # → Termux Downloads/Playlists/
```

## Cookies (X, Instagram, 18+ и т.п.)

Файл cookies в формате Netscape (например, из расширения «Get cookies.txt LOCALLY»):

```
~/.config/termux-downloads/cookies/twitter.txt
~/.config/termux-downloads/cookies/instagram.txt
~/.config/termux-downloads/cookies/cookies.txt   ← общий для всех сервисов
```

Имя файла — название папки сервиса строчными буквами, пробелы → `_` (`youtube_music.txt`).

## Обслуживание

```bash
termux-url-opener --update   # обновить yt-dlp, gallery-dl, gdown
termux-url-opener --help
```

Уведомления о завершении: установите приложение **Termux:API** и `pkg install termux-api`.

## Ограничения

- Видеостриминги с DRM (Netflix, Disney+, Prime Video, Max, Apple TV, Кинопоиск, ivi, Okko, Wink и т.п.)
  не скачиваются — обход DRM незаконен. Используйте офлайн-загрузку в официальных приложениях.
- Плейлисты Apple Music / Deezer / Tidal song.link не поддерживает: перенесите плейлист в Spotify
  (tunemymusic.com, soundiiz.com) и поделитесь ссылкой Spotify. Лимит song.link — 10 запросов в минуту.
- Не поддерживаются подкасты Spotify, музыка VK, закрытые чаты Telegram.
