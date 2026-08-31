# DB Schema Specification (Offline SQLite / Local Storage)

## Tabla: `clientes`
- `id`: TEXT PRIMARY KEY (UUID v4 generado en cliente)
- `nombre`: TEXT NOT NULL
- `rif_cedula`: TEXT
- `direccion`: TEXT
- `latitud`: REAL NOT NULL
- `longitud`: REAL NOT NULL
- `telefono`: TEXT
- `updated_at`: TEXT NOT NULL (ISO-8601 UTC timestamp: YYYY-MM-DDTHH:MM:SSZ)
- `sync_status`: INTEGER NOT NULL DEFAULT 0 (0 = pendiente de subir, 1 = sincronizado)
- `deleted`: INTEGER NOT NULL DEFAULT 0 (1 = borrado lógico)