# termux-downloader

Универсальный загрузчик для Android через **Termux**: делитесь ссылкой из любого приложения
(«Поделиться → Termux») — видео, музыка, фото и файлы скачиваются в отдельную папку для каждого сервиса.

```
Внутренняя память/Download/Termux Downloads/
├── Spotify/  YouTube/  YouTube Music/  Twitter/  Instagram/  TikTok/  VK/ …
├── Files/          ← прямые ссылки на файлы (.pdf, .apk, .zip, .mp4…)
├── Shared Files/   ← файлы, которыми поделились из других приложений
└── Other/          ← любые другие сайты
```

Журнал загрузок хранится приватно в `~/.config/termux-downloads/history.log`
(просмотр: `termux-url-opener --history`).

## Установка

Termux нужен из **F-Droid** или **GitHub** (версия из Google Play устарела).

```bash
pkg install -y git
git clone https://github.com/doromere/termux-downloader
bash termux-downloader/install.sh
```

Установщик ставит `ffmpeg`, `aria2`, `git`, `nodejs-lts`, `yt-dlp`, `gallery-dl`, `gdown`, копирует скрипты
(`termux-url-opener`, `termux-file-editor`, `playlist-dump`, `termux-dl`) в `~/bin`
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
| Музыка с DRM | Deezer (треки, альбомы, плейлисты), Apple Music (треки, альбомы) | DRM не взламывается: список треков берётся из открытых API Deezer / iTunes, а сами треки качаются из Spotify (spotDL) или YouTube — через `playlist-dump link` |
| | Tidal, Amazon Music (и Yandex Music, если yt-dlp не справился) | открытого API нет: открывается страница song.link — выберите там Spotify / YouTube Music и поделитесь той ссылкой в Termux |
| Фото + видео | Twitter/X, Instagram, Threads, Pinterest, Bluesky, Tumblr, Imgur, Pixiv, DeviantArt, ArtStation | gallery-dl → yt-dlp |
| Соцсети | TikTok, Reddit, Telegram (публичные посты) | yt-dlp → gallery-dl |
| Файлы | Google Drive, GitHub, magnet / .torrent, прямые ссылки | gdown, git, aria2c, curl |
| Остальное | любые сайты | yt-dlp → gallery-dl → curl |

Ссылки без `https://` («youtube.com/watch?v=…») тоже распознаются — если в тексте нет ссылок со схемой
и после домена есть путь. Короткие ссылки (`t.co`, `vk.cc`, `spotify.link`, `pin.it`, `reddit.com/…/s/…`) раскрываются автоматически.

**Подтверждения.** Торренты, прямые файлы, GitHub, «прочие сайты», а также каналы / плейлисты YouTube
и страницы исполнителей Spotify скачиваются только после подтверждения (Enter). Если ничего не нажать
за 30 с — загрузка отменяется. Для запуска без вопросов: `TUO_YES=1 termux-url-opener "ссылка"`.

## Плейлисты из дампов CSV / TXT (`playlist-dump`)

Скачивание по выгрузкам из **TuneMyMusic**, **Exportify**, Soundiiz или по своему списку
«Исполнитель - Название» (по одному треку в строке). Так можно скачать даже «Любимые треки»
Spotify без Premium и без API-ключа: выгрузите их в CSV/TXT через tunemymusic.com.

```bash
playlist-dump                          # выбрать файл из папки Download
playlist-dump "~/storage/shared/Download/Liked Songs.csv"   # «~» в кавычках тоже работает
playlist-dump файл.csv --numbered      # нумеровать треки по порядку
playlist-dump файл.csv --check         # что распознано и сколько уже скачано
playlist-dump файл.csv --format m4a    # без перекодирования в MP3 — в разы быстрее
playlist-dump файл.csv --no-lyrics     # не искать тексты песен — ещё быстрее
playlist-dump файл.csv --threads 2     # меньше параллельных загрузок (если сыплются ошибки SSL)
```

Или в файловом менеджере: файл → «Поделиться» → **Termux** → **Edit**.

Как работает:
1. **Сначала — сверка дампа с папкой**, без обращения к сети: уже скачанные треки (в любом
   формате, с нумерацией и без) сразу исключаются. Если всё на месте — скрипт просто обновит `.m3u8`.
2. Только для недостающих: треки без Spotify ID ищутся в Spotify (проверка названия и исполнителя),
   для треков с ID один раз запрашиваются метаданные. Всё кэшируется в `.playlist-dump.json`.
3. Найденные скачиваются spotDL одним пакетом, по 4 трека одновременно. Метаданные передаются
   spotDL готовыми, поэтому он не делает свои 3 запроса к Spotify на каждый трек.
4. Не найденные в Spotify — с YouTube, тоже параллельно; список неудач — в `.not-found.txt`.
5. Плейлист `<имя>.m3u8` собирается в порядке дампа из всех скачанных треков.

Результат: `Download/Termux Downloads/Spotify/<имя плейлиста>/`. Прерванную загрузку достаточно
запустить снова — скачанное будет пропущено за секунды. Код возврата: `0` — всё скачано,
`1` — были ошибки (часть треков не найдена, нет доступа к папке…), `2` — отменено.
Одну и ту же папку нельзя качать из двух окон Termux одновременно — второе окно сообщит об этом.

**Ссылки Deezer / Apple Music** (то же самое, что делает «Поделиться → Termux»):

```bash
playlist-dump link https://www.deezer.com/album/302127     # → Termux Downloads/Deezer/<альбом>/
playlist-dump link "https://music.apple.com/…/album/…?i=…"   # один трек → Termux Downloads/Apple Music/
```

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

Cookies — это доступ к вашим аккаунтам: держите их только в этой папке (установщик закрывает её
правами `700`), не кладите в общую память телефона и периодически перевыпускайте (выход/вход в аккаунт).

## Обслуживание и настройки

```bash
termux-url-opener --update   # обновить yt-dlp, gallery-dl, gdown
termux-url-opener --doctor   # проверить память, зависимости и права
termux-url-opener --config   # показать настройки (~/.config/termux-downloads/config.json)
termux-url-opener --config set video_quality 1080   # ask | best | 1080 | 720 | mp3
termux-url-opener --config set audio_format m4a     # mp3 | m4a | opus
termux-url-opener --config set ui auto              # auto | cli | gui (Termux:GUI)
termux-url-opener --help
```

Уведомления о завершении и прогрессе: установите приложение **Termux:API** и `pkg install termux-api`.
Опциональный графический выбор качества/подтверждения: установите **Termux:GUI** (F-Droid) и `pip install termuxgui` (иначе автоматически используется меню в терминале).

## «Some YouTube downloads require Deno»

YouTube требует JavaScript-движок. Deno (и `spotdl --download-deno`) под Android не работает,
поэтому используется **Node.js**: установщик ставит `nodejs-lts`, скрипты передают
`--js-runtimes node` в yt-dlp и spotDL, а в `~/.config/yt-dlp/config` флаг прописан для ручного запуска.
При ручном запуске spotDL добавляйте `--yt-dlp-args "--js-runtimes node"`.

## Ограничения

- Видеостриминги с DRM (Netflix, Disney+, Prime Video, Max, Apple TV, Кинопоиск, ivi, Okko, Wink и т.п.)
  не скачиваются — обход DRM незаконен. Используйте офлайн-загрузку в официальных приложениях.
- Публичный API song.link (Odesli) закрыт 31.07.2026, поэтому Tidal и Amazon Music автоматически не
  конвертируются (открывается страница song.link). Плейлисты Apple Music / Tidal / Amazon без входа в аккаунт
  не получить: перенесите плейлист в Spotify (tunemymusic.com, soundiiz.com) и поделитесь ссылкой Spotify
  или выгрузите его в CSV и поделитесь файлом. Плейлисты Deezer (публичные) работают напрямую.
- Не поддерживаются подкасты Spotify, музыка VK, закрытые чаты Telegram.
- «Любимые треки» Spotify по ссылке не скачать (нужен вход через API, только Premium) — выгрузите их
  в CSV на tunemymusic.com и поделитесь файлом (см. `playlist-dump`).
- Запасной поиск на YouTube (`ytsearch`) может найти не ту версию трека (кавер, live, ремикс):
  берётся первый результат не длиннее 15 минут. Результат стоит проверить.
- Ссылка, присланная в Termux, может прийти из любого приложения: поэтому принимаются только
  `http(s)://`, `magnet:` и «голые» домены (им дописывается `https://`); всё, что похоже на опцию
  (`--exec=…`), отклоняется, а «массовые» загрузки требуют подтверждения.

## Android-приложение (без Termux)

В каталоге [android/](android/) находится нативное Android-приложение на **Kotlin + Jetpack Compose + `youtubedl-android`** (`yt-dlp` + `ffmpeg` + `aria2c`):
- приём ссылок и `.csv`/`.txt`-плейлистов через системное меню «Поделиться» или кнопку `CSV/TXT`;
- фоновый `ForegroundService` с прогрессом и отменой из уведомления;
- сохранение в `Download/Termux Downloads/` через `MediaStore` и кнопка открытия файла;
- split-APK по архитектурам (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` + `universal`).

Сборка Debug APK запускается автоматически в [android.yml](.github/workflows/android.yml), а релизные APK по тегу `v*` публикуются в **GitHub Releases** ([release-android.yml](.github/workflows/release-android.yml)) — обновления удобно отслеживать через **Obtainium**.
