package com.example.locationservice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var db: LocationDatabase
    private lateinit var apiClient: ApiClient
    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        private const val GOOGLE_SERVER_CLIENT_ID = "689121156650-flna9n8v9ugmg8bdbr4s3nemkg4qmsvu.apps.googleusercontent.com"
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        setLoading(false)
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        handleGoogleSignInResult(task)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = LocationDatabase.getDatabase(this)
        apiClient = ApiClient(this)

        setupGoogleSignIn()
        setupListeners()
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(GOOGLE_SERVER_CLIENT_ID)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setupListeners() {
        binding.btnGoogleSignIn.setOnClickListener {
            hideError()
            setLoading(true)
            // Sign out first to ensure the user gets account picker if needed
            googleSignInClient.signOut().addOnCompleteListener {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

        binding.btnSignIn.setOnClickListener {
            val username = binding.etUsername.text?.toString()?.trim() ?: ""
            val password = binding.etPassword.text?.toString() ?: ""

            if (username.isEmpty()) {
                showError("Please enter your username")
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                showError("Please enter your password")
                return@setOnClickListener
            }

            hideError()
            setLoading(true)

            lifecycleScope.launch {
                val result = apiClient.loginWithCredentials(username, password)
                setLoading(false)
                result.onSuccess { res ->
                    saveSessionAndProceed(res)
                }.onFailure { err ->
                    showError(err.message ?: "Authentication failed")
                }
            }
        }
    }

    private fun handleGoogleSignInResult(task: Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken

            if (!idToken.isNullOrEmpty()) {
                setLoading(true)
                lifecycleScope.launch {
                    val result = apiClient.loginWithGoogle(idToken)
                    setLoading(false)
                    result.onSuccess { res ->
                        saveSessionAndProceed(res)
                    }.onFailure { err ->
                        showError(err.message ?: "Google login failed on server")
                    }
                }
            } else {
                showError("Failed to obtain Google token from device")
            }
        } catch (e: ApiException) {
            Log.e("LoginActivity", "Google sign-in error code: ${e.statusCode}", e)
            if (e.statusCode == 12501) {
                // User cancelled the dialog, no error message needed
                hideError()
            } else {
                showError("Google sign-in error (${e.statusCode}): ${e.localizedMessage}")
            }
        }
    }

    private suspend fun saveSessionAndProceed(res: LoginResponse) {
        withContext(Dispatchers.IO) {
            if (!res.apiKey.isNullOrEmpty()) {
                db.settingDao().insert(Setting("api_key", res.apiKey))
            }
            if (!res.token.isNullOrEmpty()) {
                db.settingDao().insert(Setting("auth_token", res.token))
            }
            val username = res.user?.username ?: ""
            if (username.isNotEmpty()) {
                db.settingDao().insert(Setting("username", username))
            }
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(this@LoginActivity, "Welcome, ${res.user?.username ?: "User"}!", Toast.LENGTH_SHORT).show()
            val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSignIn.isEnabled = !loading
        binding.btnGoogleSignIn.isEnabled = !loading
        binding.etUsername.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.tvErrorMessage.text = message
        binding.tvErrorMessage.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvErrorMessage.visibility = View.GONE
    }
}

