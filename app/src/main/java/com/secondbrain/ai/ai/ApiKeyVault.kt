package com.secondbrain.ai.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypt only the billable API key; all user task records remain ordinary on-device data. */
class ApiKeyVault(private val context: Context) {
    private val preferences = context.getSharedPreferences("secondbrain_api_key", Context.MODE_PRIVATE)
    private val alias="SecondBrainApiSecret"
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun save(raw:String) {
        val value=raw.trim()
        require(value.isNotEmpty())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        preferences.edit().putString("key",Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).apply()
    }
    fun get():String? { return try {
        val payload=Base64.decode(preferences.getString("key",null)?:return null,Base64.NO_WRAP)
        if(payload.size<28) return null
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,payload.copyOfRange(0,12)))
        String(cipher.doFinal(payload.copyOfRange(12,payload.size)),Charsets.UTF_8)
    } catch(_:Exception){ null }
    }
    fun clear(){ preferences.edit().remove("key").apply() }
    fun exists():Boolean = !get().isNullOrBlank()
}
