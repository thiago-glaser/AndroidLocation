package com.example.locationservice

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class ApiKeyResponse(
    val success: Boolean,
    val apiKey: String?,
    val error: String?
)

class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    
    private val client = OkHttpClient()
    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        // Clear cookies to ensure fresh login state
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        
        // Spoof User-Agent to bypass Google's WebView block (disallowed_useragent)
        val defaultUserAgent = webView.settings.userAgentString
        val safeUserAgent = defaultUserAgent.replace("; wv", "").replace("Version/4.0 ", "")
        webView.settings.userAgentString = safeUserAgent
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkForAuthCookie()
            }
        }

        webView.loadUrl("https://travel-access.ddns.net/login")
    }

    private fun checkForAuthCookie() {
        val cookies = CookieManager.getInstance().getCookie("https://travel-access.ddns.net")
        if (cookies != null) {
            val cookieArray = cookies.split(";")
            for (cookie in cookieArray) {
                if (cookie.trim().startsWith("auth_token=")) {
                    val token = cookie.trim().substringAfter("auth_token=")
                    if (token.isNotEmpty()) {
                        generateNativeApiKey(token)
                        return
                    }
                }
            }
        }
    }

    private fun generateNativeApiKey(token: String) {
        webView.visibility = View.GONE
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apiKeyPayload = JSONObject().apply {
                    put("description", "Android App Key - " + android.os.Build.MODEL)
                }.toString()

                val apiKeyRequest = Request.Builder()
                    .url("https://travel-access.ddns.net/api/auth/api-keys")
                    .header("Authorization", "Bearer $token")
                    .post(apiKeyPayload.toRequestBody(JSON))
                    .build()

                val apiKeyResponse = client.newCall(apiKeyRequest).execute()
                val apiKeyBodyStr = apiKeyResponse.body?.string() ?: ""
                
                if (!apiKeyResponse.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(apiKeyBodyStr).getString("error")
                    } catch (e: Exception) {
                        "Failed to generate API Key"
                    }
                    showError(errorMsg)
                    return@launch
                }

                val apiKeyData = gson.fromJson(apiKeyBodyStr, ApiKeyResponse::class.java)
                val newApiKey = apiKeyData.apiKey

                if (newApiKey.isNullOrEmpty()) {
                    showError("No API Key received from server")
                    return@launch
                }

                val db = LocationDatabase.getDatabase(this@LoginActivity)
                db.settingDao().insert(Setting(key = "api_key", value = newApiKey))

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "Login Successful!", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }

            } catch (e: Exception) {
                showError("Network error: ${e.message}")
            }
        }
    }

    private suspend fun showError(msg: String) {
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            webView.visibility = View.VISIBLE
            Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
        }
    }
}
