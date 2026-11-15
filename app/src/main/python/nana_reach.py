# Bridge between Nana (Kotlin, via Chaquopy) and Agent-Reach.
# Plain-Python Agent-Reach channels go through its own cli.main(); YouTube goes straight to the yt_dlp API.
import contextlib
import io
import json
import os
import shlex
import sys
import threading
import urllib.request

_lock = threading.Lock()


def _clock(sec):
    sec = int(sec or 0)
    return "%d:%02d" % (sec // 60, sec % 60)


def _ydl(**opts):
    import yt_dlp
    base = {"quiet": True, "no_warnings": True, "skip_download": True, "noprogress": True}
    base.update(opts)
    return yt_dlp.YoutubeDL(base)


def _yt_read(target):
    with _ydl() as y:
        i = y.extract_info(target, download=False)
    lines = [
        "Title: %s" % i.get("title"),
        "Channel: %s" % (i.get("channel") or i.get("uploader")),
        "Date: %s" % i.get("upload_date"),
        "Length: %s" % _clock(i.get("duration")),
        "Views: %s" % i.get("view_count"),
        "Description: %s" % (i.get("description") or "")[:800],
    ]
    return "\n".join(lines)


def _yt_search(query, limit=5):
    with _ydl(extract_flat=True) as y:
        i = y.extract_info("ytsearch%d:%s" % (limit, query), download=False)
    rows = []
    for e in (i.get("entries") or [])[:limit]:
        rows.append("%s | %s | %s | %s" % (e.get("title"), e.get("channel") or e.get("uploader"), _clock(e.get("duration")), e.get("url")))
    return "\n".join(rows)


def _pick_caption(i, langs):
    for src in ("subtitles", "automatic_captions"):
        tracks = i.get(src) or {}
        for lang in langs:
            for key in tracks:
                if key == lang or key.startswith(lang):
                    for fmt in tracks[key]:
                        if fmt.get("ext") == "json3":
                            return key, fmt.get("url")
    return None, None


def _yt_subtitle(target, lang="en,en-orig"):
    with _ydl() as y:
        i = y.extract_info(target, download=False)
    key, url = _pick_caption(i, [x.strip() for x in lang.split(",") if x.strip()])
    if not url:
        return "No captions found for this video."
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    raw = urllib.request.urlopen(req, timeout=20).read().decode("utf-8", "replace")
    out = ["Captions (%s), [m:ss]:" % key]
    line, start = [], None
    for ev in json.loads(raw).get("events", []):
        segs = ev.get("segs")
        if not segs:
            continue
        t0 = ev.get("tStartMs", 0) // 1000
        if start is None:
            start = t0
        if t0 - start > 15 and line:
            out.append("[%s] %s" % (_clock(start), " ".join(line).strip()))
            line, start = [], t0
        line.append("".join(s.get("utf8", "") for s in segs).replace("\n", " "))
    if line:
        out.append("[%s] %s" % (_clock(start), " ".join(line).strip()))
    return "\n".join(out)


def _youtube(argv):
    action = argv[0] if argv else ""
    rest = argv[1:]
    if action == "read" and rest:
        return _yt_read(rest[0])
    if action == "search" and rest:
        return _yt_search(" ".join(a for a in rest if not a.startswith("--")))
    if action == "subtitle" and rest:
        lang = "en,en-orig"
        if "--lang" in rest:
            lang = rest[rest.index("--lang") + 1]
        return _yt_subtitle(rest[0], lang)
    raise ValueError("youtube actions: read <link>, search <query>, subtitle <link> [--lang en]")


_patched = False


def _relax_symlink_check(home):
    # Android app dirs live under symlinked parents (/data/user/0). Agent-Reach rejects any symlink
    # in the whole path, so only check the part below our private home folder.
    global _patched
    if _patched:
        return
    import stat
    from agent_reach.utils import paths
    base = os.path.abspath(home)

    def check(path, label="path"):
        target = os.fspath(path)
        absolute = os.path.abspath(target)
        if absolute != base and not absolute.startswith(base + os.sep):
            return paths._orig_check(path, label)
        cur = base
        for part in absolute[len(base):].split(os.sep):
            if not part:
                continue
            cur = os.path.join(cur, part)
            try:
                mode = os.lstat(cur).st_mode
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(mode):
                raise paths.PrivatePathError("symlink not allowed: " + cur)
        return type(path)(path) if not isinstance(path, str) else __import__("pathlib").Path(path)

    paths._orig_check = paths.ensure_no_symlink_path
    paths.ensure_no_symlink_path = check
    for m in list(sys.modules.values()):
        if m is not None and getattr(m, "__name__", "").startswith("agent_reach") and hasattr(m, "ensure_no_symlink_path"):
            m.ensure_no_symlink_path = check
    _patched = True


def run(command, home):
    os.makedirs(home, exist_ok=True)
    os.environ["HOME"] = home
    argv = shlex.split(command)
    out, err = io.StringIO(), io.StringIO()
    code = 0
    with _lock:
        try:
            if argv and argv[0] == "youtube":
                out.write(_youtube(argv[1:]))
            elif len(argv) > 1 and argv[0] == "read" and ("youtube.com" in argv[1] or "youtu.be" in argv[1]):
                out.write(_yt_read(argv[1]) + "\n\n" + _yt_subtitle(argv[1]))
            else:
                from agent_reach import cli
                _relax_symlink_check(home)
                old = sys.argv
                sys.argv = ["agent-reach"] + argv
                try:
                    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                        cli.main()
                except SystemExit as e:
                    code = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
                finally:
                    sys.argv = old
        except Exception as e:
            err.write("%s: %s" % (type(e).__name__, e))
            code = 1
    return json.dumps({"code": code, "out": out.getvalue(), "err": err.getvalue()[-2000:]})
