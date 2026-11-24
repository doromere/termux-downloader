import importlib.machinery
import importlib.util
import io
import os
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
TDL_PATH = ROOT / "bin" / "termux-dl"


def load_termux_dl():
    loader = importlib.machinery.SourceFileLoader("termux_dl", str(TDL_PATH))
    spec = importlib.util.spec_from_loader("termux_dl", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


tdl = load_termux_dl()


class TestUrlHandling(unittest.TestCase):
    def test_extract_urls_scheme_and_bare(self):
        text = "Смотри https://youtu.be/abc, и https://en.wikipedia.org/wiki/Foo_(bar)!"
        self.assertEqual(
            tdl.extract_urls(text),
            ["https://youtu.be/abc", "https://en.wikipedia.org/wiki/Foo_(bar)"],
        )

        bare = "youtube.com/watch?v=123\nyoutube.com/watch?v=123\nfile.txt"
        self.assertEqual(tdl.extract_urls(bare), ["youtube.com/watch?v=123"])

    def test_normalize_rejects_flags(self):
        self.assertIsNone(tdl.normalize_url("--exec=id"))
        self.assertIsNone(tdl.normalize_url("not a url"))
        self.assertIsNone(tdl.normalize_url("ftp://example.com/a"))
        self.assertEqual(tdl.normalize_url("youtu.be"), "https://youtu.be")
        self.assertEqual(tdl.normalize_url("youtu.be/abc"), "https://youtu.be/abc")
        self.assertEqual(tdl.normalize_url("magnet:?xt=urn:btih:abc"), "magnet:?xt=urn:btih:abc")

    def test_detect_service_and_mass(self):
        self.assertEqual(tdl.detect_service("https://music.youtube.com/watch?v=1"), ("YouTube Music", "audio"))
        self.assertEqual(tdl.detect_service("https://youtu.be/1"), ("YouTube", "video"))
        self.assertEqual(tdl.detect_service("https://open.spotify.com/track/1"), ("Spotify", "spotify"))
        self.assertEqual(tdl.detect_service("https://www.deezer.com/album/1"), ("Deezer", "convert"))
        self.assertEqual(tdl.detect_service("https://music.yandex.ru/album/1"), ("Yandex Music", "audio_convert"))
        self.assertEqual(tdl.detect_service("https://www.netflix.com/title/1"), ("DRM", "drm"))
        self.assertEqual(tdl.detect_service("https://github.com/a/b"), ("GitHub", "git"))
        self.assertEqual(tdl.detect_service("magnet:?xt=urn:btih:1"), ("Torrents", "torrent"))
        self.assertTrue(tdl.is_direct_file("https://example.com/app.apk?v=1"))
        self.assertFalse(tdl.is_direct_file("https://example.com/page"))
        self.assertTrue(tdl.is_mass("https://www.youtube.com/playlist?list=PL123"))
        self.assertFalse(tdl.is_mass("https://www.youtube.com/watch?v=123"))


class TestConfigAndDoctor(unittest.TestCase):
    def test_config_roundtrip_and_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg_file = Path(tmp) / "config.json"
            with mock.patch.dict(os.environ, {"TUO_CONFIG": str(cfg_file)}):
                self.assertEqual(tdl.load_config()["video_quality"], "ask")
                self.assertEqual(tdl.load_config()["ui"], "auto")
                tdl.set_config_value("video_quality", "1080")
                tdl.set_config_value("audio_format", "opus")
                tdl.set_config_value("threads", "2")
                tdl.set_config_value("auto_confirm", "true")
                tdl.set_config_value("ui", "cli")
                loaded = tdl.load_config()
                self.assertEqual(loaded["video_quality"], "1080")
                self.assertEqual(loaded["audio_format"], "opus")
                self.assertEqual(loaded["threads"], 2)
                self.assertTrue(loaded["auto_confirm"])
                self.assertEqual(loaded["ui"], "cli")

                with self.assertRaises(ValueError):
                    tdl.set_config_value("video_quality", "4k")
                with self.assertRaises(ValueError):
                    tdl.set_config_value("threads", "99")
                with self.assertRaises(ValueError):
                    tdl.set_config_value("ui", "web")

    def test_doctor_reports_missing_and_ok(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg_file = Path(tmp) / "cfg" / "config.json"
            dl_dir = Path(tmp) / "downloads"
            with mock.patch.dict(os.environ, {"TUO_CONFIG": str(cfg_file), "TUO_ROOT": str(dl_dir)}):
                with mock.patch.object(tdl.shutil, "which", return_value="/usr/bin/tool"):
                    buf = io.StringIO()
                    with redirect_stdout(buf):
                        rc = tdl.run_doctor()
                    self.assertEqual(rc, 0)
                    self.assertIn("Готово к работе", buf.getvalue())

                with mock.patch.object(tdl.shutil, "which", return_value=None):
                    buf = io.StringIO()
                    with redirect_stdout(buf):
                        rc = tdl.run_doctor()
                    self.assertEqual(rc, 1)
                    self.assertIn("критические проблемы", buf.getvalue())


class TestDownloadEngineAndUi(unittest.TestCase):
    def test_ytdlp_api_and_progress_hook(self):
        captured_opts = {}

        class FakeYDL:
            def __init__(self, opts):
                captured_opts.update(opts)

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def download(self, urls):
                hook = captured_opts["progress_hooks"][0]
                hook({"status": "downloading", "downloaded_bytes": 512, "total_bytes": 1024, "speed": 2048, "eta": 1})
                hook({"status": "finished"})
                return 0

        fake_mod = types.SimpleNamespace(YoutubeDL=FakeYDL)
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            with (
                mock.patch.dict(sys.modules, {"yt_dlp": fake_mod}),
                mock.patch.object(tdl.shutil, "which", return_value="/usr/bin/node"),
                mock.patch.object(tdl, "notify") as m_notify,
                mock.patch.object(tdl, "dismiss_notification") as m_dismiss,
                redirect_stdout(io.StringIO()),
            ):
                rc = tdl._run_ytdlp_api(
                    "https://youtu.be/abc", dest, None, "res:720,ext:mp4:m4a", None, "YouTube"
                )
            self.assertEqual(rc, 0)
            self.assertEqual(captured_opts["format_sort"], ["res:720", "ext:mp4:m4a"])
            self.assertEqual(captured_opts["js_runtimes"], {"node": {}})
            m_notify.assert_called()
            m_dismiss.assert_called_with("termux-dl-progress")

    def test_gui_menu_and_confirm_with_mock_termuxgui(self):
        class FakeWidget:
            _next = 1

            def __init__(self, *a, **kw):
                self.id = FakeWidget._next
                FakeWidget._next += 1

        class FakeConn:
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def events(self):
                # Activity=0, LinearLayout=1, TextView=2, Button1=3, Button2=4 ("1080p")
                yield types.SimpleNamespace(type="click", value={"id": 4})

        fake_tg = types.SimpleNamespace(
            Connection=FakeConn,
            Activity=FakeWidget,
            LinearLayout=FakeWidget,
            TextView=FakeWidget,
            Button=FakeWidget,
            Event=types.SimpleNamespace(click="click", destroy="destroy"),
        )
        FakeWidget._next = 0
        with mock.patch.dict(sys.modules, {"termuxgui": fake_tg}):
            choice = tdl.gui_video_menu("YouTube", "mp3")
        self.assertEqual(choice, "2")

        # Missing termuxgui falls back cleanly to None
        with mock.patch.dict(sys.modules, {"termuxgui": None}):
            self.assertIsNone(tdl.gui_video_menu("YouTube", "mp3"))
            self.assertIsNone(tdl.gui_confirm("Title", "Question"))

    def test_run_urls_cancel_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg_file = Path(tmp) / "cfg" / "config.json"
            dl_dir = Path(tmp) / "downloads"
            with (
                mock.patch.dict(os.environ, {"TUO_CONFIG": str(cfg_file), "TUO_ROOT": str(dl_dir), "TUO_DRY_RUN": "0"}),
                mock.patch.object(tdl, "_confirm_action", return_value=False),
                mock.patch.object(tdl, "notify") as m_notify,
            ):
                buf = io.StringIO()
                with redirect_stdout(buf):
                    rc = tdl.run_urls(["https://example.com/archive.zip"])
                self.assertEqual(rc, 0)
                self.assertIn("Отменено: 1 из 1", buf.getvalue())
                m_notify.assert_not_called()
                hist = (cfg_file.parent / "history.log").read_text(encoding="utf-8")
                self.assertIn("SKIP", hist)


if __name__ == "__main__":
    unittest.main()
