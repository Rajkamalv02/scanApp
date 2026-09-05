package com.coindcx.trading.data.api

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verified HMAC-SHA256 Request Signing Interceptor
 * Tested and proven in Phase 2 against live CoinDCX endpoints.
 */
class AuthInterceptor(
    private val apiKeyProvider: () -> String,
    private val apiSecretProvider: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth for public endpoints marked with X-PUBLIC-REQUEST
        if (originalRequest.header("X-PUBLIC-REQUEST") != null) {
            val stripped = originalRequest.newBuilder()
                .removeHeader("X-PUBLIC-REQUEST")
                .build()
            return chain.proceed(stripped)
        }

        val apiKey = apiKeyProvider()
        val apiSecret = apiSecretProvider()

        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return chain.proceed(originalRequest)
        }

        val method = originalRequest.method
        val signatureHex = if (method.equals("POST", ignoreCase = true)) {
            val bodyString = bodyToString(originalRequest.body)
            calculateHmacSha256(bodyString, apiSecret)
        } else {
            // Authenticated GET requests sign empty payload
            calculateHmacSha256("", apiSecret)
        }

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Content-Type", "application/json")
            .header("X-AUTH-APIKEY", apiKey)
            .header("X-AUTH-SIGNATURE", signatureHex)
            .build()

        return chain.proceed(authenticatedRequest)
    }

    private fun bodyToString(requestBody: okhttp3.RequestBody?): String {
        if (requestBody == null) return ""
        return try {
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readString(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun calculateHmacSha256(data: String, secret: String): String {
        val algorithm = "HmacSHA256"
        val secretKey = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), algorithm)
        val mac = Mac.getInstance(algorithm)
        mac.init(secretKey)
        val bytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
