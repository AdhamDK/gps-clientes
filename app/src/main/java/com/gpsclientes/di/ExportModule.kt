package com.gpsclientes.di

import com.gpsclientes.data.export.ExportRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for export — ExportRepository is @Singleton @Inject, no extra binding needed.
 * Kept as placeholder for future export-scoped providers.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule
