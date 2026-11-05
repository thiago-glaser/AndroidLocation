package com.example.locationservice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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

    private enum class AuthMode {
        LOGIN,
        REGISTER,
        FORGOT_PASSWORD,
        FORGOT_USERNAME
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var db: LocationDatabase
    private lateinit var apiClient: ApiClient
    private lateinit var googleSignInClient: GoogleSignInClient
    private var currentMode = AuthMode.LOGIN

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
        setMode(AuthMode.LOGIN)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentMode != AuthMode.LOGIN) {
                    setMode(AuthMode.LOGIN)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(GOOGLE_SERVER_CLIENT_ID)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setMode(mode: AuthMode, keepMessages: Boolean = false) {
        currentMode = mode
        if (!keepMessages) {
            hideError()
            hideSuccess()
        }

        when (mode) {
            AuthMode.LOGIN -> {
                binding.tvSubtitle.text = "Sign in to your account"
                binding.tvModeDescription.visibility = View.GONE
                binding.btnGoogleSignIn.visibility = View.VISIBLE
                binding.btnGoogleSignIn.text = "Continue with Google"
                binding.layoutDivider.visibility = View.VISIBLE
                binding.tilUsername.visibility = View.VISIBLE
                binding.tilEmail.visibility = View.GONE
                binding.tilPassword.visibility = View.VISIBLE
                binding.btnSignIn.text = "Sign In"
                binding.tvSwitchMode.text = "Don't have an account? Register"
                binding.tvForgotPassword.visibility = View.VISIBLE
                binding.tvForgotUsername.visibility = View.VISIBLE
            }
            AuthMode.REGISTER -> {
                binding.tvSubtitle.text = "Create a new account"
                binding.tvModeDescription.visibility = View.GONE
                binding.btnGoogleSignIn.visibility = View.VISIBLE
                binding.btnGoogleSignIn.text = "Sign up with Google"
                binding.layoutDivider.visibility = View.VISIBLE
                binding.tilUsername.visibility = View.VISIBLE
                binding.tilEmail.visibility = View.VISIBLE
                binding.tilPassword.visibility = View.VISIBLE
                binding.btnSignIn.text = "Create Account"
                binding.tvSwitchMode.text = "Already have an account? Sign In"
                binding.tvForgotPassword.visibility = View.GONE
                binding.tvForgotUsername.visibility = View.GONE
            }
            AuthMode.FORGOT_PASSWORD -> {
                binding.tvSubtitle.text = "Recover your account password"
                binding.tvModeDescription.text = "Enter the email address associated with your account and we'll send you a link to reset your password."
                binding.tvModeDescription.visibility = View.VISIBLE
                binding.btnGoogleSignIn.visibility = View.GONE
                binding.layoutDivider.visibility = View.GONE
                binding.tilUsername.visibility = View.GONE
                binding.tilEmail.visibility = View.VISIBLE
                binding.tilPassword.visibility = View.GONE
                binding.btnSignIn.text = "Send Reset Link"
                binding.tvSwitchMode.text = "Back to Login"
                binding.tvForgotPassword.visibility = View.GONE
                binding.tvForgotUsername.visibility = View.GONE
            }
            AuthMode.FORGOT_USERNAME -> {
                binding.tvSubtitle.text = "Username Recovery"
                binding.tvModeDescription.text = "Enter the email address associated with your account and we will send your username to your inbox."
                binding.tvModeDescription.visibility = View.VISIBLE
                binding.btnGoogleSignIn.visibility = View.GONE
                binding.layoutDivider.visibility = View.GONE
                binding.tilUsername.visibility = View.GONE
                binding.tilEmail.visibility = View.VISIBLE
                binding.tilPassword.visibility = View.GONE
                binding.btnSignIn.text = "Send Username"
                binding.tvSwitchMode.text = "Back to Login"
                binding.tvForgotPassword.visibility = View.GONE
                binding.tvForgotUsername.visibility = View.GONE
            }
        }
    }

    private fun setupListeners() {
        binding.btnGoogleSignIn.setOnClickListener {
            hideError()
            hideSuccess()
            setLoading(true)
            // Sign out first to ensure the user gets account picker if needed
            googleSignInClient.signOut().addOnCompleteListener {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

        binding.tvSwitchMode.setOnClickListener {
            when (currentMode) {
                AuthMode.LOGIN -> setMode(AuthMode.REGISTER)
                AuthMode.REGISTER, AuthMode.FORGOT_PASSWORD, AuthMode.FORGOT_USERNAME -> setMode(AuthMode.LOGIN)
            }
        }

        binding.tvForgotPassword.setOnClickListener {
            setMode(AuthMode.FORGOT_PASSWORD)
        }

        binding.tvForgotUsername.setOnClickListener {
            setMode(AuthMode.FORGOT_USERNAME)
        }

        binding.btnSignIn.setOnClickListener {
            when (currentMode) {
                AuthMode.LOGIN -> handleLoginSubmit()
                AuthMode.REGISTER -> handleRegisterSubmit()
                AuthMode.FORGOT_PASSWORD -> handleForgotPasswordSubmit()
                AuthMode.FORGOT_USERNAME -> handleForgotUsernameSubmit()
            }
        }
    }

    private fun handleLoginSubmit() {
        val username = binding.etUsername.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""

        if (username.isEmpty()) {
            showError("Please enter your username")
            return
        }

        if (password.isEmpty()) {
            showError("Please enter your password")
            return
        }

        hideError()
        hideSuccess()
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

    private fun handleRegisterSubmit() {
        val username = binding.etUsername.text?.toString()?.trim() ?: ""
        val email = binding.etEmail.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""

        if (username.isEmpty()) {
            showError("Please choose a username")
            return
        }
        if (email.isEmpty()) {
            showError("Please enter your email address")
            return
        }
        if (password.isEmpty()) {
            showError("Please create a password")
            return
        }

        hideError()
        hideSuccess()
        setLoading(true)

        lifecycleScope.launch {
            val result = apiClient.registerAccount(username, email, password)
            setLoading(false)
            result.onSuccess { msg ->
                binding.etPassword.setText("")
                setMode(AuthMode.LOGIN, keepMessages = true)
                showSuccess(msg)
            }.onFailure { err ->
                showError(err.message ?: "Registration failed")
            }
        }
    }

    private fun handleForgotPasswordSubmit() {
        val email = binding.etEmail.text?.toString()?.trim() ?: ""
        if (email.isEmpty()) {
            showError("Please enter your email address")
            return
        }

        hideError()
        hideSuccess()
        setLoading(true)

        lifecycleScope.launch {
            val result = apiClient.forgotPassword(email)
            setLoading(false)
            result.onSuccess { msg ->
                showSuccess(msg)
            }.onFailure { err ->
                showError(err.message ?: "Failed to send reset link")
            }
        }
    }

    private fun handleForgotUsernameSubmit() {
        val email = binding.etEmail.text?.toString()?.trim() ?: ""
        if (email.isEmpty()) {
            showError("Please enter your email address")
            return
        }

        hideError()
        hideSuccess()
        setLoading(true)

        lifecycleScope.launch {
            val result = apiClient.forgotUsername(email)
            setLoading(false)
            result.onSuccess { msg ->
                binding.etEmail.setText("")
                showSuccess(msg)
            }.onFailure { err ->
                showError(err.message ?: "Failed to send username")
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
            if (!res.token.isNullOrEmpty()) {
                db.settingDao().insert(Setting("auth_token", res.token))
            }
            if (!res.apiKey.isNullOrEmpty()) {
                db.settingDao().insert(Setting("api_key", res.apiKey))
            } else {
                val existingKey = db.settingDao().getSetting("api_key")?.value
                if (existingKey.isNullOrEmpty()) {
                    apiClient.ensureApiKey(res.token)
                }
            }
            val username = res.user?.username ?: ""
            if (username.isNotEmpty()) {
                db.settingDao().insert(Setting("username", username))
            }
            val unitSystem = (res.unitSystem ?: res.user?.unitSystem ?: "METRIC").uppercase()
            db.settingDao().insert(Setting("unit_system", if (unitSystem == "IMPERIAL") "IMPERIAL" else "METRIC"))
            apiClient.ensureDeviceRegistered(res.token)
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
        binding.etEmail.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.tvSwitchMode.isEnabled = !loading
        binding.tvForgotPassword.isEnabled = !loading
        binding.tvForgotUsername.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.tvErrorMessage.text = message
        binding.tvErrorMessage.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvErrorMessage.visibility = View.GONE
    }

    private fun showSuccess(message: String) {
        binding.tvSuccessMessage.text = message
        binding.tvSuccessMessage.visibility = View.VISIBLE
    }

    private fun hideSuccess() {
        binding.tvSuccessMessage.visibility = View.GONE
    }
}
