import importlib.machinery
import importlib.util
import io
import json
import tempfile
import unittest
import urllib.request
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
PLAYLIST_DUMP_PATH = ROOT / "bin" / "playlist-dump"


def load_playlist_dump():
    loader = importlib.machinery.SourceFileLoader("playlist_dump", str(PLAYLIST_DUMP_PATH))
    spec = importlib.util.spec_from_loader("playlist_dump", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


pd = load_playlist_dump()


class TestParsingAndMatching(unittest.TestCase):
    def test_strip_num_preserves_band_names(self):
        self.assertEqual(pd.strip_num("01. Artist - Track"), "Artist - Track")
        self.assertEqual(pd.strip_num("02) Artist - Track"), "Artist - Track")
        self.assertEqual(pd.strip_num("03 - Artist - Track"), "Artist - Track")
        self.assertEqual(pd.strip_num("311 - Amber"), "311 - Amber")

    def test_parse_csv_and_txt_encodings(self):
        with tempfile.TemporaryDirectory() as tmp:
            p_csv = Path(tmp) / "export.csv"
            csv_body = (
                "Track name,Artist name,Album,Playlist name,ISRC,Spotify - id\n"
                "Ёжик,Артист,Альбом,Мой плейлист,USRC17607839,4uLU6hMCjMI75M1A2tKUQC\n"
            )
            p_csv.write_bytes(csv_body.encode("utf-8-sig"))
            rows, kind = pd.parse_dump(p_csv)
            self.assertEqual(kind, "csv")
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["title"], "Ёжик")
            self.assertEqual(rows[0]["sid"], "4uLU6hMCjMI75M1A2tKUQC")
            self.assertEqual(rows[0]["playlist"], "Мой плейлист")

            p_txt = Path(tmp) / "list.txt"
            txt_body = (
                "# comment\n"
                "01. Daft Punk - Around the World\n"
                "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC\n"
                "https://youtube.com/watch?v=ignored\n"
            )
            p_txt.write_bytes(txt_body.encode("cp1251"))
            rows_txt, kind_txt = pd.parse_dump(p_txt)
            self.assertEqual(kind_txt, "txt")
            self.assertEqual(len(rows_txt), 2)
            self.assertEqual(rows_txt[0]["query"], "Daft Punk - Around the World")
            self.assertEqual(rows_txt[1]["sid"], "4uLU6hMCjMI75M1A2tKUQC")

    def test_tokens_and_score(self):
        item = {
            "id": "abc123",
            "name": "Ежик в тумане (Remastered)",
            "artists": [{"name": "Артист"}],
        }
        row = {"title": "Ёжик в тумане", "artist": "Артист", "query": ""}
        self.assertGreaterEqual(pd.score(item, row), 0.9)

        wrong = {"id": "xyz", "name": "Other Song", "artists": [{"name": "Someone"}]}
        self.assertEqual(pd.score(wrong, row), 0.0)

    def test_existing_matches_special_chars_and_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            (dest / "ACDC - Back In Black.mp3").write_bytes(b"")
            (dest / "01 - System Of A Down - X.opus").write_bytes(b"")
            ex = pd.Existing(dest)

            r1 = {"title": "Back In Black", "artist": "AC/DC", "sid": None, "query": ""}
            r2 = {"title": "X", "artist": "System Of A Down", "sid": None, "query": ""}
            r3 = {"title": "Missing", "artist": "Unknown", "sid": None, "query": ""}

            self.assertEqual(ex.find(r1, {}), "ACDC - Back In Black.mp3")
            self.assertEqual(ex.find(r2, {}), "01 - System Of A Down - X.opus")
            self.assertIsNone(ex.find(r3, {}))

    def test_safe_name_byte_limit(self):
        cyr = "А" * 100
        out = pd.safe_name(cyr, limit=50)
        self.assertLessEqual(len(out.encode("utf-8")), 50)
        self.assertEqual(pd.safe_name('a/b:c*?"<>|'), "a_b_c______")


class TestLinksAndConcurrency(unittest.TestCase):
    def test_resolve_deezer_album_and_apple_song(self):
        def fake_http_json(url):
            if "/album/42/tracks" in url:
                return {"data": [{"title": "Track 1", "artist": {"name": "Art"}, "isrc": "US123"}]}
            if "/album/42" in url:
                return {"title": "Alb", "artist": {"name": "Art"}}
            if "itunes.apple.com/lookup" in url:
                return {
                    "results": [
                        {
                            "wrapperType": "track",
                            "trackName": "Song",
                            "artistName": "Singer",
                            "collectionName": "Col",
                        }
                    ]
                }
            raise AssertionError(f"Unexpected URL: {url}")

        with mock.patch.object(pd, "http_json", side_effect=fake_http_json):
            name, rows = pd.resolve_link("https://www.deezer.com/album/42")
            self.assertEqual(name, "Art - Alb")
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["isrc"], "US123")

            aname, arows = pd.resolve_link("https://music.apple.com/us/album/x/1?i=999")
            self.assertIsNone(aname)
            self.assertEqual(arows[0]["title"], "Song")

        with self.assertRaises(pd.LinkError):
            pd.resolve_link("https://music.apple.com/us/playlist/pl.123")

    def test_redirect_handler_blocks_non_http(self):
        handler = pd._HttpOnlyRedirect()
        req = urllib.request.Request("https://example.com")
        with self.assertRaises(pd.LinkError):
            handler.redirect_request(req, None, 302, "Found", {}, "file:///etc/passwd")

    def test_resolve_thread_safety_with_periodic_save(self):
        class FakeSpotify:
            client = object()

            def search(self, query):
                idx = query.split()[-1]
                return [
                    {
                        "id": f"id{idx}",
                        "name": f"Song {idx}",
                        "duration_ms": 180000,
                        "artists": [{"id": "a1", "name": "Artist"}],
                        "album": {
                            "id": "alb1",
                            "name": "Album",
                            "release_date": "2024-01-01",
                            "images": [],
                            "artists": [{"name": "Artist"}],
                        },
                    }
                ]

        rows = [
            {"title": f"Song {i}", "artist": "Artist", "album": "", "isrc": "", "sid": None, "playlist": "", "query": ""}
            for i in range(60)
        ]
        with tempfile.TemporaryDirectory() as tmp:
            cache, save_cache = pd.load_cache(Path(tmp), dry=False)
            with redirect_stdout(io.StringIO()):
                pd.resolve(rows, FakeSpotify(), cache, save_cache)
            self.assertEqual(len(cache["meta"]), 60)
            saved = json.loads((Path(tmp) / pd.CACHE_NAME).read_text(encoding="utf-8"))
            self.assertEqual(len(saved["meta"]), 60)

    def test_run_youtube_prints_reason_and_keeps_not_found_clean(self):
        rows = [{"title": "Track", "artist": "Artist", "album": "", "isrc": "", "sid": None, "playlist": "", "query": ""}]
        fake_res = mock.Mock(stdout="", stderr="ERROR: Video unavailable\n", returncode=1)
        opts = mock.Mock(format="mp3", threads=1, numbered=False)
        buf = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            with (
                mock.patch.object(pd.shutil, "which", return_value="yt-dlp"),
                mock.patch.object(pd, "js_runtime_args", return_value=[]),
                mock.patch.object(pd.subprocess, "run", return_value=fake_res),
                redirect_stdout(buf),
            ):
                got = pd.run_youtube(rows, dest, opts, dry=False)
            self.assertEqual(got, {})
            self.assertIn("Video unavailable", buf.getvalue())
            self.assertEqual((dest / ".not-found.txt").read_text(encoding="utf-8").strip(), "Artist - Track")


if __name__ == "__main__":
    unittest.main()
