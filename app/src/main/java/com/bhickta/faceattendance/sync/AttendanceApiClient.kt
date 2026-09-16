package com.bhickta.faceattendance.sync

import com.bhickta.faceattendance.device.DeviceConfiguration
import com.bhickta.faceattendance.device.DeviceKeyManager
import com.bhickta.faceattendance.storage.AttendanceEventEntity
import com.bhickta.faceattendance.vision.BiometricRoster
import com.bhickta.faceattendance.vision.BiometricRosterStore
import com.bhickta.faceattendance.vision.FaceTemplate
import com.bhickta.faceattendance.vision.OfflineBiometricEngine
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

data class ServerState(
    val serverTimeEpochMillis: Long,
    val authorizationExpiresAtEpochMillis: Long,
    val branchId: String?,
    val gateId: String?,
    val directionMode: String?,
    val assignmentVersion: String?,
)

data class SubmissionResult(
    val acknowledgements: List<EventAcknowledgement>,
    val serverState: ServerState,
)

data class RosterResult(
    val changed: Boolean,
    val roster: BiometricRoster?,
)

data class SelfRegistrationResult(
    val status: String,
    val employeeName: String?,
    val registration: String?,
)

class AttendanceApiClient(
    private val configuration: DeviceConfiguration,
    private val keyManager: DeviceKeyManager,
) {
    fun submit(events: List<AttendanceEventEntity>): SubmissionResult {
        require(events.isNotEmpty())
        require(events.size <= MAX_BATCH_SIZE)

        val body = JSONObject().apply {
            put("schema_version", 1)
            put("device_id", configuration.deviceId)
            put("events", JSONArray(events.map(::eventJson)))
        }.toString().toByteArray(Charsets.UTF_8)

        val response = post("submit_events", body)
        return SubmissionResult(parseAcknowledgements(response), parseServerState(response))
    }

    fun syncState(): ServerState {
        val body = JSONObject().apply {
            put("schema_version", 1)
            put("device_id", configuration.deviceId)
        }.toString().toByteArray(Charsets.UTF_8)
        return parseServerState(post("sync_state", body))
    }

    fun syncRoster(currentVersion: String?): RosterResult {
        val body = JSONObject().apply {
            put("schema_version", 1)
            put("device_id", configuration.deviceId)
            put("roster_version", currentVersion)
        }.toString().toByteArray(Charsets.UTF_8)
        val root = post("sync_roster", body)
        val payload = root.optJSONObject("message") ?: root
        val changed = payload.getBoolean("changed")
        if (!changed) return RosterResult(false, null)
        val templatesJson = payload.getJSONArray("templates")
        return RosterResult(
            changed = true,
            roster = BiometricRoster(
                version = payload.getString("roster_version"),
                modelVersion = payload.getString("model_version"),
                templates = List(templatesJson.length()) { index ->
                    val item = templatesJson.getJSONObject(index)
                    FaceTemplate(
                        personId = item.getString("person_id"),
                        displayName = item.getString("display_name"),
                        templateVersion = item.getString("template_version"),
                        embedding = BiometricRosterStore.decodeEmbedding(item.getString("embedding")),
                    )
                },
            ),
        )
    }

    fun submitSelfRegistration(
        fullName: String,
        phone: String?,
        direction: String,
        capturedAtEpochMillis: Long,
        sampleCount: Int,
        embedding: FloatArray,
    ): SelfRegistrationResult {
        val body = JSONObject().apply {
            put("schema_version", 1)
            put("device_id", configuration.deviceId)
            put("full_name", fullName)
            if (!phone.isNullOrBlank()) put("phone", phone.trim())
            put("direction", direction)
            put("captured_at", Instant.ofEpochMilli(capturedAtEpochMillis).toString())
            put("sample_count", sampleCount)
            put("embedding", BiometricRosterStore.encodeEmbedding(embedding))
            put("model_version", OfflineBiometricEngine.MODEL_VERSION)
            put("consent_confirmed", true)
        }.toString().toByteArray(Charsets.UTF_8)
        val response = post("submit_self_registration", body)
        val payload = response.optJSONObject("message") ?: response
        return SelfRegistrationResult(
            status = payload.optString("status"),
            employeeName = payload.optString("employee_name").takeIf(String::isNotBlank),
            registration = payload.optString("registration").takeIf(String::isNotBlank),
        )
    }

    private fun post(method: String, body: ByteArray): JSONObject {
        val connection = URL(
            "${configuration.baseUrl}/api/method/face_attendance.api.v1.$method",
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
            JSONObject(connection.inputStream.bufferedReader().readText())
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

    private fun parseAcknowledgements(root: JSONObject): List<EventAcknowledgement> {
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

    private fun parseServerState(root: JSONObject): ServerState {
        val payload = root.optJSONObject("message") ?: root
        return ServerState(
            serverTimeEpochMillis = payload.getLong("server_time_epoch_millis"),
            authorizationExpiresAtEpochMillis = payload.getLong("authorization_expires_at_epoch_millis"),
            branchId = payload.optString("branch_id").takeIf(String::isNotBlank),
            gateId = payload.optString("gate_id").takeIf(String::isNotBlank),
            directionMode = payload.optString("direction_mode").takeIf(String::isNotBlank),
            assignmentVersion = payload.optString("assignment_version").takeIf(String::isNotBlank),
        )
    }

    companion object {
        const val MAX_BATCH_SIZE = 100
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

class ApiException(val statusCode: Int, responseBody: String?) :
    RuntimeException("Attendance API returned HTTP $statusCode: ${responseBody.orEmpty().take(300)}")
