"""
Find My iPhone ("FMIP"): the service iCloud.com and Apple's Find My app locate devices through.

**A second source of location, beside the crowd-sourced reports.** Network reports exist only
when a stranger's iPhone hears an accessory away from its owner, and AirPods near their owner's
own iPad or iPhone are never reported that way. The owner's device reports them instead, to this
service, with an accuracy and a timestamp - which is why iCloud.com shows AirPods precisely and
recently while the network has nothing. Play Sound on AirPods goes the same way: Apple relays it
to the owner's device, which plays it over its own Bluetooth link, one earbud or the case at a
time.

**So it only knows what a signed-in Apple device tells it.** Once no Apple device on the account
can hear the AirPods, what this returns stops moving, and the network reports are all there is.

The protocol follows OpenBubbles/rustpush (``findmy.rs``, ``FindMyPhoneClient``) and the shapes
Go-iClient documents for ``playSound``: ``initClient`` once, ``refreshClient`` after, each
returning the device list and a server context to echo back. Authenticated with the account's
``dsid`` and its ``mmeFMIPAppToken``, which the mobileme sign-in already holds.

**What is logged:** statuses, counts, field names, product names and ages. Never a device id, a
person's device name, a token or a coordinate.
"""

from __future__ import annotations

import json
import time
from typing import Any

_HOST = "fmipmobile.icloud.com"

# The Find My app on the macOS release the emulated Mac claims (rule 11): bundle and agent are
# composed with the account's own identity, never written out with a model of their own.
_CLIENT_BUNDLE = "com.apple.AuthKit/1 (com.apple.findmy/3.0)"
_USER_AGENT_PRODUCT = "FindMy/3.0"

_DEVICES_FRESH_FOR_S = 60
"""
How long one device list answers for. The app's refresh asks once per tag, and a set of AirPods
is three tags, so without this one refresh would ask Apple three times for the same list.
"""

_BACK_OFF_S = 600
"""After Apple refuses, how long not to ask again on an automatic refresh."""


def _stateFor(account: Any) -> dict[str, Any]:
    """
    Host, serverContext, devices, fetchedAt and refusedAt - kept on the account object itself.

    Not in a table keyed by ``id(account)``: an id is reused once its object is collected, so a
    replaced account would inherit the last one's device list and server context.
    """
    state = getattr(account, "_opentagviewerFmip", None)
    if state is None:
        state = {}
        account._opentagviewerFmip = state
    return state


def _asyncAccount(account: Any) -> Any:
    return getattr(account, "_asyncacc", None) or account


def _loopOf(account: Any) -> Any:
    return getattr(account, "_evt_loop", None) or getattr(account, "_loop", None)


async def _headers(asyncacc: Any) -> dict[str, str]:
    identity = asyncacc.identity
    headers = dict(await asyncacc.get_anisette_headers())
    headers.update({
        "User-Agent": identity.user_agent(_USER_AGENT_PRODUCT),
        "X-Mme-Client-Info": identity.client_info(_CLIENT_BUNDLE),
        "X-Apple-Realm-Support": "1.0",
        "X-Apple-AuthScheme": "Forever",
        "X-Apple-Find-API-Ver": "3.0",
        "X-Apple-I-Locale": "en_US",
        "Accept": "application/json",
        "Accept-Language": "en-US,en;q=0.9",
    })
    return headers


def _clientContext(asyncacc: Any) -> dict[str, Any]:
    identity = asyncacc.identity
    return {
        "appName": "FindMy",
        "appVersion": "3.0",
        "apiVersion": "3.0",
        "clientTimestamp": int(time.time() * 1000),
        "deviceListVersion": 1,
        "fmly": True,
        "inactiveTime": 0,
        "osVersion": identity.os_version,
        "productType": identity.model,
        "push": False,
        "shouldLocate": True,
        "windowVisible": True,
    }


def _header(headers: Any, name: str) -> str | None:
    for key, value in (headers or {}).items():
        if key.lower() == name.lower():
            return value
    return None


class FmipError(Exception):
    """Apple answered, and not with a device list."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


async def _send(asyncacc: Any, state: dict, action: str, body: dict, token: str) -> Any:
    """POST, following the one 330 Apple answers with the account's partition the first time."""
    host = state.get("host") or _HOST
    for _ in range(2):
        url = f"https://{host}/fmipservice/device/{asyncacc.dsid}/{action}"
        resp = await asyncacc._http.post(
            url, auth=(asyncacc.dsid, token), headers=await _headers(asyncacc), json=body)
        if resp.status_code != 330:
            state["host"] = host
            return resp
        moved = _header(resp.headers, "X-Apple-MMe-Host")
        if not moved or moved == host:
            break
        print(f"FMIP {action}: 330, moving to the account's partition")
        host = moved
    raise FmipError(330, f"FMIP {action} was redirected without a usable host")


async def _post(account: Any, action: str, extra: dict[str, Any] | None = None) -> dict:
    asyncacc = _asyncAccount(account)
    state = _stateFor(account)
    token = asyncacc.service_tokens.get("mmeFMIPAppToken")
    if not token:
        raise FmipError(0, "the session holds no mmeFMIPAppToken")

    body: dict[str, Any] = {"clientContext": _clientContext(asyncacc)}
    if state.get("serverContext") is not None:
        body["serverContext"] = state["serverContext"]
    body.update(extra or {})

    resp = await _send(asyncacc, state, action, body, token)
    if resp.status_code != 200:
        raise FmipError(resp.status_code, f"FMIP {action} answered HTTP {resp.status_code}")
    try:
        data = resp.json()
    except Exception as e:
        raise FmipError(resp.status_code, f"FMIP {action} answered something not JSON") from e

    if not isinstance(data, dict):
        return {}
    if data.get("serverContext") is not None:
        state["serverContext"] = data["serverContext"]
    if isinstance(data.get("content"), list):
        state["devices"] = data["content"]
        state["fetchedAt"] = time.monotonic()
    return data


def _run(account: Any, coro: Any) -> Any:
    loop = _loopOf(account)
    if loop is None or loop.is_closed():
        coro.close()
        raise FmipError(0, "the account has no usable event loop")
    return loop.run_until_complete(coro)


def devices(account: Any, *, userAsked: bool = False) -> list[dict]:
    """
    The account's device list, from FMIP, at most a minute old.

    Empty, never an exception, when the service cannot be reached: this is an extra source, and
    a refresh that has network reports must not fail because this one did. After a refusal it
    stays quiet for a while on automatic refreshes, rather than asking again every minute.
    """
    state = _stateFor(account)
    now = time.monotonic()
    if state.get("devices") is not None and now - state.get("fetchedAt", 0) < _DEVICES_FRESH_FOR_S:
        return state["devices"]
    if not userAsked and now - state.get("refusedAt", -_BACK_OFF_S) < _BACK_OFF_S:
        return state.get("devices") or []

    action = "refreshClient" if state.get("serverContext") is not None else "initClient"
    try:
        _run(account, _post(account, action))
    except Exception as e:
        state["refusedAt"] = now
        print(f"FMIP {action} failed, using network reports only: {type(e).__name__}: {e}")
        return state.get("devices") or []

    found = state.get("devices") or []
    print(f"FMIP {action}: {len(found)} device(s)")
    for i, device in enumerate(found, 1):
        print(f"  FMIP device#{i}: {_describe(device)}")
    return found


def _describe(device: dict) -> str:
    """Product names, classes, flags and ages - never an id, a person's name or a coordinate."""
    location = device.get("location") or {}
    age = _ageMinutes(location)
    channels = [c.get("name") for c in device.get("audioChannels") or [] if isinstance(c, dict)]
    return (f"display={device.get('deviceDisplayName')!r} class={device.get('deviceClass')!r} "
            f"model={device.get('rawDeviceModel')!r} accessory={device.get('isConsideredAccessory')} "
            f"withYou={device.get('deviceWithYou')} location={'yes' if location else 'no'}"
            + (f" age={age}min accuracy={round(location.get('horizontalAccuracy') or 0)}m"
               f" old={location.get('isOld')} finished={location.get('locationFinished')}"
               if location else "")
            + f" channels={channels} battery={device.get('batteryLevel')}"
            f" fields={sorted(device)}")


def _ageMinutes(location: dict) -> int | None:
    stamp = location.get("timeStamp") or location.get("timestamp")
    if not isinstance(stamp, int | float):
        return None
    return max(0, int((time.time() * 1000 - stamp) // 60000))


def _idsOf(device: dict) -> set[str]:
    return {str(device[k]).upper() for k in ("id", "baUUID", "deviceDiscoveryId")
            if device.get(k)}


def _isAirPods(text: Any) -> bool:
    return isinstance(text, str) and "airpods" in text.lower()


def match(found: list[dict], accessory: dict) -> tuple[dict | None, str]:
    """
    Which FMIP device a stored accessory is, and by what evidence.

    In order of how much it proves: an identifier in common; the same name; the only device of
    the same model. AirPods are one device to FMIP but three tags here, so all three parts match
    the one set, and that is right - it is one location.
    """
    wanted = {str(accessory[k]).upper() for k in ("identifier", "group_identifier")
              if accessory.get(k)}
    for device in found:
        if wanted & _idsOf(device):
            return device, "identifier"

    name = accessory.get("name")
    if name:
        named = [d for d in found if d.get("name") == name]
        if len(named) == 1:
            return named[0], "name"

    model = accessory.get("model") or ""
    if _isAirPods(model):
        airpods = [d for d in found
                   if _isAirPods(d.get("deviceDisplayName")) or _isAirPods(d.get("modelDisplayName"))
                   or _isAirPods(d.get("deviceModel"))]
        if len(airpods) == 1:
            return airpods[0], "only AirPods on the account"
    elif model:
        same = [d for d in found if d.get("rawDeviceModel") == model]
        if len(same) == 1:
            return same[0], "only device of this model"

    return None, "no match"


def reportFor(device: dict) -> dict | None:
    """A device's FMIP location, in the shape ``main._serializeReports`` produces."""
    location = device.get("location") or {}
    stamp = location.get("timeStamp") or location.get("timestamp")
    lat, lon = location.get("latitude"), location.get("longitude")
    if not isinstance(stamp, int | float) or lat is None or lon is None:
        return None
    return {
        "publishedAt": int(stamp),
        "description": "Find My (iCloud)",
        "timestamp": int(stamp),
        "confidence": 3,
        "latitude": float(lat),
        "longitude": float(lon),
        "horizontalAccuracy": int(round(location.get("horizontalAccuracy") or 0)),
        "status": 0,
    }


def locationsFor(account: Any, accessories: dict[str, dict]) -> dict[str, dict]:
    """beaconId -> report, for every stored accessory FMIP can place."""
    found = devices(account)
    if not found:
        return {}
    placed = {}
    for beaconId, accessory in accessories.items():
        device, how = match(found, accessory)
        report = reportFor(device) if device else None
        print(f"  FMIP for {beaconId}: {how}" + ("" if device is None else
              f", location {'none' if report is None else 'present'}"))
        if report is not None:
            placed[beaconId] = report
    return placed


def _part(accessory: dict) -> str | None:
    name = (accessory.get("name") or "").lower()
    for part in ("left", "right", "case"):
        if part in name:
            return part
    return None


def playSound(account: Any, accessory: dict) -> dict[str, Any]:
    """
    Ask Apple to play a sound on this accessory, the way iCloud.com does.

    :return: ``{"ok": bool, "message": str}``. Never raises.
    """
    try:
        found = devices(account, userAsked=True)
        device, how = match(found, accessory)
        if device is None:
            return {"ok": False, "message": f"iCloud lists no device for this ({how})"}

        available = [str(c["name"]) for c in device.get("audioChannels") or []
                     if isinstance(c, dict) and c.get("name")]
        part = _part(accessory)
        if part is None:
            channels = available
        else:
            channels = [c for c in available if c.lower() == part] or [part]

        extra: dict[str, Any] = {"device": device.get("id"), "userAction": "PlaySound"}
        if channels:
            extra["channels"] = channels
            extra["subject"] = "Find My Accessory Alert"
        print(f"FMIP playSound: matched by {how}; part={part} channels={channels} "
              f"of available={available}")

        data = _run(account, _post(account, "playSound", extra))
        snd = next((d.get("snd") for d in data.get("content") or []
                    if d.get("id") == device.get("id")), None)
        status = (snd or {}).get("statusCode") if isinstance(snd, dict) else None
        print(f"FMIP playSound answered; snd.statusCode={status!r}")
        return {"ok": True, "message": f"sent through iCloud to {channels or 'the device'}"}
    except FmipError as e:
        print(f"FMIP playSound failed: HTTP {e.status}: {e}")
        return {"ok": False, "message": str(e)}
    except Exception as e:
        print(f"FMIP playSound failed: {type(e).__name__}: {e}")
        return {"ok": False, "message": f"{type(e).__name__}: {e}"}


def playSoundJson(account: Any, accessoryJson: str) -> str:
    """Java's entry point: JSON in, JSON out."""
    try:
        accessory = json.loads(accessoryJson)
    except (TypeError, ValueError):
        return json.dumps({"ok": False, "message": "unreadable accessory"})
    return json.dumps(playSound(account, accessory))
