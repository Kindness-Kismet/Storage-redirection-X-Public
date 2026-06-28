package com.storage.redirect.x.data.repository

import com.google.gson.JsonParser
import com.storage.redirect.x.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class UpdateResult(
    val latestVersion: String,
    val currentVersion: String,
    val hasUpdate: Boolean,
    val releaseUrl: String,
    val releaseNotes: String,
)

class UpdateRepository {

    companion object {
        // GitHub Releases API 地址
        private const val API_URL =
            "https://api.github.com/repos/Kindness-Kismet/Storage-redirection-X-Public/releases/latest"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val REQUEST_ACCEPT = "application/vnd.github+json"
        private const val REQUEST_USER_AGENT = "StorageRedirectX-Android/${BuildConfig.VERSION_NAME}"
    }

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        val json = requestLatestReleaseJson()
        val obj = JsonParser.parseString(json).asJsonObject
        val tagName = obj.get("tag_name").asString
        val htmlUrl = obj.get("html_url").asString
        val body = obj.get("body")?.asString.orEmpty()

        val latest = tagName.removePrefix("v")
        val current = BuildConfig.VERSION_NAME

        UpdateResult(
            latestVersion = latest,
            currentVersion = current,
            hasUpdate = latest != current,
            releaseUrl = htmlUrl,
            releaseNotes = body,
        )
    }

    private fun requestLatestReleaseJson(): String {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", REQUEST_ACCEPT)
            setRequestProperty("User-Agent", REQUEST_USER_AGENT)
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("GitHub API error: code=$responseCode, body=$errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }
}
