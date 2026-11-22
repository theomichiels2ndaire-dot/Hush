package com.theo.remote

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Connexion à TON compte Tidal (OAuth « Authorization Code + PKCE », la méthode officielle).
 * Tu te connectes sur la vraie page de Tidal dans le navigateur : Hush ne voit jamais ton mot de passe,
 * il reçoit juste un jeton d'accès en lecture (profil, collection, playlists).
 */
object TidalAuth {

    const val REDIRECT_URI = "hush://auth"
    private const val SCOPES = "user.read collection.read collection.write playlists.read playlists.write"
    private const val AUTHORIZE = "https://login.tidal.com/authorize"
    private const val TOKEN = "https://auth.tidal.com/v1/oauth2/token"

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    /** Message d'erreur de la dernière tentative de connexion (null si tout va bien). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Le compte a-t-il donné le droit de modifier tes playlists ? (anciennes connexions : non) */
    private val _canWrite = MutableStateFlow(false)
    val canWrite: StateFlow<Boolean> = _canWrite.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush_auth", Context.MODE_PRIVATE)
        prefs = p
        _loggedIn.value = p.getString("refresh", null) != null || p.getString("access", null) != null
        _canWrite.value = p.getString("scopes", "")!!.contains("playlists.write")
    }

    /** [extra] : droits en plus (ex. « playback entitlements.read » pour le test de lecture directe). */
    fun login(context: Context, extra: String = "") {
        val p = prefs ?: return
        _error.value = null
        val verifier = randomUrlSafe(64)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(16)
        p.edit().putString("verifier", verifier).putString("state", state).putString("asked", (SCOPES + " " + extra).trim()).apply()

        val url = AUTHORIZE +
            "?response_type=code" +
            "&client_id=${enc(BuildConfig.TIDAL_CLIENT_ID)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&scope=${enc((SCOPES + " " + extra).trim())}" +
            "&code_challenge_method=S256" +
            "&code_challenge=$challenge" +
            "&state=$state"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun logout() {
        prefs?.edit()?.clear()?.apply()
        _loggedIn.value = false
        _canWrite.value = false
    }

    /** Appelé quand Tidal renvoie vers hush://auth?code=… */
    fun handleRedirect(uri: Uri) {
        if (uri.scheme != "hush") return
        val p = prefs ?: return
        val err = uri.getQueryParameter("error")
        if (err != null) {
            _error.value = "Connexion refusée par Tidal : ${uri.getQueryParameter("error_description") ?: err}"
            return
        }
        val code = uri.getQueryParameter("code") ?: return
        if (uri.getQueryParameter("state") != p.getString("state", null)) {
            _error.value = "Réponse de connexion inattendue, réessaie."
            return
        }
        val verifier = p.getString("verifier", null) ?: return
        scope.launch {
            try {
                val body = "grant_type=authorization_code" +
                    "&client_id=${enc(BuildConfig.TIDAL_CLIENT_ID)}" +
                    "&code=${enc(code)}" +
                    "&redirect_uri=${enc(REDIRECT_URI)}" +
                    "&code_verifier=${enc(verifier)}"
                saveTokens(postToken(body), initial = true)
                p.edit().remove("verifier").remove("state").apply()
                _loggedIn.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Échec de la connexion"
            }
        }
    }

    /** Jeton valide, rafraîchi tout seul quand il expire. */
    suspend fun accessToken(): String {
        val p = prefs ?: throw TidalApi.ApiException("Pas connecté")
        val access = p.getString("access", null)
        if (access != null && System.currentTimeMillis() < p.getLong("expires", 0)) return access
        val refresh = p.getString("refresh", null) ?: run {
            logout()
            throw TidalApi.ApiException("Connexion expirée, reconnecte ton compte")
        }
        return try {
            val body = "grant_type=refresh_token" +
                "&client_id=${enc(BuildConfig.TIDAL_CLIENT_ID)}" +
                "&refresh_token=${enc(refresh)}"
            saveTokens(postToken(body))
            p.getString("access", null)!!
        } catch (e: Exception) {
            logout()
            throw TidalApi.ApiException("Connexion expirée, reconnecte ton compte")
        }
    }

    /** Les droits que ton compte a donnés à Hush. */
    fun scopes(): String = prefs?.getString("scopes", "") ?: ""

    fun invalidate() {
        prefs?.edit()?.putLong("expires", 0)?.apply()
    }

    private fun saveTokens(j: JSONObject, initial: Boolean = false) {
        val p = prefs ?: return
        val e = p.edit()
            .putString("access", j.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000)
        j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { e.putString("refresh", it) }
        // Tidal renvoie les droits accordés ; à défaut on suppose ceux demandés
        val granted = j.optString("scope").ifBlank { if (initial) (p.getString("asked", null) ?: SCOPES) else p.getString("scopes", "")!! }
        e.putString("scopes", granted)
        e.apply()
        _canWrite.value = granted.contains("playlists.write")
    }

    private suspend fun postToken(form: String): JSONObject = withContext(Dispatchers.IO) {
        // D'abord comme appli « publique » (PKCE seul), sinon avec le secret
        val first = post(form, withSecret = false)
        if (first.first in 200..299) return@withContext JSONObject(first.second)
        val second = post(form, withSecret = true)
        if (second.first in 200..299) return@withContext JSONObject(second.second)
        throw TidalApi.ApiException("Tidal a refusé la connexion (${second.first}) ${second.second.take(160)}")
    }

    private fun post(form: String, withSecret: Boolean): Pair<Int, String> {
        val c = URL(TOKEN).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        if (withSecret) {
            val basic = Base64.encodeToString(
                "${BuildConfig.TIDAL_CLIENT_ID}:${BuildConfig.TIDAL_CLIENT_SECRET}".toByteArray(), Base64.NO_WRAP,
            )
            c.setRequestProperty("Authorization", "Basic $basic")
        }
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.outputStream.use { it.write(form.toByteArray()) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to body
    }

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b64url(b)
    }

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
