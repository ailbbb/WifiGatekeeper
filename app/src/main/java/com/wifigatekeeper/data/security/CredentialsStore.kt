package com.wifigatekeeper.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Router admin credentials, stored encrypted on-device only
 * (EncryptedSharedPreferences backed by the Android Keystore).
 */
class CredentialsStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "router_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(ip: String, username: String, password: String, kind: String) {
        prefs.edit()
            .putString(KEY_IP, ip.trim())
            .putString(KEY_USER, username.trim())
            .putString(KEY_PASS, password)
            .putString(KEY_KIND, kind)
            .apply()
    }

    fun hasCredentials(): Boolean = prefs.getString(KEY_IP, null)?.isNotBlank() == true &&
        prefs.getString(KEY_PASS, null)?.isNotBlank() == true

    fun ip(): String = prefs.getString(KEY_IP, null).orEmpty()

    fun username(): String = prefs.getString(KEY_USER, "admin").orEmpty()

    fun password(): String = prefs.getString(KEY_PASS, null).orEmpty()

    fun kind(): String = prefs.getString(KEY_KIND, null) ?: com.wifigatekeeper.core.router.RouterKind.TP_LINK_CLASSIC.name

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_IP = "router_ip"
        const val KEY_USER = "admin_username"
        const val KEY_PASS = "admin_password"
        const val KEY_KIND = "router_kind"
    }
}
