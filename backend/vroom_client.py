"""VROOM + OSRM client — sync httpx with 10s timeout and 1 retry, maps errors to 502."""

from __future__ import annotations

import httpx
from fastapi import HTTPException

VROOM_URL = "http://localhost:3000"
OSRM_URL = "http://localhost:5000"
TIMEOUT = 10.0
# Loop 2: connection pooling limits — per-request client reuse hint; TODO lifespan shared client
CLIENT_LIMITS = httpx.Limits(max_keepalive_connections=5, max_connections=10)


def _is_retryable(exc: Exception) -> bool:
    return isinstance(exc, (httpx.ConnectError, httpx.TimeoutException, httpx.ReadTimeout, httpx.ConnectTimeout, httpx.NetworkError))


def optimize_via_vroom(jobs: list[dict], start: list[float] | None = None, transport: httpx.BaseTransport | None = None) -> dict:
    """POST jobs to VROOM localhost:3000 with 10s timeout and 1 retry.

    Args:
        jobs: list of {id, location:[lng,lat]}
        start: optional [lng,lat] depot; defaults to first job location
        transport: optional httpx transport (MockTransport for tests)

    Returns:
        parsed JSON from VROOM

    Raises:
        HTTPException 502 on second failure or non-2xx status
    """
    if not jobs:
        raise HTTPException(status_code=400, detail="No jobs provided")
    depot = start if start is not None else jobs[0]["location"]
    payload = {
        "jobs": jobs,
        "vehicles": [{"id": 0, "profile": "car", "start": depot, "end": depot}],
    }
    last_exc: Exception | None = None
    for attempt in range(2):
        try:
            with httpx.Client(timeout=TIMEOUT, limits=CLIENT_LIMITS, transport=transport) as client:
                resp = client.post(f"{VROOM_URL}/", json=payload)
                resp.raise_for_status()
                data = resp.json()
                if data.get("code") is not None and data.get("code") != 0:
                    raise HTTPException(status_code=502, detail=f"VROOM error code {data.get('code')}: {data.get('error')}")
                return data
        except HTTPException:
            raise
        except httpx.HTTPStatusError as exc:
            raise HTTPException(status_code=502, detail=f"VROOM error: {exc.response.status_code} {exc.response.text[:200]}") from exc
        except Exception as exc:  # noqa: BLE001
            last_exc = exc
            if attempt == 1 or not _is_retryable(exc):
                # if not retryable, fail immediately as 502
                if not _is_retryable(exc):
                    raise HTTPException(status_code=502, detail=f"VROOM request failed: {exc}") from exc
                raise HTTPException(status_code=502, detail=f"VROOM unavailable after retry: {exc}") from exc
            continue
    raise HTTPException(status_code=502, detail=f"VROOM unavailable: {last_exc}")


def fetch_geometry(coords: list[list[float]], transport: httpx.BaseTransport | None = None) -> dict:
    """GET geometry polyline from OSRM :5000.

    Args:
        coords: list of [lng,lat] in route order
        transport: optional MockTransport

    Returns:
        dict with geometry (geojson) and distance/duration passthrough

    Raises:
        HTTPException 502 on failure
    """
    if len(coords) < 2:
        return {"geometry": None, "distance": 0, "duration": 0}
    coord_str = ";".join(f"{lng},{lat}" for lng, lat in coords)
    url = f"{OSRM_URL}/route/v1/driving/{coord_str}?overview=full&geometries=geojson"
    last_exc: Exception | None = None
    for attempt in range(2):
        try:
            with httpx.Client(timeout=TIMEOUT, limits=CLIENT_LIMITS, transport=transport) as client:
                resp = client.get(url)
                resp.raise_for_status()
                data = resp.json()
                if data.get("code") != "Ok":
                    raise HTTPException(status_code=502, detail=f"OSRM error: {data.get('code')}")
                route = (data.get("routes") or [{}])[0]
                return {
                    "geometry": route.get("geometry"),
                    "distance": route.get("distance", 0),
                    "duration": route.get("duration", 0),
                }
        except HTTPException:
            raise
        except httpx.HTTPStatusError as exc:
            raise HTTPException(status_code=502, detail=f"OSRM error: {exc.response.status_code}") from exc
        except Exception as exc:  # noqa: BLE001
            last_exc = exc
            if attempt == 1 or not _is_retryable(exc):
                if not _is_retryable(exc):
                    raise HTTPException(status_code=502, detail=f"OSRM request failed: {exc}") from exc
                raise HTTPException(status_code=502, detail=f"OSRM unavailable after retry: {exc}") from exc
            continue
    raise HTTPException(status_code=502, detail=f"OSRM unavailable: {last_exc}")


def check_vroom(transport: httpx.BaseTransport | None = None, timeout: float = 2.0) -> str:
    """Lightweight VROOM health probe — returns 'up' or 'down', never raises."""
    try:
        with httpx.Client(timeout=timeout, limits=CLIENT_LIMITS, transport=transport) as client:
            resp = client.get(f"{VROOM_URL}/health")
            if resp.status_code == 200:
                return "up"
            # VROOM may not have /health, try POST empty or GET /
            resp2 = client.get(f"{VROOM_URL}/")
            return "up" if resp2.status_code < 500 else "down"
    except Exception:
        return "down"


def check_osrm(transport: httpx.BaseTransport | None = None, timeout: float = 2.0) -> str:
    """Lightweight OSRM health probe."""
    try:
        with httpx.Client(timeout=timeout, limits=CLIENT_LIMITS, transport=transport) as client:
            resp = client.get(f"{OSRM_URL}/route/v1/driving/0,0;0,0?overview=false")
            # OSRM returns 400 for invalid but still up
            return "up" if resp.status_code < 500 else "down"
    except Exception:
        return "down"
