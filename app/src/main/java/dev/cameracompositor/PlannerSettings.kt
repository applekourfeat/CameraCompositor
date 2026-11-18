package dev.cameracompositor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Personal-test credentials are entered on the phone and encrypted with Android Keystore. */
class PlannerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("scene_planner", Context.MODE_PRIVATE)
    val model get() = prefs.getString("model", "gemini-3.8-flash")!!
    val consent get() = prefs.getBoolean("consent_v1", false)
    val environmentConsent get() = prefs.getBoolean("environment_consent_v1", false)
    fun consentToEnvironmentUpload() { prefs.edit().putBoolean("environment_consent_v1",true).apply() }
    fun consentToUpload() { prefs.edit().putBoolean("consent_v1", true).apply() }
    fun key(): String? = runCatching {
        val encrypted = prefs.getString("credential", null) ?: return null
        val iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun save(key: String, model: String) {
        require(model.matches(Regex("gemini-[a-zA-Z0-9.-]+"))) { "Enter a Gemini model ID, such as gemini-3.8-flash." }
        val credential = key.trim()
        require(credential.length >= 20 && credential.none { it.isWhitespace() }) { "Paste the complete API key." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        prefs.edit().putString("credential", Base64.encodeToString(cipher.doFinal(credential.toByteArray()), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString("model", model).apply()
    }
    fun forget() { prefs.edit().remove("credential").remove("iv").remove("consent_v1").remove("environment_consent_v1").apply() }

    @Synchronized fun reserveScan() {
        val today = LocalDate.now().toString()
        val count = if (prefs.getString("day", null) == today) prefs.getInt("count", 0) else 0
        val now = System.currentTimeMillis()
        check(count < 20) { "This personal test build allows 20 scan attempts per day." }
        check(now - prefs.getLong("last_scan", 0) >= 15_000) { "Wait 15 seconds between scan attempts." }
        prefs.edit().putString("day", today).putInt("count", count + 1).putLong("last_scan", now).apply()
    }
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey("composition_personal_key", null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("composition_personal_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
