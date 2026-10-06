package com.example.fess.kotlinmassage1.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import com.example.fess.kotlinmassage1.models.User
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.squareup.picasso.Picasso
import java.util.concurrent.Executors

/**
 * Фоновый пул для «тяжёлых» операций с картинками:
 *  - декодирование base64 -> Bitmap (раньше делалось СИНХРОННО в RecyclerView.bind() —
 *    фризы при скролле чата с картинками);
 *  - сжатие выбранного фото -> base64 (раньше — голый Thread{} без пула).
 *
 * Корутин в проекте нет намеренно: не трогаем зависимости, один shared-пул из 2 потоков
 * закрывает обе задачи. Все колбэки возвращаются в main-поток.
 */
object ImageLoader {

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Ключ для setTag — не конфликтует с android.* id. */
    private val TAG_KEY = View.generateViewId()

    /**
     * base64-payload -> Bitmap в фоновом потоке; результат применяется к ImageView в main.
     * Защита от переиспользования ViewHolder: если к моменту готовности вьюха уже
     * привязана к другому тексту — результат игнорируется.
     */
    fun loadBase64ToView(data: String, target: android.widget.ImageView) {
        target.setTag(TAG_KEY, data)
        executor.execute {
            val bmp = ImageUtils.base64ToBitmap(data)
            target.post {
                if (target.getTag(TAG_KEY) == data && bmp != null) {
                    target.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Uri -> base64 в фоновом потоке; результат — в main. */
    fun compressUri(context: Context, uri: Uri, callback: (String?) -> Unit) {
        executor.execute {
            val b64 = ImageUtils.compressToBase64(context.applicationContext, uri)
            main.post { callback(b64) }
        }
    }

    /** Профиль пользователя по uid (однократное чтение); результат — в main. */
    fun fetchUser(uid: String, callback: (User?) -> Unit) {
        FirebaseDatabase.getInstance().getReference(DbPaths.user(uid))
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    main.post { callback(user) }
                }

                override fun onCancelled(error: DatabaseError) {
                    main.post { callback(null) }
                }
            })
    }

    /** Аватар: Picasso сам грузит асинхронно; режем превью, чтобы не тянуть полноразмер. */
    fun loadAvatarInto(url: String?, target: de.hdodenhof.circleimageview.CircleImageView) {
        if (url.isNullOrEmpty()) return
        Picasso.get().load(url).resize(160, 160).centerCrop().into(target)
    }
}
