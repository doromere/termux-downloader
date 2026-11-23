#!/usr/bin/env python3
import argparse
import dataclasses
import datetime
import logging
from logging.handlers import RotatingFileHandler
import os
import platform
import random
import re
import shutil
import string
import sys
import threading
import time
import webbrowser

try:
    import requests
except ImportError:
    requests = None

try:
    import tkinter as tk
    from tkinter import ttk, messagebox, Menu
    TK_AVAILABLE = True
except ImportError:
    tk = None
    ttk = None
    messagebox = None
    Menu = None
    TK_AVAILABLE = False

try:
    import yt_dlp
    YTDLP_AVAILABLE = True
except ImportError:
    yt_dlp = None
    YTDLP_AVAILABLE = False

# https://vk.com/video-87011294_456249654     | example for vk.com
# https://vkvideo.ru/video-50804569_456239864     | example for vkvideo.ru
# https://my.mail.ru/v/hi-tech_mail/video/_groupvideo/437.html     | example for my.mail.ru
# https://rutube.ru/video/a16f1e575e114049d0e4d04dc7322667/     | example for rutube.ru
# FromRussiaWithLove | Mons (https://github.com/blyamur/VK-Video-Download/) | "non-commercial use only, for personal use"

currentVersion = '2.1'

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
logger = logging.getLogger(__name__)


def setup_logging(cli_mode: bool = False) -> None:
    """Настраивает логирование в файл и (при необходимости) в консоль."""
    root_logger = logging.getLogger()
    if root_logger.handlers:
        return

    root_logger.setLevel(logging.INFO)
    formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')

    stream_handler = logging.StreamHandler()
    # В интерактивном CLI не засоряем терминал техническими INFO-логами
    stream_handler.setLevel(logging.ERROR if cli_mode else logging.INFO)
    stream_handler.setFormatter(formatter)
    root_logger.addHandler(stream_handler)

    log_candidates = [
        os.path.join(SCRIPT_DIR, 'vk_video_download.log'),
        'vk_video_download.log',
    ]
    for log_path in log_candidates:
        try:
            file_handler = RotatingFileHandler(
                log_path, maxBytes=10 * 1024 * 1024, backupCount=5, encoding='utf-8'
            )
            file_handler.setLevel(logging.INFO)
            file_handler.setFormatter(formatter)
            root_logger.addHandler(file_handler)
            break
        except OSError:
            continue


def is_termux() -> bool:
    """Проверяет, запущен ли скрипт в среде Android Termux."""
    return (
        bool(os.environ.get("TERMUX_VERSION"))
        or "/com.termux/" in os.environ.get("PREFIX", "")
        or os.path.exists("/data/data/com.termux")
    )


def get_default_output_dir(script_dir: str = SCRIPT_DIR) -> str:
    """Возвращает папку загрузки по умолчанию (в Termux — ~/storage/downloads, если доступна)."""
    if is_termux():
        termux_downloads = os.path.expanduser("~/storage/downloads")
        if os.path.isdir(termux_downloads):
            return termux_downloads
    return os.path.join(script_dir, "downloads")


def has_gui_display() -> bool:
    """Проверяет наличие tkinter и графического дисплея."""
    if not TK_AVAILABLE:
        return False
    system = platform.system()
    if system in ("Windows", "Darwin"):
        return True
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def sanitize_filename(name: str, max_len: int = 120) -> str:
    """Очищает имя файла от недопустимых символов для Windows/Linux/Android."""
    name = (name or "").strip()
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]+', '_', name)
    name = re.sub(r"\s+", " ", name).strip()
    name = name.rstrip(". ")
    if not name:
        name = "video"
    return name[:max_len]


def extract_urls(raw_text: str) -> list[str]:
    """
    Извлекает список уникальных ссылок из строки (через запятую, пробел, перенос строки
    или из текста «Поделиться» в мобильных приложениях Android).
    """
    if not raw_text:
        return []

    found = []
    # Сначала ищем явные http/https ссылки (удобно при отправке через «Поделиться» с заголовком)
    explicit_urls = re.findall(r'https?://[^\s,;"\'<>]+', raw_text)
    if explicit_urls:
        for u in explicit_urls:
            cleaned = u.rstrip('.,;!?)\\]')
            if cleaned:
                found.append(cleaned)
    else:
        for token in re.split(r'[,\s]+', raw_text.strip()):
            token = token.strip().rstrip('.,;!?)\\]')
            if not token:
                continue
            if re.match(r'^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/', token):
                token = 'https://' + token
            found.append(token)

    return list(dict.fromkeys(found))


def strip_ansi(text: str) -> str:
    return re.sub(r'\x1b\[[0-9;]*m', '', text or '')


def cleanup_temp_files(outtmpl: str, base_output_dir: str = "") -> None:
    """Удаляет временные файлы yt-dlp (.part, .ytdl, .tmp) после отмены загрузки."""
    try:
        if not outtmpl:
            return

        base = outtmpl.replace("%(ext)s", "*")
        folder = os.path.dirname(base)
        if not folder or not os.path.isdir(folder):
            return

        prefix = os.path.basename(base).replace("*", "")

        for fn in os.listdir(folder):
            full = os.path.join(folder, fn)
            if prefix and not fn.startswith(prefix):
                continue
            if fn.endswith((".part", ".ytdl", ".tmp")) or ".part" in fn:
                try:
                    os.remove(full)
                except OSError:
                    pass

        # Если создавалась отдельная подпапка для видео и она осталась пустой — удаляем её
        if base_output_dir:
            try:
                if (
                    os.path.abspath(folder) != os.path.abspath(base_output_dir)
                    and os.path.isdir(folder)
                    and not os.listdir(folder)
                ):
                    os.rmdir(folder)
            except OSError:
                pass

    except Exception as e:
        logger.warning(f"cleanup_temp_files error: {e}")


@dataclasses.dataclass
class DownloadOptions:
    output_dir: str
    random_name: bool = False
    limit_length: bool = False
    separate_folder: bool = False
    max_workers: int = 3


def download_single_video(
    video_url: str,
    serial_number: int,
    options: DownloadOptions,
    semaphore: threading.Semaphore,
    stop_event: threading.Event,
    on_title=None,
    on_outtmpl=None,
    on_progress=None,
    on_processing=None,
    on_done=None,
    on_cancelled=None,
    on_error=None,
) -> bool:
    """Общая логика скачивания одного видео для GUI и CLI режимов."""
    outtmpl = ""
    with semaphore:
        try:
            if stop_event.is_set():
                if on_cancelled:
                    on_cancelled()
                return False

            os.makedirs(options.output_dir, exist_ok=True)

            timestr = datetime.datetime.now().strftime('%d%m%Y_%H%M%S_%f')[:-4]
            random_suffix = ''.join(random.choices(string.ascii_letters + string.digits, k=4))

            title = "video"
            if not options.random_name:
                try:
                    with yt_dlp.YoutubeDL({'quiet': True, 'no_warnings': True}) as ydl:
                        info = ydl.extract_info(video_url, download=False) or {}
                        title = info.get('title') or "video"
                except Exception as e:
                    logger.warning(f"[{serial_number}] Не удалось получить метаданные: {e}")

            if stop_event.is_set():
                if on_cancelled:
                    on_cancelled()
                return False

            title = sanitize_filename(title, max_len=50 if options.limit_length else 120)

            if options.random_name:
                filename_base = f"{timestr}_{random_suffix}"
                display_name = filename_base
            else:
                filename_base = f"{title}_{timestr}_{random_suffix}"
                display_name = title

            display_name = sanitize_filename(display_name, max_len=80)
            if on_title:
                on_title(display_name)

            if options.separate_folder:
                outtmpl = os.path.join(options.output_dir, filename_base, 'video.%(ext)s')
            else:
                outtmpl = os.path.join(options.output_dir, f'{filename_base}.%(ext)s')

            if on_outtmpl:
                on_outtmpl(outtmpl)

            def progress_hook(d):
                if stop_event.is_set():
                    raise yt_dlp.utils.DownloadError("Cancelled by user")

                status = d.get('status')
                if status == 'downloading':
                    raw_percent = strip_ansi(d.get('_percent_str', '0%')).replace(',', '.').strip()
                    match = re.search(r'(\d+\.?\d*)\s*%', raw_percent)
                    val = float(match.group(1)) if match else 0.0
                    speed = strip_ansi(d.get('_speed_str', '')).strip()
                    eta = strip_ansi(d.get('_eta_str', '')).strip()
                    if on_progress:
                        on_progress(val, speed, eta)
                elif status == 'finished':
                    if on_processing:
                        on_processing(d.get('filename') or '')

            ydl_opts = {
                'outtmpl': outtmpl,
                'quiet': True,
                'no_warnings': True,
                'logtostderr': False,
                'progress_hooks': [progress_hook],
            }

            try:
                with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    ydl.download([video_url])
            except yt_dlp.utils.DownloadError as e:
                if "Cancelled by user" in str(e) or stop_event.is_set():
                    cleanup_temp_files(outtmpl, options.output_dir)
                    if on_cancelled:
                        on_cancelled()
                    return False
                raise

            if on_done:
                on_done(display_name)
            return True

        except Exception as e:
            logger.error(f"[{serial_number}] Ошибка: {e}")
            if stop_event.is_set():
                cleanup_temp_files(outtmpl, options.output_dir)
                if on_cancelled:
                    on_cancelled()
            else:
                if on_error:
                    on_error(str(e))
            return False


# =====================================================================
# Консольный режим (Termux / CLI)
# =====================================================================

class CliApp:
    def __init__(self, options: DownloadOptions):
        self.options = options
        self._print_lock = threading.Lock()
        self._is_tty = sys.stdout.isatty()
        if platform.system() == "Windows" and self._is_tty:
            os.system("")  # Включает поддержку ANSI-последовательностей в Windows 10+

    def _term_width(self) -> int:
        return max(30, shutil.get_terminal_size((80, 20)).columns)

    def _clear_status_line_locked(self) -> None:
        if self._is_tty:
            sys.stdout.write("\r\033[K")
            sys.stdout.flush()

    def _draw_status_line_locked(self, text: str) -> None:
        if not self._is_tty or not text:
            return
        max_chars = self._term_width() - 1
        if len(text) > max_chars:
            text = text[: max_chars - 1] + "…"
        sys.stdout.write(f"\r\033[K{text}")
        sys.stdout.flush()

    def _log_event(self, message: str, status_line: str = "") -> None:
        with self._print_lock:
            self._clear_status_line_locked()
            print(message, flush=True)
            if status_line:
                self._draw_status_line_locked(status_line)

    def check_environment_hints(self) -> None:
        if is_termux() and not os.path.isdir(os.path.expanduser("~/storage/downloads")):
            print(
                "[!] Совет для Termux: выполните команду termux-setup-storage,\n"
                "    чтобы сохранять видео сразу в папку «Загрузки» телефона."
            )
        if shutil.which("ffmpeg") is None:
            hint = "pkg install ffmpeg" if is_termux() else "установите пакет ffmpeg"
            print(
                f"[!] Внимание: ffmpeg не найден ({hint}).\n"
                "    Без него видео и аудио с некоторых сервисов могут не склеиться."
            )

    def download_batch(self, urls: list[str]) -> bool:
        urls = list(dict.fromkeys(urls))
        if not urls:
            print("Нет корректных ссылок для скачивания.")
            return False

        total = len(urls)
        print(f"\nЗапуск скачивания ({total} шт.) -> {self.options.output_dir}")

        semaphore = threading.Semaphore(max(1, self.options.max_workers))
        state_lock = threading.Lock()
        stop_flags: dict[str, threading.Event] = {}
        outtmpl_map: dict[str, str] = {}
        active_status: dict[str, str] = {}
        milestone_printed: dict[str, int] = {}
        results: dict[str, str] = {}
        threads: list[threading.Thread] = []

        def build_status_bar() -> str:
            with state_lock:
                done_cnt = len(results)
                parts = [f"{tid} {txt}" for tid, txt in active_status.items()]
            if not parts:
                return f"Обработка... ({done_cnt}/{total})"
            joined = " | ".join(parts)
            return f"[{done_cnt}/{total}] {joined}"

        def worker(url: str, idx: int) -> None:
            tid = f"#{idx}"
            stop_ev = stop_flags[tid]
            short_name = url if len(url) <= 45 else url[:42] + "..."

            def on_title(name: str):
                nonlocal short_name
                short_name = name
                with state_lock:
                    active_status[tid] = "0.0%"
                self._log_event(f"[{tid}] Скачивание: {name}", build_status_bar())

            def on_outtmpl(tmpl: str):
                with state_lock:
                    outtmpl_map[tid] = tmpl

            def on_progress(val: float, speed: str, eta: str):
                extra = ""
                if total == 1 and speed:
                    extra = f" ({speed}" + (f", ETA {eta}" if eta else "") + ")"
                with state_lock:
                    active_status[tid] = f"{val:5.1f}%{extra}"

                if self._is_tty:
                    status_text = build_status_bar()
                    with self._print_lock:
                        self._draw_status_line_locked(status_text)
                else:
                    bucket = int(val // 25) * 25
                    prev = milestone_printed.get(tid, -1)
                    if bucket > prev and bucket in (25, 50, 75):
                        milestone_printed[tid] = bucket
                        self._log_event(f"[{tid}] {short_name}: {val:.0f}%")

            def on_processing(_filename: str):
                with state_lock:
                    active_status[tid] = "Склейка/обработка..."
                if self._is_tty:
                    status_text = build_status_bar()
                    with self._print_lock:
                        self._draw_status_line_locked(status_text)

            def on_done(name: str):
                with state_lock:
                    active_status.pop(tid, None)
                    results[tid] = "ok"
                self._log_event(f"[{tid}] Готово ✅ — {name}", build_status_bar())

            def on_cancelled():
                with state_lock:
                    active_status.pop(tid, None)
                    results[tid] = "cancelled"
                self._log_event(f"[{tid}] Отменено ⛔ — {short_name}", build_status_bar())

            def on_error(err_msg: str):
                with state_lock:
                    active_status.pop(tid, None)
                    results[tid] = "error"
                clean_err = strip_ansi(err_msg).splitlines()[0] if err_msg else "неизвестная ошибка"
                self._log_event(f"[{tid}] Ошибка ❌ — {short_name}: {clean_err}", build_status_bar())

            download_single_video(
                video_url=url,
                serial_number=idx,
                options=self.options,
                semaphore=semaphore,
                stop_event=stop_ev,
                on_title=on_title,
                on_outtmpl=on_outtmpl,
                on_progress=on_progress,
                on_processing=on_processing,
                on_done=on_done,
                on_cancelled=on_cancelled,
                on_error=on_error,
            )

        for idx, url in enumerate(urls, start=1):
            tid = f"#{idx}"
            stop_flags[tid] = threading.Event()
            t = threading.Thread(target=worker, args=(url, idx), daemon=True)
            threads.append(t)
            t.start()

        try:
            while any(t.is_alive() for t in threads):
                time.sleep(0.1)
        except KeyboardInterrupt:
            with self._print_lock:
                self._clear_status_line_locked()
                print("\nПрерывание (Ctrl+C): останавливаем загрузки и очищаем временные файлы...")
            for ev in stop_flags.values():
                ev.set()
            for t in threads:
                t.join(timeout=3.0)
            with state_lock:
                tmpls = list(outtmpl_map.values())
            for tmpl in tmpls:
                cleanup_temp_files(tmpl, self.options.output_dir)
            return False

        with self._print_lock:
            self._clear_status_line_locked()

        ok_count = sum(1 for v in results.values() if v == "ok")
        print(f"Завершено: успешно {ok_count} из {total}. Папка: {self.options.output_dir}")
        return ok_count == total

    def run_interactive(self) -> int:
        print("=" * 60)
        print(f"  VK Video Download v{currentVersion} — Консольный режим (Termux / CLI)")
        print("=" * 60)
        self.check_environment_hints()

        while True:
            print("-" * 60)
            print(f"Папка сохранения: {self.options.output_dir}")
            print(
                "Настройки: "
                f"[1] Случайное имя: {'ВКЛ' if self.options.random_name else 'ВЫКЛ'} | "
                f"[2] Лимит 50 симв.: {'ВКЛ' if self.options.limit_length else 'ВЫКЛ'} | "
                f"[3] Отд. папка: {'ВКЛ' if self.options.separate_folder else 'ВЫКЛ'}"
            )
            print("Команды: 1/2/3 — переключить опцию, d — сменить папку, q — выход")

            try:
                raw = input("Вставьте ссылку(и) или команду > ").strip()
            except (EOFError, KeyboardInterrupt):
                print("\nВыход.")
                return 0

            if not raw:
                continue

            cmd = raw.lower()
            if cmd in ("q", "quit", "exit", "выход"):
                print("Выход.")
                return 0
            if cmd == "1":
                self.options.random_name = not self.options.random_name
                continue
            if cmd == "2":
                self.options.limit_length = not self.options.limit_length
                continue
            if cmd == "3":
                self.options.separate_folder = not self.options.separate_folder
                continue
            if cmd == "d":
                try:
                    new_dir = input("Новый путь для сохранения (Enter — отмена): ").strip()
                except (EOFError, KeyboardInterrupt):
                    print()
                    continue
                if new_dir:
                    self.options.output_dir = os.path.expanduser(new_dir)
                continue

            urls = extract_urls(raw)
            if not urls:
                print("Не удалось распознать ссылку. Попробуйте ещё раз.")
                continue

            self.download_batch(urls)


# =====================================================================
# Графический режим (Tkinter GUI)
# =====================================================================

_BaseFrame = ttk.Frame if TK_AVAILABLE else object


class App(_BaseFrame):
    def __init__(self, parent, initial_options: DownloadOptions = None, initial_urls: list[str] = None):
        ttk.Frame.__init__(self)
        self.root = parent
        self.initial_options = initial_options or DownloadOptions(output_dir=get_default_output_dir())

        for index in [0, 1, 2]:
            self.columnconfigure(index=index, weight=1)
            self.rowconfigure(index=index, weight=1)

        # Контекстное меню
        self.entry_context_menu = Menu(self, tearoff=0)
        self.entry_context_menu.add_command(label="Вырезать", command=self.cut_text)
        self.entry_context_menu.add_command(label="Копировать", command=self.copy_text)
        self.entry_context_menu.add_command(label="Вставить", command=self.paste_text)
        self.entry_context_menu.add_separator()
        self.entry_context_menu.add_command(label="Выделить всё", command=self.select_all)

        # прогресс/состояние
        self.progress_lock = threading.Lock()
        self.download_progress = {}  # thread_id -> text
        self.stop_flags = {}         # thread_id -> threading.Event()
        self.outtmpl_map = {}        # thread_id -> outtmpl
        self.active_downloads = set()

        self.has_activity = False

        # лимит параллельных загрузок
        self.max_workers = max(1, self.initial_options.max_workers)
        self.semaphore = threading.Semaphore(self.max_workers)

        # статистика
        self.total_jobs = 0
        self.done_jobs = 0

        # троттлинг UI
        self._last_ui_update = 0.0
        self._ui_update_interval = 0.15
        self._pending_ui_update = False

        self.setup_widgets()

        if initial_urls:
            self.entry_nm.insert(tk.END, ", ".join(initial_urls))

    # ------------------ UI ------------------

    def setup_widgets(self):
        self.widgets_frame = ttk.Frame(self, padding=(0, 5, 0, 0))
        self.widgets_frame.grid(row=0, column=1, padx=10, pady=(5, 0), sticky="nsew")
        self.widgets_frame.columnconfigure(index=0, weight=1)
        self.widgets_frame.rowconfigure(index=4, weight=1)  # под таблицу

        self.label = ttk.Label(
            self.widgets_frame,
            text="Вставьте ссылку(и) на видео через запятую",
            justify="center",
            font=("-size", 15, "-weight", "bold"),
        )
        self.label.grid(row=0, column=0, padx=0, pady=10, sticky="n")

        self.entry_nm = ttk.Entry(self.widgets_frame, font=("Calibri 22"))
        self.entry_nm.insert(tk.END, str(''))
        self.entry_nm.grid(
            row=1, column=0, columnspan=10,
            padx=(5, 5), ipadx=150, ipady=5,
            pady=(0, 0), sticky="ew"
        )
        self.entry_nm.bind('<Return>', self.on_enter_pressed)

        # Горячие клавиши
        self.entry_nm.bind('<Control-c>', self.copy_text)
        self.entry_nm.bind('<Control-v>', self.paste_text)
        self.entry_nm.bind('<Control-x>', self.cut_text)
        self.entry_nm.bind('<Control-a>', self.select_all)
        self.entry_nm.bind('<Control-KeyPress>', self.handle_control_key)
        self.entry_nm.bind("<Button-3>", self.show_context_menu)

        # Кнопка "Скачать видео"
        self.bt_frame = ttk.Frame(self, padding=(0, 0, 0, 0))
        self.bt_frame.grid(row=2, column=0, padx=10, pady=(5, 0), columnspan=10, sticky="n")

        self.accentbutton = ttk.Button(
            self.bt_frame,
            text="Скачать видео",
            style="Accent.TButton",
            command=self.get_directory_string
        )
        self.accentbutton.grid(row=0, column=0, columnspan=3, ipadx=30, padx=2, pady=(5, 0), sticky="n")
        self.bt_frame.columnconfigure(0, weight=1)

        # --- Чекбоксы ---
        self.check_frame = ttk.Frame(self.widgets_frame)
        self.check_frame.grid(row=3, column=0, padx=20, pady=(5, 5), sticky="w")

        self.var_random_name = tk.StringVar(value='random' if self.initial_options.random_name else '')
        self.var_limit_length = tk.StringVar(value='limit' if self.initial_options.limit_length else '')
        self.var_folder = tk.StringVar(value='folder' if self.initial_options.separate_folder else '')

        self.check_random = ttk.Checkbutton(
            self.check_frame,
            text="Случайное имя",
            variable=self.var_random_name,
            onvalue='random',
            offvalue='',
            style="Switch.TCheckbutton"
        )
        self.check_random.grid(row=0, column=0, padx=(0, 10), pady=0, sticky="w")

        self.check_limit = ttk.Checkbutton(
            self.check_frame,
            text="Ограничить длину до 50 симв.",
            variable=self.var_limit_length,
            onvalue='limit',
            offvalue='',
            style="Switch.TCheckbutton"
        )
        self.check_limit.grid(row=0, column=1, padx=(0, 10), pady=0, sticky="w")

        self.check_folder = ttk.Checkbutton(
            self.check_frame,
            text="Отдельная папка для видео",
            variable=self.var_folder,
            onvalue='folder',
            offvalue='',
            style="Switch.TCheckbutton"
        )
        self.check_folder.grid(row=0, column=2, padx=0, pady=0, sticky="w")

        # --- Таблица загрузок ---
        self.table_frame = ttk.Frame(self.widgets_frame)
        self.table_frame.grid(row=4, column=0, padx=10, pady=(5, 0), sticky="nsew")
        self.table_frame.rowconfigure(0, weight=1)
        self.table_frame.columnconfigure(0, weight=1)

        # стиль фона списка
        style = ttk.Style()
        style.configure(
            "Custom.Treeview",
            background="#EDEEF0",
            fieldbackground="#EDEEF0",
            foreground="#000000",
            rowheight=24
        )
        style.map("Custom.Treeview", background=[("selected", "#D6D8DC")])

        columns = ("id", "name", "status", "action")
        self.tree = ttk.Treeview(
            self.table_frame,
            columns=columns,
            show="headings",
            height=5,
            style="Custom.Treeview"
        )

        self.tree.heading("id", text="#")
        self.tree.heading("name", text="Имя / источник")
        self.tree.heading("status", text="Статус")
        self.tree.heading("action", text="")

        self.tree.column("id", width=60, anchor="center", stretch=False)
        self.tree.column("name", width=380, anchor="w", stretch=True)
        self.tree.column("status", width=150, anchor="w", stretch=False)
        self.tree.column("action", width=50, anchor="center", stretch=False)

        self.tree.grid(row=0, column=0, sticky="nsew")

        self.scrollbar = ttk.Scrollbar(self.table_frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=self.scrollbar.set)
        self.scrollbar.grid(row=0, column=1, sticky="ns")

        # клики по таблице (Cancel)
        self.tree.bind("<Button-1>", self.on_tree_click)

        # --- нижняя строка статуса ---
        self.status_label = ttk.Label(
            self.widgets_frame,
            text="Готов к загрузке",
            justify="left",
            anchor="w",
            font=("-size", 10, "-weight", "normal"),
            wraplength=650,
            foreground="#aaaaaa"
        )
        self.status_label.grid(row=5, column=0, padx=20, pady=(5, 5), sticky="ew")

        # --- Кнопки: About, Версия, Donate ---
        self.copy_frame = ttk.Frame(self, padding=(0, 0, 0, 10))
        self.copy_frame.grid(row=8, column=0, padx=10, pady=5, columnspan=10, sticky="s")

        self.UrlButton = ttk.Button(self.copy_frame, text="About", style="Url.TButton", command=self.openweb)
        self.UrlButton.grid(row=1, column=0, padx=20, pady=0, columnspan=2, sticky="n")

        self.UrlButton = ttk.Button(
            self.copy_frame, text="Vers.: " + currentVersion + " ", style="Url.TButton", command=self.checkUpdate
        )
        self.UrlButton.grid(row=1, column=4, padx=20, pady=0, columnspan=2, sticky="w")

        self.UrlButton = ttk.Button(self.copy_frame, text="Donate", style="Url.TButton", command=self.donate)
        self.UrlButton.grid(row=1, column=7, padx=20, pady=0, columnspan=2, sticky="w")

    def on_tree_click(self, event):
        """Клик по колонке Action отменяет конкретную загрузку"""
        region = self.tree.identify("region", event.x, event.y)
        if region != "cell":
            return

        col = self.tree.identify_column(event.x)
        row_id = self.tree.identify_row(event.y)

        if not row_id or col != "#4":
            return

        values = self.tree.item(row_id, "values")
        if not values:
            return

        thread_id = values[0]  # "#1"
        self.cancel_download(thread_id)

    def cancel_download(self, thread_id: str):
        with self.progress_lock:
            flag = self.stop_flags.get(thread_id)

        if flag:
            flag.set()
            self.set_status_error(f"Остановка {thread_id}...")

    # ------------------ common handlers ------------------

    def show_context_menu(self, event):
        try:
            self.entry_nm.focus_set()
            self.entry_context_menu.tk_popup(event.x_root, event.y_root)
        except Exception as e:
            logger.error(f"Error showing context menu: {str(e)}")
            self.set_status_error(f"Ошибка: {str(e)}")
        finally:
            self.entry_context_menu.grab_release()

    def handle_control_key(self, event):
        try:
            keycode = event.keycode
            if keycode == 67:
                self.copy_text()
                return "break"
            elif keycode == 86:
                self.paste_text()
                return "break"
            elif keycode == 88:
                self.cut_text()
                return "break"
            elif keycode == 65:
                self.select_all()
                return "break"
        except Exception as e:
            self.set_status_error(f"Ошибка: {str(e)}")
            return "break"

    def copy_text(self, event=None):
        self.entry_nm.event_generate("<<Copy>>")
        return "break"

    def paste_text(self, event=None):
        self.entry_nm.event_generate("<<Paste>>")
        return "break"

    def cut_text(self, event=None):
        self.entry_nm.event_generate("<<Cut>>")
        return "break"

    def select_all(self, event=None):
        self.entry_nm.event_generate("<<SelectAll>>")
        return "break"

    def openweb(self):
        webbrowser.open_new_tab('https://github.com/blyamur/VK-Video-Download')

    def donate(self):
        webbrowser.open_new_tab('https://yoomoney.ru/to/41001158104834')

    def checkUpdate(self, method='Button'):
        if requests is None:
            if method == 'Button':
                messagebox.showwarning("Обновления", "Модуль requests не установлен.")
            return
        try:
            logger.info("Проверка обновлений")
            github_page = requests.get(
                'https://raw.githubusercontent.com/blyamur/VK-Video-Download/main/README.md',
                timeout=10,
            )
            github_page_html = str(github_page.content).split()
            version = None
            for token in github_page_html:
                if re.match(r'^\d+\.\d+$', token):
                    version = token
                    break

            if version and float(version) > float(currentVersion):
                update = messagebox.askyesno("Обновление", f"Доступна версия {version}. Обновиться?")
                if update:
                    webbrowser.open_new_tab('https://github.com/blyamur/VK-Video-Download')
            elif method == 'Button':
                messagebox.showinfo("Обновления", "Нет новых версий")
        except Exception as e:
            logger.error(f"Ошибка проверки: {e}")

    # ------------------ downloads ------------------

    def get_directory_string(self):
        try:
            urls_input = self.entry_nm.get().strip()
            if not urls_input:
                self.set_status_error("Введите ссылку")
                return

            video_urls = extract_urls(urls_input)
            if not video_urls:
                self.set_status_error("Нет корректных ссылок")
                return

            # Читаем значения чекбоксов в главном потоке Tkinter (потокобезопасно для Linux/X11)
            batch_options = DownloadOptions(
                output_dir=self.initial_options.output_dir,
                random_name=(self.var_random_name.get() == 'random'),
                limit_length=(self.var_limit_length.get() == 'limit'),
                separate_folder=(self.var_folder.get() == 'folder'),
                max_workers=self.max_workers,
            )

            self.total_jobs = len(video_urls)
            self.done_jobs = 0

            self.has_activity = True
            self.entry_nm.delete(0, tk.END)

            # очищаем таблицу
            for item in self.tree.get_children():
                self.tree.delete(item)

            # заполняем строки
            for idx, url in enumerate(video_urls, start=1):
                thread_id = f"#{idx}"

                short_url = url
                if len(short_url) > 60:
                    short_url = short_url[:57] + "..."

                with self.progress_lock:
                    self.stop_flags[thread_id] = threading.Event()
                    self.download_progress[thread_id] = "  0.0%"

                self.tree.insert("", "end", iid=thread_id, values=(thread_id, short_url, "  0.0%", "✖"))

                t = threading.Thread(
                    target=self.download_video,
                    args=(url, idx, batch_options),
                    daemon=True,
                )
                t.start()

            self.update_status_bar(force=True)

        except Exception as e:
            logger.error(f"Ошибка: {e}")
            self.set_status_error(f"Ошибка: {str(e)}")

    def download_video(self, video_url: str, serial_number: int, options: DownloadOptions):
        thread_id = f"#{serial_number}"
        with self.progress_lock:
            stop_ev = self.stop_flags[thread_id]
            self.active_downloads.add(thread_id)

        def on_title(display_name: str):
            def _set_name():
                if self.tree.exists(thread_id):
                    values = list(self.tree.item(thread_id, "values"))
                    values[1] = display_name
                    self.tree.item(thread_id, values=values)
            self.root.after(0, _set_name)

        def on_outtmpl(outtmpl: str):
            with self.progress_lock:
                self.outtmpl_map[thread_id] = outtmpl

        def on_progress(val: float, _speed: str, _eta: str):
            percent = f"{val:5.1f}%"
            with self.progress_lock:
                self.download_progress[thread_id] = percent
            self.update_row(thread_id, percent)
            self.update_status_bar()

        def on_done(_display_name: str):
            with self.progress_lock:
                self.download_progress[thread_id] = "Готово ✅"
            self.update_row(thread_id, "Готово ✅")
            self.update_status_bar(force=True)

        def on_cancelled():
            with self.progress_lock:
                self.download_progress[thread_id] = "Отменено ⛔"
            self.update_row(thread_id, "Отменено ⛔")
            self.update_status_bar(force=True)

        def on_error(_err_msg: str):
            with self.progress_lock:
                self.download_progress[thread_id] = "Ошибка ❌"
            self.update_row(thread_id, "Ошибка ❌")
            self.update_status_bar(force=True)

        try:
            download_single_video(
                video_url=video_url,
                serial_number=serial_number,
                options=options,
                semaphore=self.semaphore,
                stop_event=stop_ev,
                on_title=on_title,
                on_outtmpl=on_outtmpl,
                on_progress=on_progress,
                on_done=on_done,
                on_cancelled=on_cancelled,
                on_error=on_error,
            )
        finally:
            with self.progress_lock:
                self.active_downloads.discard(thread_id)

    # ------------------ UI updates ------------------

    def update_row(self, thread_id: str, status_text: str):
        def _update():
            if self.tree.exists(thread_id):
                values = list(self.tree.item(thread_id, "values"))
                values[2] = status_text

                if status_text in ("Готово ✅", "Ошибка ❌", "Отменено ⛔"):
                    values[3] = ""

                self.tree.item(thread_id, values=values)

        self.root.after(0, _update)

    def update_status_bar(self, force=False):
        now = time.time()

        if not force:
            if (now - self._last_ui_update) < self._ui_update_interval:
                if not self._pending_ui_update:
                    self._pending_ui_update = True
                    self.root.after(int(self._ui_update_interval * 1000), self.update_status_bar)
                return

        self._pending_ui_update = False
        self._last_ui_update = now

        with self.progress_lock:
            done = sum(
                1 for v in self.download_progress.values()
                if v in ("Готово ✅", "Ошибка ❌", "Отменено ⛔")
            )
            total = self.total_jobs if self.total_jobs else len(self.download_progress)

        text = f"Готово: {done}/{total}"
        self.root.after(0, lambda: self.status_label.configure(text=text, foreground="#000000"))

    def set_status_error(self, msg):
        self.root.after(0, lambda: self.status_label.configure(text=msg, foreground="#d93025"))

    def on_enter_pressed(self, event):
        self.get_directory_string()


def run_gui(options: DownloadOptions, initial_urls: list[str] = None, allow_cli_fallback: bool = True) -> int:
    if not TK_AVAILABLE:
        msg = (
            "Модуль tkinter не установлен.\n"
            "В Termux для консольного режима запускайте без флага --gui,\n"
            "либо установите графические пакеты: pkg install x11-repo && pkg install python-tkinter"
        )
        if allow_cli_fallback:
            print("Графический модуль tkinter недоступен — переключаемся в консольный режим (CLI).\n")
            return run_cli(options, initial_urls)
        print(msg, file=sys.stderr)
        return 1

    root = None
    try:
        system = platform.system()
        is_linux = system == 'Linux'
        if is_linux:
            logger.info("Запуск приложения под Linux")

        theme_dir = os.path.join(SCRIPT_DIR, 'theme')
        theme_file = os.path.join(theme_dir, 'vk_theme.tcl')
        icon_ico = os.path.join(theme_dir, 'icon.ico')
        icon_png = os.path.join(SCRIPT_DIR, 'icon.png')

        try:
            root = tk.Tk()
        except tk.TclError as e:
            if allow_cli_fallback:
                print(
                    f"Графический экран недоступен ({e}) — переключаемся в консольный режим (CLI).\n"
                )
                return run_cli(options, initial_urls)
            print(
                f"Не удалось открыть графическое окно: {e}\n"
                "Запустите X11/VNC сервер (export DISPLAY=:1) или используйте консольный режим (--cli).",
                file=sys.stderr,
            )
            return 1

        w = max(0, root.winfo_screenwidth() // 2 - 360)
        h = max(0, root.winfo_screenheight() // 2 - 215)
        root.geometry(f'720x430+{w}+{h}')
        root.resizable(False, False)
        root.title("Скачать видео с VK.com")

        # Попытка загрузить иконку (поддержка разных платформ)
        icon_loaded = False
        if os.path.exists(icon_ico):
            try:
                root.iconbitmap(icon_ico)
                icon_loaded = True
            except Exception as e:
                logger.warning(f"Не удалось загрузить иконку .ico: {e}")

        if not icon_loaded and os.path.exists(icon_png):
            try:
                icon_image = tk.PhotoImage(file=icon_png)
                root.iconphoto(True, icon_image)
                root.icon_image = icon_image
                icon_loaded = True
            except Exception as e:
                logger.warning(f"Не удалось загрузить иконку .png: {e}")

        # Подключение темы оформления (с мягким откатом на стандартную тему ttk)
        if os.path.exists(theme_file):
            try:
                root.tk.call("source", theme_file)
                root.tk.call("set_theme", "light")
            except Exception as e:
                logger.warning(f"Не удалось применить тему {theme_file}: {e}")
        else:
            logger.warning(f"Файл темы не найден ({theme_file}), используется стандартная тема ttk")

        app = App(root, initial_options=options, initial_urls=initial_urls)
        app.pack(fill="both", expand=True)
        root.update()
        logger.info("Приложение запущено")

        def on_closing():
            root.destroy()
            sys.exit(0)

        root.protocol("WM_DELETE_WINDOW", on_closing)
        root.mainloop()
        return 0

    except Exception as e:
        logger.error(f"Ошибка запуска: {e}")
        if root is not None:
            try:
                messagebox.showerror("Ошибка", f"Ошибка запуска: {e}")
            except Exception:
                print(f"Ошибка запуска: {e}", file=sys.stderr)
        else:
            print(f"Ошибка запуска: {e}", file=sys.stderr)
        return 1


def run_cli(options: DownloadOptions, urls: list[str] = None) -> int:
    cli = CliApp(options)
    if urls:
        cli.check_environment_hints()
        ok = cli.download_batch(urls)
        return 0 if ok else 1
    return cli.run_interactive()


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="Скачивание видео с VK, Rutube, YouTube, Mail.ru и др. (GUI + Termux/CLI)"
    )
    parser.add_argument(
        "urls",
        nargs="*",
        help="Ссылки на видео (через пробел или запятую). Если не указаны — откроется GUI или интерактивное меню.",
    )
    mode_group = parser.add_mutually_exclusive_group()
    mode_group.add_argument(
        "--cli",
        action="store_true",
        help="Принудительно запустить в консольном режиме (Termux / терминал).",
    )
    mode_group.add_argument(
        "--gui",
        action="store_true",
        help="Принудительно запустить графическое окно (Tkinter).",
    )
    parser.add_argument(
        "-o", "--output-dir",
        default=None,
        help="Папка для сохранения видео (в Termux по умолчанию ~/storage/downloads, иначе ./downloads).",
    )
    parser.add_argument(
        "-r", "--random-name",
        action="store_true",
        help="Сохранять видео со случайным именем.",
    )
    parser.add_argument(
        "-l", "--limit-length",
        action="store_true",
        help="Ограничить длину названия файла до 50 символов.",
    )
    parser.add_argument(
        "-f", "--folder",
        action="store_true",
        help="Создавать отдельную подпапку для каждого видео.",
    )
    parser.add_argument(
        "-w", "--workers",
        type=int,
        default=3,
        help="Максимум параллельных загрузок (по умолчанию: 3).",
    )
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)

    if not YTDLP_AVAILABLE:
        msg = (
            "Не установлена библиотека yt-dlp.\n"
            "Установите зависимости командой:\n"
            "  pip install -U -r requirements.txt\n"
            "(или в Termux запустите: bash setup_termux.sh)"
        )
        print(msg, file=sys.stderr)
        return 1

    output_dir = (
        os.path.expanduser(args.output_dir)
        if args.output_dir
        else get_default_output_dir(SCRIPT_DIR)
    )
    options = DownloadOptions(
        output_dir=output_dir,
        random_name=args.random_name,
        limit_length=args.limit_length,
        separate_folder=args.folder,
        max_workers=max(1, args.workers),
    )

    urls = extract_urls(" ".join(args.urls)) if args.urls else []

    # Выбираем режим:
    # 1) Явно запрошен --gui -> запускаем GUI
    # 2) Явно запрошен --cli или переданы ссылки в командной строке -> CLI
    # 3) Автовыбор: если доступен дисплей и tkinter -> GUI (с откатом в CLI), иначе -> CLI
    use_cli = args.cli or (bool(urls) and not args.gui) or (not args.gui and not has_gui_display())

    setup_logging(cli_mode=use_cli)

    if args.gui:
        return run_gui(options, initial_urls=urls, allow_cli_fallback=False)
    if use_cli:
        return run_cli(options, urls=urls)
    return run_gui(options, initial_urls=urls, allow_cli_fallback=True)


if __name__ == "__main__":
    sys.exit(main())
