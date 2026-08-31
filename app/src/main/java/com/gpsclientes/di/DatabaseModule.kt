package com.gpsclientes.di

import android.content.Context
import androidx.room.Room
import com.gpsclientes.data.local.AppDatabase
import com.gpsclientes.data.local.ClienteDao
import com.gpsclientes.data.importer.ImportClientesUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DatabaseModule — single source of truth via Room.
 * Provides AppDatabase with TypeConverters, ClienteDao, and ImportClientesUseCase.
 * Placeholder for later modules (LocationModule, MapModule, ExportModule) to be added in PR2+.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "gps_clientes.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideClienteDao(db: AppDatabase): ClienteDao = db.clienteDao()

    @Provides
    @Singleton
    fun provideImportUseCase(db: AppDatabase): ImportClientesUseCase =
        ImportClientesUseCase(db)
}
