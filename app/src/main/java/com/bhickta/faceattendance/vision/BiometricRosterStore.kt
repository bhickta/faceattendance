package com.bhickta.faceattendance.vision

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class FaceTemplate(
    val personId: String,
    val displayName: String,
    val templateVersion: String,
    val embedding: FloatArray,
)

data class BiometricRoster(
    val version: String,
    val modelVersion: String,
    val templates: List<FaceTemplate>,
)

class BiometricRosterStore(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("biometric_roster_v1.enc"))
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    @Volatile private var cached: BiometricRoster? = null

    @Synchronized
    fun get(): BiometricRoster? {
        cached?.let { return it }
        if (!file.baseFile.exists()) return null
        return runCatching { decode(decrypt(file.readFully())) }
            .getOrNull()
            ?.also { cached = it }
    }

    @Synchronized
    fun save(roster: BiometricRoster) {
        validate(roster)
        val encrypted = encrypt(encode(roster))
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
            cached = roster
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun validate(roster: BiometricRoster) {
        require(roster.version.isNotBlank())
        require(roster.modelVersion == OfflineBiometricEngine.MODEL_VERSION)
        require(roster.templates.map(FaceTemplate::personId).distinct().size == roster.templates.size)
        roster.templates.forEach {
            require(it.personId.isNotBlank() && it.displayName.isNotBlank() && it.templateVersion.isNotBlank())
            require(it.embedding.size == EMBEDDING_SIZE)
            require(it.embedding.all(Float::isFinite))
            require(CosineMatcher.norm(it.embedding) > 0.99f)
        }
    }

    private fun encode(roster: BiometricRoster) = JSONObject().apply {
        put("version", roster.version)
        put("model_version", roster.modelVersion)
        put("templates", JSONArray(roster.templates.map { template ->
            JSONObject().apply {
                put("person_id", template.personId)
                put("display_name", template.displayName)
                put("template_version", template.templateVersion)
                put("embedding", encodeEmbedding(template.embedding))
            }
        }))
    }.toString().toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): BiometricRoster {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val items = root.getJSONArray("templates")
        return BiometricRoster(
            version = root.getString("version"),
            modelVersion = root.getString("model_version"),
            templates = List(items.length()) { index ->
                val item = items.getJSONObject(index)
                FaceTemplate(
                    personId = item.getString("person_id"),
                    displayName = item.getString("display_name"),
                    templateVersion = item.getString("template_version"),
                    embedding = decodeEmbedding(item.getString("embedding")),
                )
            },
        ).also(::validate)
    }

    private fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return byteArrayOf(FILE_VERSION) + cipher.iv + cipher.doFinal(plainText)
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > 1 + IV_LENGTH && payload[0] == FILE_VERSION)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, payload.copyOfRange(1, 1 + IV_LENGTH)),
        )
        return cipher.doFinal(payload.copyOfRange(1 + IV_LENGTH, payload.size))
    }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val EMBEDDING_SIZE = 256
        private const val FILE_VERSION: Byte = 1
        private const val IV_LENGTH = 12
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "face_attendance_roster_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun encodeEmbedding(values: FloatArray): String {
            val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach(buffer::putFloat)
            return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        }

        fun decodeEmbedding(value: String): FloatArray {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            require(bytes.size == EMBEDDING_SIZE * Float.SIZE_BYTES)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(EMBEDDING_SIZE) { buffer.float }
        }
    }
}
