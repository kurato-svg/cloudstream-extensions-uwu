package com.oppadrama

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AbyssDiagnostic {

    suspend fun inspect(
        url: String,
        referer: String
    ): List<String> {
        val page = app.get(
            url,
            referer = referer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to referer
            )
        ).text

        val datas = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""")
            .find(page)
            ?.groupValues
            ?.getOrNull(1)
            ?: return listOf("OPPA_ABYSS: datas not found for $url")

        val decoded = String(
            Base64.getDecoder().decode(datas),
            StandardCharsets.ISO_8859_1
        )

        val root = JSONObject(decoded)
        val slug = root.optString("slug")
        val md5Id = root.optLong("md5_id")
        val userId = root.optLong("user_id")
        val media = root.optString("media")

        if (slug.isBlank() || md5Id == 0L || userId == 0L || media.isBlank()) {
            return listOf(
                "OPPA_ABYSS: incomplete config",
                "OPPA_ABYSS: slug=$slug md5_id=$md5Id user_id=$userId media_len=${media.length}"
            )
        }

        val keyText = md5Hex("$userId:$slug:$md5Id")
        val keyBytes = keyText.toByteArray(StandardCharsets.US_ASCII)
        val ivBytes = keyBytes.copyOfRange(0, 16)

        val decrypted = aesCtr(
            media.toByteArray(StandardCharsets.ISO_8859_1),
            keyBytes,
            ivBytes
        )

        val decryptedJson = String(
            decrypted,
            StandardCharsets.UTF_8
        )

        val mp4 = JSONObject(decryptedJson).optJSONObject("mp4")
            ?: return listOf(
                "OPPA_ABYSS: decrypted but mp4 object not found",
                "OPPA_ABYSS_RAW: $decryptedJson"
            )

        val logs = mutableListOf<String>()

        logs.add("OPPA_ABYSS: OK")
        logs.add("OPPA_ABYSS: slug=$slug md5_id=$md5Id user_id=$userId")
        logs.add("OPPA_ABYSS: raw=$decryptedJson")

        val sources = mp4.optJSONArray("sources")
        val domains = mp4.optJSONArray("domains")

        if (sources != null) {
            for (i in 0 until sources.length()) {
                val item = sources.optJSONObject(i) ?: continue
                val label = item.optString("label")
                val resId = item.optInt("res_id")
                val size = item.optLong("size")
                val codec = item.optString("codec")
                val sub = item.optString("sub")
                val domain = domains?.optString(i).orEmpty()

                logs.add(
                    "OPPA_ABYSS_SOURCE: label=$label res_id=$resId size=$size codec=$codec sub=$sub domain=$domain"
                )
            }
        }

        return logs
    }

    private fun md5Hex(value: String): String {
        val bytes = MessageDigest
            .getInstance("MD5")
            .digest(value.toByteArray(StandardCharsets.UTF_8))

        return bytes.joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    private fun aesCtr(
        data: ByteArray,
        key: ByteArray,
        iv: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)

        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            ivSpec
        )

        return cipher.doFinal(data)
    }
}
