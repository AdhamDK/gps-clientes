package com.gpsclientes.di

import com.gpsclientes.ui.map.MapProvider
import com.gpsclientes.ui.map.OsmMapProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MapModule {
    @Binds
    abstract fun bindMapProvider(impl: OsmMapProvider): MapProvider
}
