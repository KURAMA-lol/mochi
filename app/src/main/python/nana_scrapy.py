# Scrapy (https://github.com/scrapy/scrapy) inside the app via Chaquopy.
# Twisted's reactor can only start once per process, so it lives on one background thread and every call
# schedules a crawl onto it. Returns JSON: {"ok": bool, "out": text}.
import json
import re
import threading
from concurrent.futures import Future
from urllib.parse import urlparse

_started = threading.Event()
_boot_lock = threading.Lock()
_runner = None
_state = {"err": None}


def _reactor_main():
    try:
        from twisted.internet import reactor
        from scrapy.crawler import CrawlerRunner
        from scrapy.settings import Settings
        global _runner
        s = Settings()
        s.set("TWISTED_REACTOR", None)
        s.set("LOG_ENABLED", False)
        s.set("TELNETCONSOLE_ENABLED", False)
        s.set("ROBOTSTXT_OBEY", True)
        s.set("DOWNLOAD_TIMEOUT", 15)
        s.set("RETRY_TIMES", 1)
        s.set("USER_AGENT", "Mozilla/5.0 (Linux; Android 14) NanaScrapy/1.0")
        s.set("REQUEST_FINGERPRINTER_IMPLEMENTATION", "2.7")
        _runner = CrawlerRunner(s)
        reactor.callWhenRunning(_started.set)
        reactor.run(installSignalHandlers=False)
    except BaseException as e:
        _state["err"] = "%s: %s" % (type(e).__name__, e)
        _started.set()


def _boot():
    with _boot_lock:
        if _started.is_set():
            return
        threading.Thread(target=_reactor_main, name="nana-scrapy", daemon=True).start()
        _started.wait(20)


def _sel(resp, spec):
    spec = spec.strip()
    if spec.startswith("xpath:"):
        return resp.xpath(spec[6:].strip()).getall()
    if spec.startswith("css:"):
        spec = spec[4:].strip()
    return resp.css(spec).getall()


def _make_spider(url, fields, follow, max_pages, items):
    import scrapy
    host = urlparse(url).netloc
    follow_re = re.compile(follow) if follow else None

    class NanaSpider(scrapy.Spider):
        name = "nana"
        start_urls = [url]
        custom_settings = {"CLOSESPIDER_PAGECOUNT": max_pages, "DEPTH_LIMIT": 2 if follow_re else 0}
        seen = 0

        def parse(self, response):
            if len(items) < max_pages:
                row = {"url": response.url}
                if fields:
                    for k, spec in fields.items():
                        vals = [v.strip() for v in _sel(response, str(spec)) if v and v.strip()]
                        row[k] = vals[0] if len(vals) == 1 else vals[:40]
                else:
                    row["title"] = (response.css("title::text").get() or "").strip()
                    row["headings"] = [h.strip() for h in response.css("h1::text,h2::text,h3::text").getall() if h.strip()][:20]
                    txt = " ".join(t.strip() for t in response.xpath("//body//text()[not(ancestor::script) and not(ancestor::style)]").getall() if t.strip())
                    row["text"] = txt[:1500]
                items.append(row)
            if follow_re:
                for href in response.css("a::attr(href)").getall():
                    nxt = response.urljoin(href)
                    if urlparse(nxt).netloc == host and follow_re.search(nxt):
                        yield response.follow(nxt, callback=self.parse)

    return NanaSpider


def run(args_json):
    try:
        a = json.loads(args_json)
        url = str(a.get("url", "")).strip()
        if not url.startswith("http"):
            return json.dumps({"ok": False, "out": "Give a full http(s) url."})
        fields = a.get("fields") or {}
        if isinstance(fields, str):
            fields = fields.strip()
            if fields.startswith("{"):
                fields = json.loads(fields)
            else:
                fields = dict(p.split("=", 1) for p in fields.replace("|", ";").split(";") if "=" in p)
        fields = {str(k).strip(): str(v).strip() for k, v in fields.items()}
        follow = str(a.get("follow") or "").strip()
        try:
            max_pages = max(1, min(15, int(a.get("max_pages") or 5)))
        except Exception:
            max_pages = 5
        if not follow:
            max_pages = 1
        _boot()
        if _state["err"]:
            return json.dumps({"ok": False, "out": "Scrapy could not start: " + _state["err"]})
        from twisted.internet import reactor
        items = []
        fut = Future()

        def start():
            try:
                crawler = _runner.create_crawler(_make_spider(url, fields, follow, max_pages, items))
                d = _runner.crawl(crawler)
                d.addCallbacks(lambda r: fut.set_result(True), lambda f: fut.set_exception(RuntimeError(f.getErrorMessage())))
            except BaseException as e:
                fut.set_exception(e)

        reactor.callFromThread(start)
        fut.result(timeout=75)
        if not items:
            return json.dumps({"ok": True, "out": "Page loaded but nothing matched. Check the selectors, or the page needs JavaScript."})
        return json.dumps({"ok": True, "out": json.dumps(items, ensure_ascii=False)[:9000]})
    except BaseException as e:
        return json.dumps({"ok": False, "out": "%s: %s" % (type(e).__name__, e)})
