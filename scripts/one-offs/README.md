# One-off Debug Scripts

These scripts were previously at the repository root and broke `pytest` collection
(e.g. `test_delete_uuid.py` raises `JSONDecodeError` on import because it executes
`httpx` calls at module scope). They are **not** pytest tests — they are manual,
one-off debug helpers that require a running backend (`http://localhost:8000`) or a
local SQLite file.

- `test_delete_uuid.py` — manual delete/verify via HTTP (requires running API)
- `test_insert.py` — raw SQLite insert into `backend/clientes.db`
- `test_sqlalchemy_insert.py` — SQLAlchemy insert into `backend/clientes.db`
- `check_db.py` — `PRAGMA table_info(clientes)` dump
- `check_sql2.py` — `SELECT sql FROM sqlite_master` dump

Run them explicitly if needed:

```bash
python scripts/one-offs/check_db.py
python scripts/one-offs/test_delete_uuid.py  # needs backend running
```

`pytest.ini` scopes collection to `backend/tests` and `tests`, so these files are
excluded from default `python -m pytest -q` without `--ignore` flags.
