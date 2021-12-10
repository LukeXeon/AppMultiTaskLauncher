package open.source.multitask

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.core.os.HandlerCompat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class RemoteTask @JvmOverloads constructor(
    name: String,
    private val intent: Intent,
    dependencies: List<Class<out Task>> = emptyList()
) : Task(name, false, dependencies) {

    internal companion object {
        private var count = 0
        private var handler: Handler? = null

        internal suspend fun withDispatcher(action: suspend (Handler) -> Unit) {
            try {
                val h = synchronized(this) {
                    var h = handler
                    if (h == null) {
                        h = HandlerCompat.createAsync(
                            HandlerThread(
                                "startup-remote-dispatcher",
                                Process.THREAD_PRIORITY_FOREGROUND
                            ).apply {
                                start()
                            }.looper
                        )
                    }
                    count++
                    return@synchronized h
                }
                action(h)
            } finally {
                synchronized(this) {
                    val h = handler
                    if (--count == 0 && h != null) {
                        h.looper.quit()
                        handler = null
                    }
                }
            }
        }

    }

    final override suspend fun execute(application: Application) {
        withDispatcher { dispatcher ->
            try {
                suspendCoroutine<Unit> {
                    intent.setPackage(application.packageName)
                    PendingIntent.getBroadcast(
                        application,
                        0,
                        intent,
                        PendingIntent.FLAG_ONE_SHOT
                    ).send(
                        0,
                        { _, _, resultCode, _, bundle ->
                            when (resultCode) {
                                RemoteTaskExecutor.RESULT_OK -> {
                                    it.resume(Unit)
                                }
                                RemoteTaskExecutor.RESULT_EXCEPTION -> {
                                    it.resumeWithException(
                                        bundle!!.getParcelable<RemoteTaskException>(
                                            RemoteTaskExecutor.EXCEPTION_KEY
                                        )!!
                                    )
                                }
                                else -> {
                                    it.resumeWithException(AssertionError())
                                }
                            }
                        },
                        dispatcher
                    )
                }
            } catch (e: RemoteTaskException) {
                handleException(e)
            }
        }
    }

    open fun handleException(exception: RemoteTaskException) {
        throw exception
    }
}