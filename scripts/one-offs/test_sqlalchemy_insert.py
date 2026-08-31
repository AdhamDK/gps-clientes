from backend.database import SessionLocal, Base, engine
from backend.models import Cliente
from backend.database import generate_uuid, utc_now_iso, normalize_nfd

# Try to insert via SQLAlchemy
db = SessionLocal()
try:
    cliente = Cliente(
        id=generate_uuid(),
        nombre="Test Cliente Fix",
        nombre_normalizado=normalize_nfd("Test Cliente Fix"),
        rif=None,
        rif_cedula=None,
        telefono="04140000000",
        direccion="Calle Falsa 123",
        direccion_original="Calle Falsa 123",
        empresa=None,
        zona="Rutero",
        lat=None,
        lng=None,
        latitud=None,
        longitud=None,
        texto_breve=None,
        is_flagged=False,
        has_gps_fix=False,
        updated_at=utc_now_iso(),
        sync_status=0,
        deleted=0,
    )
    db.add(cliente)
    db.commit()
    print("SQLAlchemy INSERT OK", cliente.id)
except Exception as e:
    print("ERROR:", e)
    import traceback
    traceback.print_exc()
finally:
    db.close()
