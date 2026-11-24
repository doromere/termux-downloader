import importlib.machinery
import importlib.util
import io
import os
import tempfile
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
                tdl.set_config_value("video_quality", "1080")
                tdl.set_config_value("audio_format", "opus")
                tdl.set_config_value("threads", "2")
                tdl.set_config_value("auto_confirm", "true")
                loaded = tdl.load_config()
                self.assertEqual(loaded["video_quality"], "1080")
                self.assertEqual(loaded["audio_format"], "opus")
                self.assertEqual(loaded["threads"], 2)
                self.assertTrue(loaded["auto_confirm"])

                with self.assertRaises(ValueError):
                    tdl.set_config_value("video_quality", "4k")
                with self.assertRaises(ValueError):
                    tdl.set_config_value("threads", "99")

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


if __name__ == "__main__":
    unittest.main()
