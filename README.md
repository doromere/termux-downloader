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

Установщик ставит `ffmpeg`, `aria2`, `git`, `yt-dlp`, `gallery-dl`, `gdown`, копирует скрипты в `~/bin`
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
