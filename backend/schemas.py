"""Pydantic v2 schemas for Cliente — RIF validation, null-friendly zona."""

from datetime import datetime
from typing import Union

from pydantic import BaseModel, ConfigDict, Field, field_validator

# RIF pattern per spec: ^[JVEGP]\d{7,9}$ when present
RIF_PATTERN = r"^[JVEGP]\d{7,9}$"


class ClienteBase(BaseModel):
    nombre: str = Field(..., min_length=1, description="Required, non-empty")
    rif: str | None = Field(default=None, pattern=RIF_PATTERN, description="Optional; if present must match RIF regex")
    telefono: str | None = None
    direccion: str | None = None
    direccion_original: str | None = None
    empresa: str | None = None
    zona: str | None = None
    lat: float | None = None
    lng: float | None = None
    texto_breve: str | None = None


class ClienteCreate(ClienteBase):
    pass


class ClienteUpdate(BaseModel):
    nombre: str | None = Field(default=None, min_length=1)
    rif: str | None = Field(default=None, pattern=RIF_PATTERN)
    telefono: str | None = None
    direccion: str | None = None
    empresa: str | None = None
    zona: str | None = None
    lat: float | None = None
    lng: float | None = None
    texto_breve: str | None = None


class ClienteRead(ClienteBase):
    id: Union[str, int]
    nombre_normalizado: str
    is_flagged: bool
    has_gps_fix: bool
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class EntregadoRequest(BaseModel):
    cliente_ids: list[str]

    @field_validator("cliente_ids", mode="before")
    @classmethod
    def coerce_ids(cls, v):
        if not isinstance(v, list):
            raise ValueError("cliente_ids must be a list")
        out: list[str] = []
        for x in v:
            if x is None:
                continue
            s = str(x).strip()
            if s == "":
                continue
            out.append(s)
        return out


class EntregadoResponse(BaseModel):
    updated: int
    fecha: str


class RutasHoyRead(BaseModel):
    orden: int
    cliente_id: Union[str, int]
    fecha: str
    entregado: bool = False
    delivered_at: datetime | None = None
    cliente: ClienteRead | None = None

    model_config = ConfigDict(from_attributes=True)


class OptimizeRequest(BaseModel):
    cliente_ids: list[str]
    start: list[float] | None = None

    @field_validator("cliente_ids", mode="before")
    @classmethod
    def coerce_cliente_ids(cls, v):
        if not isinstance(v, list):
            raise ValueError("cliente_ids must be a list")
        out: list[str] = []
        for x in v:
            if x is None:
                continue
            s = str(x).strip()
            if s == "":
                continue
            out.append(s)
        return out
