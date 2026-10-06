package com.example.fess.kotlinmassage1.registerlogin





import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.widget.Toast
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.messages.LatestMessagesActivity
import com.example.fess.kotlinmassage1.util.TokenStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging


class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.fess.kotlinmassage1.util.NotificationHelper.ensureChannel(this)


        setContentView(R.layout.activity_login)
        findViewById<android.widget.Button>(com.example.fess.kotlinmassage1.R.id.login_button_login).setOnClickListener {
            performLogin()
//
        }

        findViewById<android.widget.TextView>(com.example.fess.kotlinmassage1.R.id.back_to_register_login).setOnClickListener {
            finish()
        }


    }

    private fun warmUpToken() {
        // Просто «греем» InstanceId, чтобы FCM выдал токен.
        // Запись в /user-tokens/{uid}/{deviceId} сделаем после успешного логина (TokenStore).
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    Log.d("LoginActivity", "fcm token: $token")
                }
                .addOnFailureListener {
                    Log.w("LoginActivity", "Не удалось получить FCM token: ${it.message}")
                }
    }


    private fun performLogin() {
        val email = findViewById<android.widget.EditText>(com.example.fess.kotlinmassage1.R.id.email_edittext_login).text.toString()
        val password = findViewById<android.widget.EditText>(com.example.fess.kotlinmassage1.R.id.password_edittext_login).text.toString()

        warmUpToken()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill out email/pw.", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                .addOnCompleteListener {
                    if (!it.isSuccessful) return@addOnCompleteListener

                    Log.d("Login", "Successfully logged in: ${it.result!!.user!!.uid}")

                    // Юзер залогинен — прописываем его токен в БД по схеме user-tokens/{uid}/{deviceId}
                    TokenStore.saveCurrentToken(this)

                    val intent = Intent(this, LatestMessagesActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK.or(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to log in: ${it.message}", Toast.LENGTH_SHORT).show()
                }
    }

}