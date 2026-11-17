package app.ikev2manager.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.ikev2manager.vpn.VpnCredentials
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key held in the Android Keystore. Output = 12-byte IV || ciphertext+tag. */
class AndroidKeystoreCipher(private val alias: String = "ikev2manager_credentials_v1") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** Credentials live only as an encrypted blob in app-private storage; never in Room, prefs, logs or exports. */
class CredentialStore(private val file: File, private val cipher: SecretCipher) {

    @Synchronized
    fun save(c: VpnCredentials) {
        require(c.username.isNotBlank()) { "Username is empty" }
        require('\u0000' !in c.username) { "Username contains an invalid character" }
        val plain = (c.username + "\u0000" + c.password).toByteArray(Charsets.UTF_8)
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(cipher.encrypt(plain))
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "Could not store credentials" }
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Returns null if nothing is stored or the key is no longer usable (e.g. keystore reset). */
    @Synchronized
    fun load(): VpnCredentials? {
        if (!file.exists()) return null
        return try {
            val plain = cipher.decrypt(file.readBytes())
            val s = String(plain, Charsets.UTF_8)
            plain.fill(0)
            val i = s.indexOf('\u0000')
            if (i <= 0) null else VpnCredentials(s.substring(0, i), s.substring(i + 1))
        } catch (e: Exception) {
            null
        }
    }

    fun hasCredentials(): Boolean = file.exists()

    @Synchronized
    fun clear() { file.delete() }
}
