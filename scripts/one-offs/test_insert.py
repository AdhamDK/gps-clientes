import sqlite3
con=sqlite3.connect('backend/clientes.db')
try:
    con.execute("INSERT INTO clientes (id, nombre, nombre_normalizado, rif_cedula, rif, telefono, direccion, direccion_original, empresa, zona, latitud, longitud, lat, lng, texto_breve, is_flagged, has_gps_fix, updated_at, \"updatedAt\", sync_status, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ('d585b2f9-505b-469a-9906-248490c33b9e', 'Test Cliente Fix', 'test cliente fix', None, None, '04140000000', 'Calle Falsa 123', 'Calle Falsa 123', None, 'Rutero', None, None, None, None, None, 0, 0, '2026-08-29T19:35:21.809324Z', None, 0, 0))
    con.commit()
    print("INSERT OK")
except Exception as e:
    print("ERROR:", e)
    import traceback
    traceback.print_exc()
