package com.wifigatekeeper.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Router admin credentials, stored encrypted on-device only
 * (EncryptedSharedPreferences backed by the Android Keystore).
 *
 * The Android Keystore is not bulletproof: OEM bugs, OS restores and
 * key-invalidation races surface as AEADBadTagException / KeyStoreException /
 * InvalidProtocolBufferException from [EncryptedSharedPreferences.create], which
 * would otherwise crash the app on EVERY launch (this store is built inside
 * Application.onCreate). Here a corrupted keyset is detected, wiped, and
 * recreated once; the user simply re-enters the router login.
 */
class CredentialsStore(context: Context) {

    private val prefs: SharedPreferences = createWithRecovery(context)

    /** True when a corrupted keyset had to be wiped (credentials are gone). */
    val recoveredFromCorruption: Boolean
        get() = recovered

    fun save(ip: String, username: String, password: String, kind: String) {
        prefs.edit()
            .putString(KEY_IP, ip.trim())
            .putString(KEY_USER, username.trim())
            .putString(KEY_PASS, password)
            .putString(KEY_KIND, kind)
            .apply()
    }

    // The password is OPTIONAL on purpose: many ISP routers (RastNet among
    // them) ship with an empty admin password. Only the IP gates "configured".
    fun hasCredentials(): Boolean = prefs.getString(KEY_IP, null)?.isNotBlank() == true

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
        const val PREF_FILE = "router_credentials"

        var recovered = false

        /**
         * Creates the encrypted prefs; on any Keystore/crypto failure wipes the
         * keyset and pref file and retries from scratch exactly once.
         */
        private fun createWithRecovery(context: Context): SharedPreferences {
            try {
                return create(context)
            } catch (first: Throwable) {
                if (first is kotlinx.coroutines.CancellationException) throw first
                recovered = true
                try {
                    // Remove the encrypted pref file and the androidx master-key
                    // alias so the next create() starts from a fresh keyset.
                    File(context.filesDir.parentFile, "shared_prefs/$PREF_FILE.xml").delete()
                    runCatching {
                        val keystore = java.security.KeyStore.getInstance("AndroidKeyStore")
                        keystore.load(null)
                        keystore.deleteEntry("__androidx_security_crypto_encrypted_prefs__")
                    }
                    return create(context)
                } catch (second: Throwable) {
                    if (second is kotlinx.coroutines.CancellationException) throw second
                    // Last resort: a plain prefs file so the app still launches
                    // and the user can log in again instead of crash-looping.
                    return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                }
            }
        }

        private fun create(context: Context): SharedPreferences =
            EncryptedSharedPreferences.create(
                context,
                PREF_FILE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
    }
}
