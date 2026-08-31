"""SQLAlchemy models: Cliente (11-col collapsed) + RutasHoy.

11-col Cliente maps to Android Room 31-col (ClienteEntity.kt) via comments:
  Room 31 = 20 Excel (A-T) + 8 GPS + 3 derived + updatedAt.
  Backend collapses to routable subset: nombre (+normalized), rif, telefono,
  direccion, direccion_original (frozen), empresa, zona, lat/lng, texto_breve,
  is_flagged, has_gps_fix + audit updatedAt. Indices on nombre_normalizado,
  rif, has_gps_fix per spec.
"""

import uuid
from datetime import datetime

from sqlalchemy import Boolean, Column, Date, DateTime, Float, ForeignKey, Index, Integer, String
from sqlalchemy.orm import relationship

from .database import Base


class Cliente(Base):
    __tablename__ = "clientes"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))

    # Core identity (Excel B + derived)
    nombre = Column(String, nullable=False)  # nombreCanonico
    nombre_normalizado = Column(String, nullable=False)  # NFD lower+strip

    # RIF (Excel E) — validated ^[JVEGP]\d{7,9}$ via Pydantic; nullable per 55% null rate
    rif = Column(String, nullable=True)
    rif_cedula = Column(String, nullable=True)

    # Contact / address (Excel H,J,L)
    telefono = Column(String, nullable=True)
    direccion = Column(String, nullable=True)
    direccion_original = Column(String, nullable=True)  # frozen on import
    empresa = Column(String, nullable=True)  # Excel L

    # Routing zone (Excel M) — 95% null in fixture, optional
    zona = Column(String, nullable=True)

    # GPS (null until geocoded; has_gps_fix derived)
    lat = Column(Float, nullable=True)
    lng = Column(Float, nullable=True)
    latitud = Column(Float, nullable=True)
    longitud = Column(Float, nullable=True)
    texto_breve = Column(String, nullable=True)

    # Derived flags
    is_flagged = Column(Boolean, default=False, nullable=False)  # Empresa contains '#'
    has_gps_fix = Column(Boolean, default=False, nullable=False)

    # Audit
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False)
    sync_status = Column(Integer, default=0, nullable=False)
    deleted = Column(Integer, default=0, nullable=False)

    rutas = relationship("RutasHoy", back_populates="cliente", cascade="all, delete-orphan")

    __table_args__ = (
        Index("ix_clientes_nombre_normalizado", "nombre_normalizado"),
        Index("ix_clientes_rif", "rif"),
        Index("ix_clientes_has_gps_fix", "has_gps_fix"),
    )


class RutasHoy(Base):
    __tablename__ = "rutas_hoy"

    id = Column(Integer, primary_key=True)
    cliente_id = Column(String, ForeignKey("clientes.id", ondelete="CASCADE"), nullable=False)
    orden = Column(Integer, nullable=False)
    fecha = Column(Date, nullable=False)
    entregado = Column(Boolean, default=False, nullable=False)
    delivered_at = Column(DateTime, nullable=True)

    cliente = relationship("Cliente", back_populates="rutas")

    __table_args__ = (
        Index("ix_rutas_hoy_fecha_orden", "fecha", "orden"),
        Index("ix_rutas_hoy_cliente_id", "cliente_id"),
        Index("ix_rutas_hoy_fecha_entregado", "fecha", "entregado"),
    )
