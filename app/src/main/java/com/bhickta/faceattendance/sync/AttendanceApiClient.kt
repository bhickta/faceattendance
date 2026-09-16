package com.bhickta.faceattendance.sync

import com.bhickta.faceattendance.device.DeviceConfiguration
import com.bhickta.faceattendance.device.DeviceKeyManager
import com.bhickta.faceattendance.storage.AttendanceEventEntity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class EventAcknowledgement(
    val eventId: String,
    val status: String,
    val reason: String?,
)

class AttendanceApiClient(
    private val configuration: DeviceConfiguration,
    private val keyManager: DeviceKeyManager,
) {
    fun submit(events: List<AttendanceEventEntity>): List<EventAcknowledgement> {
        require(events.isNotEmpty())
        require(events.size <= MAX_BATCH_SIZE)

        val body = JSONObject().apply {
            put("schema_version", 1)
            put("device_id", configuration.deviceId)
            put("events", JSONArray(events.map(::eventJson)))
        }.toString().toByteArray(Charsets.UTF_8)

        val connection = URL(
            "${configuration.baseUrl}/api/method/face_attendance.api.v1.submit_events",
        ).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
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
                throw ApiException(connection.responseCode, connection.errorStream?.bufferedReader()?.readText())
            }
            parseAcknowledgements(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private fun eventJson(event: AttendanceEventEntity) = JSONObject().apply {
        put("event_id", event.eventId)
        put("device_sequence", event.deviceSequence)
        put("person_id", event.personId)
        put("device_id", event.deviceId)
        put("branch_id", event.branchId)
        put("gate_id", event.gateId)
        put("assignment_version", event.assignmentVersion)
        put("event_type", event.direction)
        put("captured_at", Instant.ofEpochMilli(event.capturedAtEpochMillis).toString())
        put("captured_at_elapsed_ms", event.capturedAtElapsedMillis)
        put("boot_id", event.bootId)
        put("timezone_offset_minutes", event.timezoneOffsetMinutes)
        put("timestamp_confidence", event.timestampConfidence)
        put("match_score", event.matchScore)
        put("liveness_score", event.livenessScore)
        put("model_version", event.modelVersion)
        put("template_version", event.templateVersion)
        put("roster_version", event.rosterVersion)
    }

    private fun parseAcknowledgements(response: String): List<EventAcknowledgement> {
        val root = JSONObject(response)
        val payload = root.optJSONObject("message") ?: root
        val results = payload.getJSONArray("results")
        return List(results.length()) { index ->
            val item = results.getJSONObject(index)
            EventAcknowledgement(
                eventId = item.getString("event_id"),
                status = item.getString("status"),
                reason = item.optString("reason").takeIf(String::isNotBlank),
            )
        }
    }

    companion object {
        const val MAX_BATCH_SIZE = 100
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

class ApiException(val statusCode: Int, responseBody: String?) :
    RuntimeException("Attendance API returned HTTP $statusCode: ${responseBody.orEmpty().take(300)}")
