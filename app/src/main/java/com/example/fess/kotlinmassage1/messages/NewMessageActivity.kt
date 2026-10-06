package com.example.fess.kotlinmassage1.messages





import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.example.fess.kotlinmassage1.R
//import com.example.fess.kotlinmassage1.registerlogin.User
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.example.fess.kotlinmassage1.models.User
import com.squareup.picasso.Picasso
import com.example.fess.kotlinmassage1.views.ChatRecyclerAdapter
import com.example.fess.kotlinmassage1.views.ChatRowDelegate
import androidx.recyclerview.widget.RecyclerView

class NewMessageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_message)

        supportActionBar?.title = "Select User"



        fetchUsers()
    }

    companion object {
        val USER_KEY = "USER_KEY"

    }

    private fun fetchUsers() {
        val ref = FirebaseDatabase.getInstance().getReference("/users")
        ref.addListenerForSingleValueEvent(object : ValueEventListener {

            override fun onDataChange(p0: DataSnapshot) {
                val adapter = ChatRecyclerAdapter()

                p0.children.forEach {
                    Log.d("NewMessage", it.toString())
                    val user = it.getValue(User::class.java)
                    if (user != null) {
                        adapter.append(UserItem(user))
                    }
                }

                adapter.onItemClickListener = { item, view ->

                    val userItem = item as UserItem

                    val intent = Intent(view.context, ChatLogActivity::class.java)
                    //   intent.putExtra(USER_KEY, userItem.user.username)
                    intent.putExtra(USER_KEY, userItem.user)
                    startActivity(intent)

                    finish()
                }

                //recyclerview_newmessage.adapter = adapter
                findViewById<androidx.recyclerview.widget.RecyclerView>(com.example.fess.kotlinmassage1.R.id.recyclerview_newmessage).adapter = adapter
            }

            override fun onCancelled(p0: DatabaseError) {

            }
        })
    }
}

class UserItem(val user: User) : ChatRowDelegate {
    override val chatPartnerUser: User? get() = user

    override fun layoutRes(): Int = R.layout.user_row_new_message

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<android.widget.TextView>(R.id.username_textview_new_message).text = user.username

        Picasso.get().load(user.profileImageUrl).into(viewHolder.itemView.findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.imageview_new_message))
    }
}

//class   user
// this is super tedious

//class CustomAdapter: RecyclerView.Adapter<ViewHolder> {
//  override fun onBindViewHolder(p0:, p1: Int) {
//    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//  }
//}

//}
