package com.example.fess.kotlinmassage1.registerlogin

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Toast
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.messages.LatestMessagesActivity
import com.example.fess.kotlinmassage1.util.TokenStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.iid.FirebaseInstanceId
import kotlinx.android.synthetic.main.activity_login.*


class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContentView(R.layout.activity_login)
        login_button_login.setOnClickListener {
            performLogin()
//
        }

        back_to_register_login.setOnClickListener {
            finish()
        }


    }

    private fun warmUpToken() {
        // Просто «греем» InstanceId, чтобы FCM выдал токен.
        // Запись в /user-tokens/{uid}/{deviceId} сделаем после успешного логина (TokenStore).
        FirebaseInstanceId.getInstance().instanceId
                .addOnSuccessListener { instanceIdResult ->
                    Log.d("LoginActivity", "fcm token: ${instanceIdResult.token}")
                }
                .addOnFailureListener {
                    Log.w("LoginActivity", "Не удалось получить FCM token: ${it.message}")
                }
    }


    private fun performLogin() {
        val email = email_edittext_login.text.toString()
        val password = password_edittext_login.text.toString()

        warmUpToken()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill out email/pw.", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                .addOnCompleteListener {
                    if (!it.isSuccessful) return@addOnCompleteListener

                    Log.d("Login", "Successfully logged in: ${it.result!!.user.uid}")

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