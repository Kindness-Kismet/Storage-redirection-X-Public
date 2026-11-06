package com.storage.redirect.x.data.repository

import com.storage.redirect.x.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

const val CLOUD_RULE_REPOSITORY = "https://github.com/Kindness-Net/storage-redirection-x-rules"
internal const val CLOUD_RULE_MAX_BYTES = 8 * 1024 * 1024

class CloudRuleHttpException(val statusCode: Int) : IOException("Cloud rules HTTP $statusCode")
class CloudRuleSizeException : IOException("Cloud rules exceed size limit")

// 固定的公开规则源，无需凭据，也不发送已安装应用或用户配置。
class CloudRuleSource {
    fun download(): ByteArray {
        val url = "https://raw.githubusercontent.com/Kindness-Net/storage-redirection-x-rules/master/catalog-v1.json"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "StorageRedirectX-Android/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw CloudRuleHttpException(connection.responseCode)
            }
            if (connection.contentLengthLong > CLOUD_RULE_MAX_BYTES) throw CloudRuleSizeException()
            connection.inputStream.use { it.readCloudRuleBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun InputStream.readCloudRuleBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > CLOUD_RULE_MAX_BYTES) throw CloudRuleSizeException()
        output.write(buffer, 0, count)
    }
}
