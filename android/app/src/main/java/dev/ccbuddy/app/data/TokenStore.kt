package dev.ccbuddy.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists paired-session records (device name + long-lived token) in
 * EncryptedSharedPreferences, per the spec's security notes — never
 * plaintext on the phone side.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "buddy_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun all(): List<PeerSession> {
        val raw = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PeerSession(
                id = o.getString("id"),
                deviceName = o.getString("deviceName"),
                token = o.getString("token"),
                pairedAt = o.getLong("pairedAt"),
                connected = false
            )
        }
    }

    fun upsert(peer: PeerSession) {
        val existing = all().filterNot { it.id == peer.id }
        save(existing + peer)
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    private fun save(peers: List<PeerSession>) {
        val array = JSONArray()
        peers.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("deviceName", p.deviceName)
                    .put("token", p.token)
                    .put("pairedAt", p.pairedAt)
            )
        }
        prefs.edit().putString(KEY_PEERS, array.toString()).apply()
    }

    companion object {
        private const val KEY_PEERS = "peers"
    }
}
