package com.example.fess.kotlinmassage1.registerlogin





import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.messages.LatestMessagesActivity
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.util.TokenStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.example.fess.kotlinmassage1.util.ImageUtils
import java.util.*


class RegisterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.fess.kotlinmassage1.util.NotificationHelper.ensureChannel(this)
        setContentView(R.layout.activity_register)




        findViewById<android.widget.Button>(com.example.fess.kotlinmassage1.R.id.register_button_register).setOnClickListener {
            performRegister()
        }

        findViewById<android.widget.TextView>(com.example.fess.kotlinmassage1.R.id.already_have_accaunt_text_view).setOnClickListener {
            Log.d("RegisterActivity", "Try show log activity")
            //Lounch login activity somehow
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }

        findViewById<android.widget.Button>(com.example.fess.kotlinmassage1.R.id.select_photo_button_register).setOnClickListener {
            Log.d("RegisterActivity", "Try select photo")

            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, 0)
        }


    }

    var selectedPhotoUri: Uri? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 0 && resultCode == Activity.RESULT_OK && data != null) {
            Log.d("RegisterActivity", "Photo was selected")

            selectedPhotoUri = data.data

            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, selectedPhotoUri)

            findViewById<de.hdodenhof.circleimageview.CircleImageView>(com.example.fess.kotlinmassage1.R.id.select_photoview_register).setImageBitmap(bitmap)

            findViewById<android.widget.Button>(com.example.fess.kotlinmassage1.R.id.select_photo_button_register).alpha = 0f

            //  val bitmapDrawable = BitmapDrawable(bitmap)
            // select_photo_button_register.setBackgroundDrawable(bitmapDrawable)

        }
    }

    private fun performRegister() {

        val email = findViewById<android.widget.EditText>(com.example.fess.kotlinmassage1.R.id.email_edittext_register).text.toString()
        val password = findViewById<android.widget.EditText>(com.example.fess.kotlinmassage1.R.id.password_edittext_register).text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email/pw", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("RegisterActivity", "Email is: " + email)
        Log.d("RegisterActivity", "Password is: $password")
        //Firebase Auth for create User whith Email and password

        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener {
                    if (!it.isSuccessful) return@addOnCompleteListener
                    //Else if successful
                    Log.d("Main", "Succefful create user: ${it.result!!.user!!.uid}")

                    val avatarB64 = if (selectedPhotoUri != null)
                        ImageUtils.compressToBase64(this, selectedPhotoUri!!) ?: "" else ""
                    saveUserToFirebaseDatabase(avatarB64)
                }
                .addOnFailureListener {
                    Log.d("Main", "Failed create user: ${it.message}")
                    Toast.makeText(this, "Failed create user: ${it.message}", Toast.LENGTH_SHORT).show()
                }
    }


    private fun saveUserToFirebaseDatabase(profileImageUrl: String) {


        val uid = FirebaseAuth.getInstance().uid ?: ""
        val ref = FirebaseDatabase.getInstance().getReference("/users/$uid")

        val user = User(uid, findViewById<android.widget.EditText>(com.example.fess.kotlinmassage1.R.id.username_edittext_register).text.toString(), profileImageUrl)

        ref.setValue(user)
                .addOnSuccessListener {
                    Log.d("Register", "Finally save user to firebasedatabase")

                    // Сохраняем FCM-токен нового юзера по схеме /user-tokens/{uid}/{deviceId}
                    TokenStore.saveCurrentToken(this)

                    val intent = Intent(this, LatestMessagesActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK.or(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)

                }
                .addOnFailureListener {
                    Log.d("Register", "Failed set value to firebasedatabase ${it.message}")
                }
    }


}

