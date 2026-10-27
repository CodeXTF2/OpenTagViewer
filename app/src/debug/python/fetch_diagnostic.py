"""
DIAGNOSTIC (AirPods Pro 3 import but never locate): verbose logging of the report fetch.

**Debug source set only**, like ``icloud_test_double``: Chaquopy compiles ``src/debug/python``
into the debug APK and into no release build. ``main`` imports it if it is there.

The phone drops log lines over its quota, so everything here goes to
``$HOME/icloud-diagnostic.log``, which the log export appends and redacts with the rest.

What it records, and what it never does:

* counts, statuses, index ranges, dates, model names, exception types and messages
* **never** a key, a hashed advertisement id, a token, a header, or a decrypted location
"""

from __future__ import annotations

import functools
import logging
import os
import sys
from datetime import datetime, timedelta

logger = logging.getLogger("fetch_diagnostic")

_REPORTS_ENDPOINT = "findmyservice/v2/fetch"
_installed = False


class _Tee:
    """Copies the app's own ``print`` output into the file, because logcat loses it."""

    def __init__(self, stream, path):
        self._stream = stream
        self._path = path

    def write(self, text):
        try:
            with open(self._path, "a", encoding="utf-8") as f:
                f.write(text)
        except OSError:
            pass
        return self._stream.write(text)

    def flush(self):
        return self._stream.flush()

    def __getattr__(self, name):
        return getattr(self._stream, name)


def _describeRequest(json_body) -> str:
    fetches = (json_body or {}).get("fetch", [])
    parts = []
    for f in fetches:
        start, end = f.get("startDate"), f.get("endDate")
        span_h = (end - start) / 3_600_000 if start is not None and end is not None else None
        parts.append(f"primary={len(f.get('primaryIds', []))} "
                     f"secondary={len(f.get('secondaryIds', []))} "
                     f"keyType={f.get('keyType')} span={span_h}h")
    return f"{len(fetches)} fetch group(s): " + "; ".join(parts)


def _describeResponse(resp) -> str:
    try:
        body = resp.json()
    except Exception as e:
        return f"status={resp.status_code} bytes={len(resp.content)} not JSON ({type(e).__name__})"
    acsn = body.get("acsnLocations", {}) if isinstance(body, dict) else {}
    payload = acsn.get("locationPayload", []) or []
    infos = sum(len(p.get("locationInfo", []) or []) for p in payload)
    return (f"status={resp.status_code} bytes={len(resp.content)} "
            f"top-level keys={sorted(body) if isinstance(body, dict) else type(body).__name__} "
            f"acsnLocations.statusCode={acsn.get('statusCode')!r} "
            f"top statusCode={body.get('statusCode') if isinstance(body, dict) else None!r} "
            f"ids with reports={len(payload)} reports={infos}")


def _describeAccessory(accessory) -> str:
    now = datetime.now().astimezone()
    try:
        mapping = accessory.to_json()
    except Exception:
        mapping = {}
    try:
        lo = accessory.get_min_index(now - timedelta(days=7))
        hi = accessory.get_max_index(now)
    except Exception as e:
        lo = hi = f"? ({type(e).__name__})"
    return (f"{type(accessory).__name__} model={getattr(accessory, 'model', None)!r} "
            f"paired_at={getattr(accessory, 'paired_at', None)} "
            f"alignment index={mapping.get('alignment_index')} date={mapping.get('alignment_date')} "
            f"7-day index window={lo}..{hi}")


def install() -> None:  # noqa: C901 - one wrapper per hook, temporary
    global _installed
    if _installed:
        return
    _installed = True

    try:
        path = os.path.join(os.environ["HOME"], "icloud-diagnostic.log")
        handler: logging.Handler = logging.FileHandler(path, mode="a", encoding="utf-8")
    except (KeyError, OSError) as e:
        print(f"DIAGNOSTIC: fetch diagnostics not installed: {e!r}", file=sys.stderr)
        return
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))

    # Our own lines, and FindMy.py's report fetcher ("Fetched %d new reports (index %i)").
    # Not findmy.reports.account: its INFO lines name the Apple ID.
    for name in ("fetch_diagnostic", "findmy.reports.reports", "findmy.accessory"):
        logging.getLogger(name).setLevel(logging.DEBUG)
        logging.getLogger(name).addHandler(handler)

    sys.stdout = _Tee(sys.stdout, path)

    from findmy.reports import reports as reports_module
    from findmy.reports.account import AsyncAppleAccount
    from findmy.util.http import HttpSession

    original_post = HttpSession.post

    @functools.wraps(original_post)
    async def post(self, url, **kwargs):
        if _REPORTS_ENDPOINT not in url:
            return await original_post(self, url, **kwargs)
        logger.info("POST reports: %s", _describeRequest(kwargs.get("json")))
        try:
            resp = await original_post(self, url, **kwargs)
        except Exception as e:
            logger.info("POST reports raised %s: %s", type(e).__name__, e)
            raise
        logger.info("POST reports answered: %s", _describeResponse(resp))
        return resp

    HttpSession.post = post

    original_raw = AsyncAppleAccount.fetch_raw_reports

    @functools.wraps(original_raw)
    async def fetch_raw_reports(self, devices):
        # Names only: which Apple services this session holds a token for (FMIP is the one
        # iCloud.com locates AirPods and plays their sound through).
        try:
            logger.info("Session tokens held (names only): %s", sorted(self.service_tokens))
        except Exception as e:
            logger.info("Session tokens unreadable: %s", type(e).__name__)
        try:
            found = await original_raw(self, devices)
        except Exception as e:
            logger.info("fetch_raw_reports raised %s: %s", type(e).__name__, e)
            raise
        logger.info("fetch_raw_reports returned %d encrypted report(s)", len(found))
        return found

    AsyncAppleAccount.fetch_raw_reports = fetch_raw_reports

    fetcher = reports_module.LocationReportsFetcher
    original_accessory = fetcher._fetch_accessory_reports

    @functools.wraps(original_accessory)
    async def _fetch_accessory_reports(self, accessory, only_latest=False):
        logger.info("Accessory fetch start (only_latest=%s): %s",
                    only_latest, _describeAccessory(accessory))
        try:
            found = await original_accessory(self, accessory, only_latest=only_latest)
        except Exception as e:
            logger.info("Accessory fetch raised %s: %s", type(e).__name__, e)
            raise
        logger.info("Accessory fetch end: %d report(s); now %s",
                    len(found), _describeAccessory(accessory))
        return found

    fetcher._fetch_accessory_reports = _fetch_accessory_reports

    original_decrypt = reports_module.LocationReport.decrypt

    @functools.wraps(original_decrypt)
    def decrypt(self, key):
        try:
            return original_decrypt(self, key)
        except Exception as e:
            logger.info("Report decrypt failed (%s key): %s: %s",
                        getattr(getattr(key, "key_type", None), "name", "?"),
                        type(e).__name__, e)
            raise

    reports_module.LocationReport.decrypt = decrypt

    logger.info("Fetch diagnostics installed")
