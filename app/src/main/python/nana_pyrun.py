# Python and pip for Nana's terminal. Runs inside the app (Chaquopy), only inside her scratch folder.
import builtins
import contextlib
import ctypes
import importlib.util
import io
import json
import os
import re
import sys
import threading
import traceback
import urllib.request
import zipfile

_lock = threading.Lock()
_BAD = re.compile(r"shared_prefs|databases|/data/data|/data/user|nana_mail|agent_cfg", re.I)


def _lib(home):
    d = os.path.join(home, "pylib")
    os.makedirs(d, exist_ok=True)
    if d not in sys.path:
        sys.path.insert(0, d)
    return d


def _guard_open(home):
    real = os.path.realpath(home)
    orig = builtins.open

    def safe(file, *a, **k):
        if isinstance(file, (str, bytes, os.PathLike)):
            p = os.path.realpath(os.path.join(real, os.fsdecode(file)))
            if not (p == real or p.startswith(real + os.sep)):
                raise PermissionError("Nana's Python can only open files inside her scratch folder")
        return orig(file, *a, **k)

    return safe


def run(code, home, timeout=30):
    os.makedirs(home, exist_ok=True)
    _lib(home)
    if _BAD.search(code):
        return "Blocked: that code reaches the app's private data."
    timeout = max(1, min(int(timeout), 120))
    out = io.StringIO()
    g = {"__name__": "__main__", "__builtins__": dict(vars(builtins), open=_guard_open(home))}

    def work():
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
                exec(compile(code, "<nana>", "exec"), g)
        except SystemExit:
            pass
        except BaseException:
            out.write(traceback.format_exc()[-1500:])

    with _lock:
        old = os.getcwd()
        os.chdir(home)
        try:
            t = threading.Thread(target=work, daemon=True)
            t.start()
            t.join(timeout)
            if t.is_alive():
                ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(t.ident), ctypes.py_object(KeyboardInterrupt))
                t.join(3)
                out.write("\n[stopped: took longer than %ss]" % timeout)
        finally:
            os.chdir(old)
    return out.getvalue()[-6000:] or "(no output)"


def pip(spec, home):
    """Installs a pure-Python wheel (and its plain dependencies) from PyPI into the scratch folder."""
    lib = _lib(home)
    log = []
    seen = set()

    def inst(name, depth):
        key = re.split(r"[<>=!~;\[\s(]", name.strip(), 1)[0].lower().replace("_", "-")
        if not key or key in seen or depth > 3:
            return
        seen.add(key)
        if importlib.util.find_spec(key.replace("-", "_")) is not None:
            log.append("%s: already available" % key)
            return
        try:
            info = json.load(urllib.request.urlopen("https://pypi.org/pypi/%s/json" % key, timeout=20))
        except Exception as e:
            log.append("%s: not found on PyPI (%s)" % (key, str(e)[:60]))
            return
        wheels = [f for f in info["urls"] if f.get("packagetype") == "bdist_wheel" and f["filename"].endswith("none-any.whl")]
        if not wheels:
            log.append("%s: no pure-Python wheel, so it needs native code and cannot be installed here" % key)
            return
        data = urllib.request.urlopen(wheels[0]["url"], timeout=90).read()
        zipfile.ZipFile(io.BytesIO(data)).extractall(lib)
        log.append("installed %s %s" % (key, info["info"]["version"]))
        for r in info["info"].get("requires_dist") or []:
            if ";" in r:
                continue
            inst(r, depth + 1)

    with _lock:
        inst(spec, 0)
    importlib.invalidate_caches()
    return "\n".join(log) or "nothing to do"
