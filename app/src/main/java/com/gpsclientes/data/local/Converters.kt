package com.gpsclientes.data.local

import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromGeocodingSource(source: GeocodingSource): String = source.name

    @TypeConverter
    fun toGeocodingSource(value: String): GeocodingSource = try {
        GeocodingSource.valueOf(value)
    } catch (_: IllegalArgumentException) {
        GeocodingSource.MANUAL
    }
}
