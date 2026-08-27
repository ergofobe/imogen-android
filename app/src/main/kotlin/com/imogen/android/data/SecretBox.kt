package com.imogen.android.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM against a key the app never holds.
 *
 * Refresh tokens are the whole account: one is worth a password. Android keeps app files
 * private already, but "private unless the device is rooted, or restored from a backup, or
 * pulled off over ADB with developer options on" is a weaker claim than it sounds. The key
 * lives in the platform keystore, hardware-backed where there is hardware for it, and
 * cannot be read out — only used.
 *
 * Deliberately not `EncryptedSharedPreferences`: that library has sat in alpha for years
 * and is now deprecated, and this is forty lines.
 */
object SecretBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "imogen.accounts"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val NONCE_BYTES = 12

    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No nonce is supplied: GCM must never reuse one, and the provider's own
        // generator is a better source of that guarantee than anything written here.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plaintext)
        return cipher.iv + sealed
    }

    /**
     * Returns null when the ciphertext cannot be opened, which in practice means the key
     * is gone — a restore onto a new device, or the user clearing app data. There is
     * nothing to recover in that case; the accounts are simply signed out.
     */
    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= NONCE_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
            )
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Not tied to a screen lock: the backup worker runs while the phone is in
                // a pocket, and a key it cannot use then is a backup that never happens.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
