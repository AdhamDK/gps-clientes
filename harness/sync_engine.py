"""sync(db_path, gas_url) — reads sync_status=0, POSTs array to GAS, marks synced if status success."""
import re
import sqlite3
import httpx

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

def sync(db_path: str, gas_url: str) -> dict:
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    cur = con.cursor()
    try:
        rows = cur.execute("SELECT * FROM clientes WHERE sync_status=0 AND deleted=0").fetchall()
    except sqlite3.OperationalError:
        rows = cur.execute("SELECT * FROM clientes WHERE sync_status=0").fetchall()
    if not rows:
        con.close()
        return {"synced": 0, "total": 0}
    payload = [dict(r) for r in rows]
    for c in payload:
        if not UUID_RE.match(str(c["id"]).lower()):
            con.close()
            raise ValueError(f"invalid UUID: {c['id']}")
    # spec: POST array JSON with Content-Type application/json
    resp = httpx.post(gas_url, json=payload, headers={"Content-Type": "application/json"}, timeout=5.0)
    resp.raise_for_status()
    data = resp.json()
    # spec expects status success ; accept ok for compat
    if data.get("status") in ("success", "ok"):
        ids = [r["id"] for r in rows]
        cur.executemany("UPDATE clientes SET sync_status=1 WHERE id=?", [(i,) for i in ids])
        con.commit()
    con.close()
    return {"synced": data.get("synced", len(rows)) if data.get("status") in ("success", "ok") else 0, "total": len(rows)}
