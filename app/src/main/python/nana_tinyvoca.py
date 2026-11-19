# tiny-voca front end for Nana: text -> phoneme ids + stress, sentence by sentence.
# The ONNX model itself runs in Kotlin (onnxruntime-android). This file ports the text handling of
# tiny_voca.infer (sentences, finished, shorter) and drives the dizione phonemizer (Apache-2.0).
import json
import os
import re
import sys

_dz = None
_SPACE = re.compile("[\\s\ufeff]+")
_SENTENCE_END = re.compile(r"""(?:(?<=[.!?\u2026])|(?<=[.!?\u2026]["')\]]))\s+(?=["'(\[]?[A-Z0-9])""")
_NOT_AN_END = re.compile(
    r"(?:\b(?:mr|mrs|ms|dr|prof|sr|jr|st|vs|etc|no|inc|ltd|co|e\.g|i\.e|a\.m|p\.m)|\b[A-Z])\.$",
    re.IGNORECASE | re.ASCII)
_CLAUSE_END = re.compile(r"(?<=[,;:\u2014])\s+")
_TEXT_END = re.compile("[\\s\ufeff]+$")
_CLOSING = "\"'\u201d\u2019)]\u00bb"


def load(src_dir, data_dir):
    """Starts dizione from real files: src_dir holds the dizione package, data_dir the phonemizer data."""
    global _dz
    try:
        os.environ["DIZIONE_DATA"] = data_dir
        if src_dir not in sys.path:
            sys.path.insert(0, src_dir)
        import dizione
        dizione.reset()
        dizione.phonemize("Warm up.")
        _dz = dizione
        return "ok"
    except Exception as e:
        return "%s: %s" % (type(e).__name__, e)


def sentences(text):
    parts, current = [], ""
    for piece in _SENTENCE_END.split(_SPACE.sub(" ", text).strip()):
        current = (current + " " + piece) if current else piece
        if not _NOT_AN_END.search(current):
            parts.append(current)
            current = ""
    if current:
        parts.append(current)
    return [p for p in parts if any(ch.isalnum() for ch in p)]


def finished(text):
    """Adds a full stop when the text ends without a mark (the voice needs it to end the last word)."""
    body = _TEXT_END.sub("", text)
    if not body:
        return text
    last = _SPACE.split(body)[-1]
    if any(ch.isalnum() for ch in last):
        marked = last.rstrip(_CLOSING)[-1:] in tuple(".!?\u2026,;:")
    else:
        marked = any(ch in ".!?\u2026" for ch in last) or last in (",", ";", ":")
    return text if marked else body + "."


def _phones(piece):
    try:
        return _dz.phonemize(piece)
    except ValueError:
        # Nothing to say (other script, a face, signs only): skipped. A real word that failed is skipped too.
        return None


def plan(text, limit=220):
    """JSON list of {"ids": [...], "stress": [...]}: one entry per utterance the model should speak.
    A sentence longer than `limit` phonemes is cut after commas, then between words. Empty list = nothing to read."""
    if _dz is None:
        raise RuntimeError("tiny-voca phonemizer is not loaded")
    out = []

    def fit(s):
        r = _phones(s)
        if r is None:
            return
        if len(r.ids) <= limit:
            out.append({"ids": [int(i) for i in r.ids], "stress": [int(i) for i in r.stress]})
            return
        pieces = _CLAUSE_END.split(s)
        if len(pieces) > 1:
            for p in pieces:
                fit(p)
            return
        words = s.split(" ")
        if len(words) < 2:
            return
        half = (len(words) + 1) // 2
        fit(" ".join(words[:half]))
        fit(" ".join(words[half:]))

    for s in sentences(finished(text)):
        fit(s)
    return json.dumps(out)
