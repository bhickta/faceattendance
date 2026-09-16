package com.bhickta.faceattendance.enrollment

import com.bhickta.faceattendance.device.DeviceConfiguration
import com.bhickta.faceattendance.device.DeviceKeyManager
import com.bhickta.faceattendance.sync.ApiException
import com.bhickta.faceattendance.vision.BiometricRosterStore
import com.bhickta.faceattendance.vision.OfflineBiometricEngine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class EnrollmentClient(
    private val configuration: DeviceConfiguration,
    private val keyManager: DeviceKeyManager,
) {
    fun submit(token: String, embedding: FloatArray) {
        require(token.isNotBlank())
        val body = JSONObject().apply {
            put("schema_version", 1)
            put("device_id", configuration.deviceId)
            put("enrollment_token", token.trim())
            put("model_version", OfflineBiometricEngine.MODEL_VERSION)
            put("embedding", BiometricRosterStore.encodeEmbedding(embedding))
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = URL(
            "${configuration.baseUrl}/api/method/face_attendance.api.v1.submit_enrollment",
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty(
                "Authorization",
                "token ${configuration.apiKey}:${configuration.apiSecret}",
            )
            connection.setRequestProperty("X-Device-Signature", keyManager.sign(body))
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) {
                throw ApiException(
                    connection.responseCode,
                    connection.errorStream?.bufferedReader()?.readText(),
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
