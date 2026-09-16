package com.bhickta.faceattendance.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DeviceConfiguration(
    val baseUrl: String,
    val apiKey: String,
    val apiSecret: String,
    val deviceId: String,
    val branchId: String,
    val gateId: String,
    val directionMode: String,
    val assignmentVersion: String,
)

class DeviceConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    fun get(): DeviceConfiguration? {
        val baseUrl = preferences.getString("base_url", null) ?: return null
        return runCatching {
            DeviceConfiguration(
                baseUrl = baseUrl,
                apiKey = decrypt(requireNotNull(preferences.getString("api_key", null))),
                apiSecret = decrypt(requireNotNull(preferences.getString("api_secret", null))),
                deviceId = requireNotNull(preferences.getString("device_id", null)),
                branchId = requireNotNull(preferences.getString("branch_id", null)),
                gateId = requireNotNull(preferences.getString("gate_id", null)),
                directionMode = requireNotNull(preferences.getString("direction_mode", null)),
                assignmentVersion = requireNotNull(preferences.getString("assignment_version", null)),
            )
        }.getOrNull()
    }

    fun save(configuration: DeviceConfiguration) {
        require(configuration.baseUrl.startsWith("https://")) { "Only HTTPS endpoints are supported" }
        preferences.edit()
            .putString("base_url", configuration.baseUrl.trimEnd('/'))
            .putString("api_key", encrypt(configuration.apiKey))
            .putString("api_secret", encrypt(configuration.apiSecret))
            .putString("device_id", configuration.deviceId)
            .putString("branch_id", configuration.branchId)
            .putString("gate_id", configuration.gateId)
            .putString("direction_mode", configuration.directionMode)
            .putString("assignment_version", configuration.assignmentVersion)
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_LENGTH) { "Invalid encrypted preference" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, bytes.copyOfRange(0, IV_LENGTH)),
        )
        return cipher.doFinal(bytes.copyOfRange(IV_LENGTH, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "device_configuration"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "face_attendance_configuration_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
