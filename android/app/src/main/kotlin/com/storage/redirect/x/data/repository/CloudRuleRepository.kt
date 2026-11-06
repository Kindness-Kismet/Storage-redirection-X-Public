package com.storage.redirect.x.data.repository

import android.util.AtomicFile
import com.storage.redirect.x.data.model.CloudRuleCatalog
import com.storage.redirect.x.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class CloudRuleSnapshot(val catalog: CloudRuleCatalog, val fetchedAtMillis: Long)

enum class CloudRuleError { Network, Http, InvalidData, Storage }

data class CloudRuleResult(
    val snapshot: CloudRuleSnapshot?,
    val error: CloudRuleError? = null,
    val httpStatus: Int? = null,
)

class CloudRuleRepository(
    cacheDirectory: File,
    private val download: () -> ByteArray = CloudRuleSource()::download,
) {
    private val cache = AtomicFile(File(cacheDirectory, "cloud-rules-v1.json"))

    suspend fun loadCached(): CloudRuleResult = withContext(Dispatchers.IO) {
        cacheMutex.withLock { readCache() }
    }

    suspend fun refresh(): CloudRuleResult = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val previous = readCache().snapshot
            val bytes = try {
                download()
            } catch (error: IOException) {
                return@withLock CloudRuleResult(
                    previous,
                    when (error) {
                        is CloudRuleHttpException -> CloudRuleError.Http
                        is CloudRuleSizeException -> CloudRuleError.InvalidData
                        else -> CloudRuleError.Network
                    },
                    (error as? CloudRuleHttpException)?.statusCode,
                )
            }
            currentCoroutineContext().ensureActive()
            val catalog = try {
                parse(bytes)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Logger.error("Invalid cloud rule catalog", error)
                return@withLock CloudRuleResult(previous, CloudRuleError.InvalidData)
            }
            currentCoroutineContext().ensureActive()
            try {
                writeCache(bytes)
                CloudRuleResult(CloudRuleSnapshot(catalog, cache.baseFile.lastModified()))
            } catch (error: IOException) {
                Logger.error("Cannot save cloud rule cache", error)
                CloudRuleResult(previous, CloudRuleError.Storage)
            }
        }
    }

    private fun readCache(): CloudRuleResult {
        if (!cache.baseFile.exists() && !File(cache.baseFile.path + ".bak").exists()) {
            return CloudRuleResult(null)
        }
        return try {
            val bytes = cache.openRead().use { it.readCloudRuleBytes() }
            CloudRuleResult(CloudRuleSnapshot(parse(bytes), cache.baseFile.lastModified()))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Logger.error("Cannot read cloud rule cache", error)
            CloudRuleResult(null, if (error is IOException) CloudRuleError.Storage else CloudRuleError.InvalidData)
        }
    }

    private fun parse(bytes: ByteArray): CloudRuleCatalog {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return CloudRuleParser.parse(text)
    }

    private fun writeCache(bytes: ByteArray) {
        val stream = cache.startWrite()
        try {
            stream.write(bytes)
            cache.finishWrite(stream)
        } catch (error: IOException) {
            cache.failWrite(stream)
            throw error
        }
    }

    companion object {
        private val cacheMutex = Mutex()
    }
}
