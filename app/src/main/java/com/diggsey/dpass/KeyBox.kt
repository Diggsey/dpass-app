package com.diggsey.dpass

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

object KeyBox {
    private const val TAG = "BiometricPrompt"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_NAME = "DPASS_KEY"
    private const val DATA_ENCRYPTED = "DATA_ENCRYPTED"
    private const val INITIALIZATION_VECTOR = "INITIALIZATION_VECTOR"
    private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
    private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_CBC
    private const val PADDING = KeyProperties.ENCRYPTION_PADDING_PKCS7

    private fun keyTransformation() =
        listOf(ALGORITHM, BLOCK_MODE, PADDING).joinToString(separator = "/")

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun createKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM, KEYSTORE)

        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(KEY_NAME, purposes)
            .setBlockModes(BLOCK_MODE)
            .setEncryptionPaddings(PADDING)
            .setUserAuthenticationRequired(true)
            .build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
//        keyStore.setKeyEntry(KEY_NAME, key, null, null)
//        return key
    }

    private fun getOrCreateKey(): SecretKey {
        try {
            val key = keyStore.getKey(KEY_NAME, null)
            if (key != null) {
                return key as SecretKey
            }
        } catch (err: Exception) {
            Log.e("KeyBox", err.toString())
        }
        return createKey()
    }

    private fun unlockCipher(context: Context, cipher: Cipher, block: (Cipher) -> Unit) {
        val prompt = BiometricPrompt.Builder(context).setTitle("dpass")
            .setAllowedAuthenticators(Authenticators.DEVICE_CREDENTIAL or Authenticators.BIOMETRIC_WEAK)
            .build()

        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    block(result.cryptoObject.cipher)
                }
            })
    }

    private fun preferencesName(context: Context): String {
        return "${context.packageName}_key"
    }

    private fun getCipher(): Cipher {
        return Cipher.getInstance(keyTransformation())
    }

    fun rememberSecureValue(context: Context, value: ByteArray) {
        val key = getOrCreateKey()
        val cipher = getCipher()
        cipher.init(Cipher.ENCRYPT_MODE, key)

        unlockCipher(context, cipher) {
            val iv = cipher.iv
            val encryptedData = cipher.doFinal(value)
            val sharedPreferences =
                context.getSharedPreferences(preferencesName(context), Context.MODE_PRIVATE)
            with(sharedPreferences.edit()) {
                putString(INITIALIZATION_VECTOR, Base64.encodeToString(iv, Base64.DEFAULT))
                putString(DATA_ENCRYPTED, Base64.encodeToString(encryptedData, Base64.DEFAULT))
            }
        }
    }

    fun recallSecureValue(context: Context, block: (ByteArray) -> Unit) {
        val key = getOrCreateKey()
        val sharedPreferences =
            context.getSharedPreferences(preferencesName(context), Context.MODE_PRIVATE)
        val iv =
            Base64.decode(sharedPreferences.getString(INITIALIZATION_VECTOR, ""), Base64.DEFAULT)
        val encryptedData =
            Base64.decode(sharedPreferences.getString(DATA_ENCRYPTED, ""), Base64.DEFAULT)
        if (iv.isEmpty() || encryptedData.isEmpty()) {
            return
        }
        val cipher = getCipher()
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

        unlockCipher(context, cipher) {
            val value = it.doFinal(encryptedData)
            block(value)
        }
    }
}