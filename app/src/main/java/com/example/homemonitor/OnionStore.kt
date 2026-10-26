package com.example.homemonitor

import android.content.Context
import android.content.SharedPreferences
import com.google.zxing.Result
import com.google.zxing.client.android.result.ParsedResult
import com.google.zxing.client.android.result.ResultParser
import java.util.*

/**
 * Speichert gescannte / bekannte Onion-Adressen in SharedPreferences.
 * Enthält zudem eine Prüfung, ob ein QR-Code-Inhalt eine Onion-Adresse ist.
 */
class OnionStore(private val prefs: SharedPreferences) {

    private const val KEY_ONIONS = "onions_v1"

    companion object {
        @Volatile private var instance: OnionStore? = null
        fun init(context: Context): OnionStore {
            return instance ?: synchronized(this) {
                instance ?: OnionStore(context.getSharedPreferences("homemonitor_prefs", Context.MODE_PRIVATE))
                    .also { instance = it }
            }
        }
        fun get(): OnionStore? = instance
    }

    val addresses: List<String>
        get() = prefs.getStringSet(KEY_ONIONS, emptySet())?.toList() ?: emptyList()

    fun add(address: String) {
        val set = mutableSetOf<String>()
        set.addAll(addresses)
        set.add(address.lowercase())
        prefs.edit().putStringSet(KEY_ONIONS, set).apply()
    }

    fun remove(address: String) {
        val set = mutableSetOf<String>()
        set.addAll(addresses)
        set.remove(address.lowercase())
        prefs.edit().putStringSet(KEY_ONIONS, set).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ONIONS).apply()
    }

    /**
     * Prüft, ob der Text eine valide Onion-Adresse ist (v2: 16 Zeichen, v3: 56 Zeichen, Base32).
     */
    fun isOnionAddress(text: String): Boolean {
        val t = text.trim().lowercase()
        val body = if (t.endsWith(".onion")) t.removeSuffix(".onion") else t
        return when {
            body.length == 16 -> body.matches(Regex("[a-z2-7]+")) // v2
            body.length == 56 -> body.matches(Regex("[a-z2-7]+")) // v3
            else -> false
        }
    }

    /**
     * ZXing parsen und ggf. die Onion-Adresse extrahieren (manche QR-Codes enthalten
     * eine http(s)-URL mit .onion-Host).
     */
    fun extractOnion(raw: String): String? {
        val t = raw.trim().lowercase()
        if (isOnionAddress(t)) return if (t.endsWith(".onion")) t else "$t.onion"
        // URL wie http://xyz.onion/... oder http://xyz.onion
        val m = Regex("https?://([a-z2-7]{16}|[a-z2-7]{56})\\.onion").find(t)
        return m?.groupValues?.get(1)?.let { "$it.onion" }
    }
}
