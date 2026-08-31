package com.gpsclientes.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClienteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ClienteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ClienteEntity>): List<Long>

    @Update
    suspend fun update(entity: ClienteEntity)

    @Delete
    suspend fun delete(entity: ClienteEntity)

    @Query("SELECT * FROM clientes WHERE id = :id")
    suspend fun getById(id: Long): ClienteEntity?

    @Query("SELECT * FROM clientes ORDER BY nombreNormalizado ASC")
    fun observeAll(): Flow<List<ClienteEntity>>

    @Query("SELECT * FROM clientes ORDER BY nombreNormalizado ASC")
    suspend fun getAll(): List<ClienteEntity>

    // Exact normalized match for duplicate detection
    @Query("SELECT * FROM clientes WHERE nombreNormalizado = :normalized LIMIT 1")
    suspend fun findExactNormalized(normalized: String): ClienteEntity?

    // LIKE candidates with LIMIT 10, ranked by exact then LIKE (caller may sort further with Levenshtein)
    @Query("SELECT * FROM clientes WHERE nombreNormalizado LIKE '%' || :normalized || '%' ORDER BY CASE WHEN nombreNormalizado = :normalized THEN 0 ELSE 1 END, nombreNormalizado ASC LIMIT 10")
    suspend fun findLikeCandidates(normalized: String): List<ClienteEntity>

    @Query("SELECT * FROM clientes WHERE nombreNormalizado LIKE '%' || :normalized || '%' ORDER BY CASE WHEN nombreNormalizado = :normalized THEN 0 ELSE 1 END, nombreNormalizado ASC LIMIT 10")
    fun observeLikeCandidates(normalized: String): Flow<List<ClienteEntity>>

    @Query("SELECT * FROM clientes WHERE rif = :rif LIMIT 1")
    suspend fun findByRif(rif: String): ClienteEntity?

    @Query("SELECT * FROM clientes WHERE rif = :rif")
    fun observeByRif(rif: String): Flow<List<ClienteEntity>>

    @Query("SELECT * FROM clientes WHERE isFlaggedImport = 1 ORDER BY nombreNormalizado ASC")
    suspend fun flaggedImports(): List<ClienteEntity>

    @Query("SELECT * FROM clientes WHERE isFlaggedImport = 1 ORDER BY nombreNormalizado ASC")
    fun observeFlaggedImports(): Flow<List<ClienteEntity>>

    @Query("SELECT * FROM clientes WHERE hasGpsFix = :hasFix ORDER BY nombreNormalizado ASC")
    suspend fun filterByHasGpsFix(hasFix: Boolean): List<ClienteEntity>

    @Query("SELECT * FROM clientes WHERE hasGpsFix = :hasFix ORDER BY nombreNormalizado ASC")
    fun observeByHasGpsFix(hasFix: Boolean): Flow<List<ClienteEntity>>

    @Query("SELECT COUNT(*) FROM clientes")
    suspend fun count(): Int

    @Query("DELETE FROM clientes")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ClienteEntity): Long

    // parity web: distinct zonas for filtroZona dropdown (web sidebar dynamic distinct zonaRuta)
    @Query("SELECT DISTINCT zonaRuta FROM clientes WHERE zonaRuta IS NOT NULL AND zonaRuta != '' ORDER BY zonaRuta ASC")
    suspend fun distinctZonas(): List<String>
}
