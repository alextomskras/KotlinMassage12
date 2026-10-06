package com.example.fess.kotlinmassage1.messages

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.models.ChatMessage
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.registerlogin.RegisterActivity
import com.example.fess.kotlinmassage1.util.DbPaths
import com.example.fess.kotlinmassage1.util.NotificationHelper
import com.example.fess.kotlinmassage1.util.TokenStore
import com.example.fess.kotlinmassage1.views.DialogItem
import com.example.fess.kotlinmassage1.views.LatestKartinkaMessageRow
import com.example.fess.kotlinmassage1.views.LatestMessageRow
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.Item
import com.xwray.groupie.ViewHolder

class LatestMessagesActivity : AppCompatActivity() {

    companion object {
        /**
         * Текущий юзер нужен ChatLog для подписи исходящих сообщений.
         * TODO: заменить на ViewModel/репозиторий (глобальный mutable state — долгий рефакторинг).
         * Пока хотя бы сбрасываем при logout, чтобы не показывать чужой профиль.
         */
        var currentUser: User? = null
        const val TAG = "LatestMessages"
    }

    private val adapter = GroupAdapter<ViewHolder>()
    // LinkedHashMap сохраняет порядок вставки Firebase (по ключам push)
    private val latestMessagesMap = LinkedHashMap<String, ChatMessage>()
    private var latestListener: ChildEventListener? = null
    private var latestRef: DatabaseReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_latest_messages)
        NotificationHelper.ensureChannel(this)

        val recycler = findViewById<RecyclerView>(R.id.recyclerview_latest_messages)
        recycler.adapter = adapter
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        // Раньше здесь был жёсткий cast `item as LatestKartinkaMessageRow` — краш, когда
        // в списке попадался текстовый диалог. Теперь общий интерфейс DialogItem.
        adapter.setOnItemClickListener { item, _ ->
            val partner = (item as? DialogItem)?.chatPartnerUser ?: return@setOnItemClickListener
            val intent = Intent(this, ChatLogActivity::class.java)
            intent.putExtra(NewMessageActivity.USER_KEY, partner)
            startActivity(intent)
        }

        listenForLatestMessages()
        fetchCurrentUser()
        verifyUserIsLoggedIn()
    }

    private fun refreshRecyclerViewMessages() {
        val items: List<Item<*>> = latestMessagesMap.values.map { msg ->
            if (msg.type == ChatMessage.TYPE_IMAGE) LatestKartinkaMessageRow(msg)
            else LatestMessageRow(msg)
        }
        // было: adapter.clear() + add() по всему списку на КАЖДОЕ событие -> O(n^2) и фризы
        adapter.updateWithDiff(items)
    }

    private fun listenForLatestMessages() {
        val fromId = FirebaseAuth.getInstance().uid ?: return
        val ref = FirebaseDatabase.getInstance().getReference(DbPaths.latestRoot(fromId))

        val listener = object : ChildEventListener {
            override fun onChildAdded(p0: DataSnapshot, p1: String?) {
                val chatMessage = p0.getValue(ChatMessage::class.java) ?: return
                latestMessagesMap[p0.key!!] = chatMessage
                refreshRecyclerViewMessages()
            }

            override fun onChildChanged(p0: DataSnapshot, p1: String?) {
                val chatMessage = p0.getValue(ChatMessage::class.java) ?: return
                latestMessagesMap[p0.key!!] = chatMessage
                refreshRecyclerViewMessages()
            }

            override fun onChildRemoved(p0: DataSnapshot) {
                p0.key?.let { latestMessagesMap.remove(it) }
                refreshRecyclerViewMessages()
            }

            override fun onChildMoved(p0: DataSnapshot, p1: String?) {}

            override fun onCancelled(p0: DatabaseError) {
                Log.w(TAG, "latest-messages listener cancelled: ${p0.message}")
            }
        }
        latestListener = listener
        latestRef = ref
        ref.addChildEventListener(listener)
    }

    private fun fetchCurrentUser() {
        val uid = FirebaseAuth.getInstance().uid ?: return
        FirebaseDatabase.getInstance().getReference(DbPaths.user(uid))
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(p0: DataSnapshot) {
                    currentUser = p0.getValue(User::class.java)
                    Log.d(TAG, "Current user loaded")
                }

                override fun onCancelled(p0: DatabaseError) {
                    Toast.makeText(this@LatestMessagesActivity,
                        "Не удалось загрузить профиль", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun verifyUserIsLoggedIn() {
        if (FirebaseAuth.getInstance().uid == null) {
            val intent = Intent(this, RegisterActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new_message -> {
                startActivity(Intent(this, NewMessageActivity::class.java))
            }
            R.id.menu_sign_out -> {
                // Убираем FCM-токен этого устройства из /user-tokens/{uid}/{deviceId},
                // иначе бэкенд продолжит слать пуши на logout-аккаунт.
                TokenStore.removeCurrentToken(this)
                FirebaseAuth.getInstance().signOut()
                currentUser = null
                val intent = Intent(this, RegisterActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.nav_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onDestroy() {
        // Снимаем слушателя — иначе активити утекает в Firebase навсегда
        val listener = latestListener
        if (listener != null) latestRef?.removeEventListener(listener)
        latestListener = null
        latestRef = null
        super.onDestroy()
    }
}
