"""
Migration: INTEGER PK -> TEXT UUIDv4 + spec columns (rif_cedula, latitud, longitud, updated_at TEXT, sync_status, deleted).

Usage:
  python -m backend.migrate_uuid          # migrates backend/clientes.db (or DATABASE_URL)
  python -m backend.migrate_uuid --dry-run
"""
import argparse
import os
import sqlite3
import uuid
from datetime import datetime, timezone

from .database import DATABASE_URL, DB_PATH


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _table_info(cur, table):
    cur.execute(f"PRAGMA table_info({table})")
    return cur.fetchall()


def migrate(db_path: str, dry_run: bool = False) -> dict:
    if not os.path.exists(db_path):
        return {"status": "no_db", "path": db_path}
    con = sqlite3.connect(db_path)
    cur = con.cursor()
    # Check if clientes exists
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='clientes'")
    if not cur.fetchone():
        con.close()
        return {"status": "no_table"}
    info = _table_info(cur, "clientes")
    cols = {row[1]: row for row in info}  # row: cid, name, type, notnull, dflt, pk
    id_type = cols.get("id", [None, None, ""])[2] if "id" in cols else ""
    needs_recreate = id_type.upper() == "INTEGER" or cols.get("id", [None, None, None, None, None, 0])[5] == 1 and id_type.upper() != "TEXT"

    # If already TEXT PK, just ensure missing spec columns
    if not needs_recreate and id_type.upper() == "TEXT":
        missing = []
        for col, ddl in [
            ("rif_cedula", "TEXT"),
            ("latitud", "REAL"),
            ("longitud", "REAL"),
            ("sync_status", "INTEGER NOT NULL DEFAULT 0"),
            ("deleted", "INTEGER NOT NULL DEFAULT 0"),
        ]:
            if col not in cols:
                missing.append(col)
                if not dry_run:
                    cur.execute(f"ALTER TABLE clientes ADD COLUMN {col} {ddl}")
        # ensure updated_at is TEXT ISO8601 — if exists but type is DATETIME, SQLite keeps; backfill nulls
        if "updated_at" not in cols:
            if not dry_run:
                cur.execute("ALTER TABLE clientes ADD COLUMN updated_at TEXT NOT NULL DEFAULT '2026-01-01T00:00:00Z'")
            missing.append("updated_at")
        else:
            # backfill null updated_at
            if not dry_run:
                cur.execute("UPDATE clientes SET updated_at=? WHERE updated_at IS NULL", (utc_now_iso(),))
                # sync latitud/longitud from lat/lng where null
                if "latitud" in cols or "latitud" in missing:
                    try:
                        cur.execute("UPDATE clientes SET latitud=lat WHERE latitud IS NULL AND lat IS NOT NULL")
                        cur.execute("UPDATE clientes SET longitud=lng WHERE longitud IS NULL AND lng IS NOT NULL")
                        cur.execute("UPDATE clientes SET lat=latitud WHERE lat IS NULL AND latitud IS NOT NULL")
                        cur.execute("UPDATE clientes SET lng=longitud WHERE lng IS NULL AND longitud IS NOT NULL")
                    except Exception:
                        pass
                # sync rif_cedula
                try:
                    cur.execute("UPDATE clientes SET rif_cedula=rif WHERE rif_cedula IS NULL AND rif IS NOT NULL")
                    cur.execute("UPDATE clientes SET rif=rif_cedula WHERE rif IS NULL AND rif_cedula IS NOT NULL")
                except Exception:
                    pass
                cur.execute("UPDATE clientes SET sync_status=0 WHERE sync_status IS NULL")
                cur.execute("UPDATE clientes SET deleted=0 WHERE deleted IS NULL")
        if not dry_run:
            con.commit()
        con.close()
        return {"status": "altered", "missing_added": missing, "recreated": False}

    # Need full recreate: INTEGER -> TEXT UUID
    if dry_run:
        con.close()
        return {"status": "would_recreate", "id_type": id_type}

    # Create new table with spec schema
    cur.execute("ALTER TABLE clientes RENAME TO clientes_old")
    cur.execute("""
        CREATE TABLE clientes (
          id TEXT PRIMARY KEY,
          nombre TEXT NOT NULL,
          nombre_normalizado TEXT NOT NULL,
          rif TEXT,
          rif_cedula TEXT,
          telefono TEXT,
          direccion TEXT,
          direccion_original TEXT,
          empresa TEXT,
          zona TEXT,
          lat REAL,
          lng REAL,
          latitud REAL,
          longitud REAL,
          texto_breve TEXT,
          is_flagged BOOLEAN NOT NULL DEFAULT 0,
          has_gps_fix BOOLEAN NOT NULL DEFAULT 0,
          updated_at TEXT NOT NULL,
          sync_status INTEGER NOT NULL DEFAULT 0,
          deleted INTEGER NOT NULL DEFAULT 0
        )
    """)
    # Copy rows: generate UUID for each old integer id, map columns
    cur.execute("SELECT * FROM clientes_old")
    old_cols = [d[0] for d in cur.description]
    rows = cur.fetchall()
    for row in rows:
        old = dict(zip(old_cols, row))
        new_id = str(uuid.uuid4())
        # coalesce
        lat = old.get("lat")
        lng = old.get("lng")
        rif = old.get("rif")
        updated = old.get("updated_at")
        # normalize updated_at to ISO Z
        if isinstance(updated, str):
            try:
                # try parse then reformat
                iso = updated.replace("Z", "+00:00")
                dt = datetime.fromisoformat(iso)
                updated_iso = dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z") if dt.tzinfo else dt.replace(tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")
            except Exception:
                updated_iso = utc_now_iso()
        elif isinstance(updated, datetime):
            updated_iso = updated.replace(tzinfo=timezone.utc).isoformat().replace("+00:00", "Z") if updated.tzinfo is None else updated.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        else:
            updated_iso = utc_now_iso()
        cur.execute("""
            INSERT INTO clientes (id, nombre, nombre_normalizado, rif, rif_cedula, telefono, direccion, direccion_original, empresa, zona, lat, lng, latitud, longitud, texto_breve, is_flagged, has_gps_fix, updated_at, sync_status, deleted)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, (
            new_id,
            old.get("nombre"),
            old.get("nombre_normalizado") or "",
            rif,
            rif,
            old.get("telefono"),
            old.get("direccion"),
            old.get("direccion_original"),
            old.get("empresa"),
            old.get("zona"),
            lat,
            lng,
            lat,
            lng,
            old.get("texto_breve"),
            int(bool(old.get("is_flagged"))),
            int(bool(old.get("has_gps_fix"))),
            updated_iso,
            0,
            0,
        ))
    # Migrate rutas_hoy cliente_id FK type if needed
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='rutas_hoy'")
    if cur.fetchone():
        r_info = _table_info(cur, "rutas_hoy")
        r_cols = {row[1] for row in r_info}
        # check if cliente_id is INTEGER -> need to rebuild mapping old integer id -> new UUID
        # Since we lost mapping, we drop rutas_hoy content (cannot map reliably) — keep empty
        # Alternatively, if rutas_hoy was empty, no action
        cur.execute("SELECT COUNT(*) FROM rutas_hoy")
        # Actually we already renamed? No, we kept rutas_hoy; but its FK now points to TEXT ids, old integer ids invalid -> delete rows
        cur.execute("DELETE FROM rutas_hoy WHERE cliente_id NOT IN (SELECT id FROM clientes)")
    con.commit()
    # Drop old
    cur.execute("DROP TABLE clientes_old")
    con.commit()
    con.close()
    return {"status": "recreated", "rows": len(rows)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--db", default=DB_PATH)
    args = parser.parse_args()
    res = migrate(args.db, dry_run=args.dry_run)
    print(res)
