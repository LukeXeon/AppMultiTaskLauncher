package open.source.multitask

import android.os.SystemClock
import android.util.Log


internal suspend fun <T> trace(tag: String, message: String, action: suspend () -> T): T {
    val start = SystemClock.uptimeMillis()
    val r = action()
    Log.d(tag, "trace $message : ${SystemClock.uptimeMillis() - start}")
    return r
}