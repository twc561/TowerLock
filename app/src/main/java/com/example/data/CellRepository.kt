package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class CellRepository(
    private val cellDao: CellDao,
    private val context: Context
) {
    val allLogs: Flow<List<CellLog>> = cellDao.getAllLogs()
    val allTowers: Flow<List<TowerDbEntry>> = cellDao.getAllTowers()

    private val okHttpClient = OkHttpClient()

    suspend fun insertLog(log: CellLog) = withContext(Dispatchers.IO) {
        cellDao.insertLog(log)
    }

    suspend fun deleteLog(log: CellLog) = withContext(Dispatchers.IO) {
        cellDao.deleteLog(log)
    }

    suspend fun deleteLogById(id: Long) = withContext(Dispatchers.IO) {
        cellDao.deleteLogById(id)
    }

    suspend fun clearAllLogs() = withContext(Dispatchers.IO) {
        cellDao.clearAllLogs()
    }

    // Lookup Chain with Source Attribution Badge
    // Returns: Triple(lat, lon, range/accuracy) and Source String
    suspend fun findTowerLocation(
        tech: String,
        mcc: String,
        mnc: String,
        area: Int,
        cid: Long,
        openCellIdApiKey: String?
    ): TowerLocationResult = withContext(Dispatchers.IO) {
        // 1. Search local database
        val localTower = cellDao.findTower(mcc, mnc, area, cid)
        if (localTower != null) {
            return@withContext TowerLocationResult(
                lat = localTower.lat,
                lon = localTower.lon,
                range = localTower.range,
                address = localTower.address,
                source = "Local Database"
            )
        }

        // 2. Search OpenCelliD API if key is available
        if (!openCellIdApiKey.isNullOrBlank()) {
            try {
                // OpenCelliD API uses LAC for area, cellid for cid
                val radioType = when {
                    tech.contains("5G", ignoreCase = true) -> "LTE" // OpenCelliD maps NR/LTE similarly or radio="LTE"
                    else -> "LTE"
                }
                val url = "https://opencellid.org/cell/get?key=$openCellIdApiKey&mcc=$mcc&mnc=$mnc&lac=$area&cellid=$cid&format=json"
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            if (json.has("lat") && json.has("lon")) {
                                val lat = json.getDouble("lat")
                                val lon = json.getDouble("lon")
                                val range = json.optInt("range", 1000)
                                
                                // Cache it in our local database
                                val cachedTower = TowerDbEntry(
                                    radio = if (tech.contains("5G")) "NR" else "LTE",
                                    mcc = mcc,
                                    mnc = mnc,
                                    area = area,
                                    cid = cid,
                                    lat = lat,
                                    lon = lon,
                                    range = range,
                                    address = null
                                )
                                cellDao.insertTower(cachedTower)

                                return@withContext TowerLocationResult(
                                    lat = lat,
                                    lon = lon,
                                    range = range,
                                    address = null,
                                    source = "OpenCelliD API"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CellRepository", "OpenCelliD lookup failed", e)
            }
        }

        // 3. Unmapped cell state
        return@withContext TowerLocationResult(
            lat = null,
            lon = null,
            range = 0,
            address = null,
            source = "Unmapped cell"
        )
    }

    suspend fun insertCustomTower(entry: TowerDbEntry) = withContext(Dispatchers.IO) {
        cellDao.insertTower(entry)
    }

    // CSV Import: mcc, mnc, area, cid, lat, lon, range, address
    suspend fun importCsv(inputStream: InputStream): Int = withContext(Dispatchers.IO) {
        var importedCount = 0
        val entries = mutableListOf<TowerDbEntry>()
        try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine() // Skip header if present, or check if it is header
                val isHeader = line != null && (line.contains("mcc") || line.contains("radio"))
                if (!isHeader && line != null) {
                    // It is a data line, parse it
                    parseCsvLine(line)?.let { entries.add(it) }
                }
                while (reader.readLine().also { line = it } != null) {
                    parseCsvLine(line!!)?.let { entries.add(it) }
                    if (entries.size >= 100) {
                        cellDao.insertTowers(entries)
                        importedCount += entries.size
                        entries.clear()
                    }
                }
                if (entries.isNotEmpty()) {
                    cellDao.insertTowers(entries)
                    importedCount += entries.size
                }
            }
        } catch (e: Exception) {
            Log.e("CellRepository", "CSV Import failed", e)
        }
        importedCount
    }

    private fun parseCsvLine(line: String): TowerDbEntry? {
        val parts = line.split(",")
        if (parts.size < 7) return null
        return try {
            val radio = parts[0].trim()
            val mcc = parts[1].trim()
            val mnc = parts[2].trim()
            val area = parts[3].trim().toInt()
            val cid = parts[4].trim().toLong()
            val lat = parts[5].trim().toDouble()
            val lon = parts[6].trim().toDouble()
            val range = parts.getOrNull(7)?.trim()?.toIntOrNull() ?: 1000
            val address = parts.getOrNull(8)?.trim()?.replace("\"", "")
            TowerDbEntry(
                radio = radio,
                mcc = mcc,
                mnc = mnc,
                area = area,
                cid = cid,
                lat = lat,
                lon = lon,
                range = range,
                address = address
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class TowerLocationResult(
    val lat: Double?,
    val lon: Double?,
    val range: Int,
    val address: String?,
    val source: String
)
