package io.github.revocx35.rwr.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The paired server. The device token is encrypted with a non-exportable Android Keystore key. */
data class ServerAccount(
    val baseUrl: String,
    val pin: String?,
    val deviceId: Long,
    val deviceName: String,
    val canConfigure: Boolean,
    val token: String,
) {
    companion object {
        private const val FILE = "rwr_server"
        private const val KEY_ALIAS = "rwr_device_token"

        fun load(context: Context): ServerAccount? {
            val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val url = sp.getString("url", null) ?: return null
            val ct = sp.getString("token_ct", null) ?: return null
            val iv = sp.getString("token_iv", null) ?: return null
            val token = runCatching { decrypt(Base64.decode(ct, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP)) }
                .getOrNull() ?: return null
            return ServerAccount(
                url, sp.getString("pin", null), sp.getLong("device_id", 0), sp.getString("name", "") ?: "",
                sp.getBoolean("can_configure", false), token,
            )
        }

        fun save(context: Context, a: ServerAccount) {
            val (ct, iv) = encrypt(a.token)
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
                putString("url", a.baseUrl)
                putString("pin", a.pin)
                putLong("device_id", a.deviceId)
                putString("name", a.deviceName)
                putBoolean("can_configure", a.canConfigure)
                putString("token_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                putString("token_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            }
        }

        fun updateCanConfigure(context: Context, value: Boolean) =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("can_configure", value) }

        fun clear(context: Context) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { clear() }
            runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        }

        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun key(): SecretKey {
            (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }

        private fun encrypt(plain: String): Pair<ByteArray, ByteArray> {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            return c.doFinal(plain.toByteArray()) to c.iv
        }

        private fun decrypt(ct: ByteArray, iv: ByteArray): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return String(c.doFinal(ct))
        }
    }
}
