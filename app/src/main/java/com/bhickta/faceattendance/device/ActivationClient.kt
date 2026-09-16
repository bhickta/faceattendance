package com.bhickta.faceattendance.device

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ActivationClient(private val keyManager: DeviceKeyManager = DeviceKeyManager()) {
    fun activate(baseUrl: String, activationToken: String): DeviceConfiguration {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        require(normalizedUrl.startsWith("https://")) { "Server address must use HTTPS" }
        require(activationToken.isNotBlank()) { "Activation token is required" }

        val request = JSONObject().apply {
            put("activation_token", activationToken.trim())
            put("public_key", keyManager.publicKeyBase64())
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = URL(
            "$normalizedUrl/api/method/face_attendance.api.v1.activate_device",
        ).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(request) }
            if (connection.responseCode !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.readText()
                throw ActivationException("Activation failed (HTTP ${connection.responseCode})", message)
            }
            val root = JSONObject(connection.inputStream.bufferedReader().readText())
            val result = root.optJSONObject("message") ?: root
            DeviceConfiguration(
                baseUrl = normalizedUrl,
                apiKey = result.getString("api_key"),
                apiSecret = result.getString("api_secret"),
                deviceId = result.getString("device_id"),
                branchId = result.getString("branch_id"),
                gateId = result.getString("gate_id"),
                directionMode = result.getString("direction_mode"),
                assignmentVersion = result.getString("assignment_version"),
            )
        } finally {
            connection.disconnect()
        }
    }
}

class ActivationException(message: String, response: String?) :
    RuntimeException("$message: ${response.orEmpty().take(200)}")
