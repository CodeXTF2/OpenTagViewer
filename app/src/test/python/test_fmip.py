"""FMIP: matching iCloud's device list to stored accessories, and what goes into a ring."""

import json
import time

import fmip

AIRPODS = {"deviceDisplayName": "AirPods Pro", "id": "fmip-airpods", "name": "Someone's AirPods",
           "audioChannels": [{"name": "left"}, {"name": "right"}, {"name": "case"}],
           "location": {"timeStamp": 1_700_000_000_000, "latitude": 1.5, "longitude": 2.5,
                        "horizontalAccuracy": 12.4}}
IPAD = {"deviceDisplayName": "iPad", "rawDeviceModel": "iPad12,1", "id": "fmip-ipad",
        "name": "An iPad", "location": None}


def part(name, **extra):
    return {"model": "AirPods Pro (3rd generation)", "name": name, "identifier": "B-1", **extra}


def test_an_identifier_in_common_wins():
    device, how = fmip.match([IPAD, {**AIRPODS, "baUUID": "g-1"}], part("x", group_identifier="G-1"))
    assert device["id"] == "fmip-airpods"
    assert how == "identifier"


def test_every_part_of_a_set_finds_the_one_set():
    for name in ("Left", "Right", "Case"):
        device, _ = fmip.match([IPAD, AIRPODS], part(name))
        assert device is AIRPODS


def test_two_sets_of_airpods_are_not_guessed_between():
    device, how = fmip.match([AIRPODS, {**AIRPODS, "id": "other"}], part("Left"))
    assert device is None
    assert how == "no match"


def test_an_ipad_matches_by_its_model():
    device, _ = fmip.match([IPAD, AIRPODS], {"model": "iPad12,1", "name": "x"})
    assert device is IPAD


def test_a_location_becomes_a_report_the_bridge_already_serialises():
    report = fmip.reportFor(AIRPODS)
    assert report == {
        "publishedAt": 1_700_000_000_000, "description": "Find My (iCloud)",
        "timestamp": 1_700_000_000_000, "confidence": 3, "latitude": 1.5, "longitude": 2.5,
        "horizontalAccuracy": 12, "status": 0,
    }
    assert fmip.reportFor(IPAD) is None


class _Account:
    """Stands in for the sync AppleAccount; the HTTP layer is replaced below."""


def _withDevices(monkeypatch, devices):
    sent = []

    async def post(account, action, extra=None):
        sent.append((action, extra))
        state = fmip._stateFor(account)
        state["devices"], state["fetchedAt"], state["serverContext"] = devices, time.monotonic(), {}
        return {"content": devices}

    def run(account, coro):
        import asyncio
        return asyncio.new_event_loop().run_until_complete(coro)

    monkeypatch.setattr(fmip, "_post", post)
    monkeypatch.setattr(fmip, "_run", run)
    return sent


def test_ringing_the_case_asks_for_the_case_channel_only(monkeypatch):
    sent = _withDevices(monkeypatch, [AIRPODS])
    answer = json.loads(fmip.playSoundJson(_Account(), json.dumps(part("Someone's AirPods Case"))))

    assert answer["ok"] is True
    action, extra = sent[-1]
    assert action == "playSound"
    assert extra["channels"] == ["case"]
    assert extra["device"] == "fmip-airpods"
    assert extra["userAction"] == "PlaySound"


def test_a_part_with_no_side_in_its_name_rings_every_channel(monkeypatch):
    sent = _withDevices(monkeypatch, [AIRPODS])
    fmip.playSound(_Account(), part("AirPods"))
    assert sent[-1][1]["channels"] == ["left", "right", "case"]


def test_no_matching_device_is_a_refusal_not_an_exception(monkeypatch):
    _withDevices(monkeypatch, [IPAD])
    answer = fmip.playSound(_Account(), part("Left"))
    assert answer["ok"] is False


def test_a_refresh_does_not_ask_apple_again_within_a_minute(monkeypatch):
    sent = _withDevices(monkeypatch, [AIRPODS])
    account = _Account()
    fmip.devices(account)
    fmip.devices(account)
    assert [a for a, _ in sent] == ["initClient"]


def test_a_failure_backs_off_on_automatic_refreshes(monkeypatch):
    calls = []

    async def post(account, action, extra=None):
        calls.append(action)
        raise fmip.FmipError(403, "refused")

    def run(account, coro):
        import asyncio
        return asyncio.new_event_loop().run_until_complete(coro)

    monkeypatch.setattr(fmip, "_post", post)
    monkeypatch.setattr(fmip, "_run", run)
    account = _Account()
    assert fmip.devices(account) == []
    assert fmip.devices(account) == []
    assert len(calls) == 1
    fmip.devices(account, userAsked=True)
    assert len(calls) == 2
