package open.source.multitask

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.*
import androidx.collection.ArrayMap
import androidx.core.app.BundleCompat
import kotlinx.coroutines.runBlocking
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.properties.Delegates
import kotlin.reflect.KClass


open class RemoteTaskExecutor : ContentProvider() {

    companion object {
        private const val BINDER_KEY = "binder"
        private val EXECUTOR = ThreadPoolExecutor(
            0,
            max(4, Runtime.getRuntime().availableProcessors()),
            10,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            object : ThreadFactory {

                private val count = AtomicInteger(0)

                override fun newThread(r: Runnable): Thread {
                    return object : Thread("startup-remote-" + count.getAndIncrement()) {
                        override fun run() {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                            r.run()
                        }
                    }
                }
            }
        )
        private val ALIVE_BINDER = Binder()
    }

    private val tasks = ArrayMap<String, BooleanArray>()

    final override fun onCreate(): Boolean {
        return true
    }

    open val classLoader: ClassLoader?
        get() = javaClass.classLoader

    final override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle {
        val callback = IRemoteTaskCallback.Stub.asInterface(
            BundleCompat.getBinder(
                extras ?: throw AssertionError(), BINDER_KEY
            )
        )
        EXECUTOR.execute {
            try {
                val lock = synchronized(tasks) {
                    tasks.getOrPut(method) {
                        booleanArrayOf(false)
                    }
                }
                synchronized(lock) {
                    if (!lock[0]) {
                        val application = context!!.applicationContext as Application
                        val executor = Class.forName(method, false, classLoader)
                            .getDeclaredConstructor()
                            .apply {
                                isAccessible = true
                            }.newInstance() as TaskExecutor
                        runBlocking { executor.execute(application) }
                        lock[0] = true
                    }
                }
                callback.onCompleted()
            } catch (e: Throwable) {
                callback.onException(RemoteTaskException(e))
            }
        }
        return Bundle().apply {
            BundleCompat.putBinder(this, BINDER_KEY, ALIVE_BINDER)
        }
    }

    final override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    final override fun getType(uri: Uri): String? = null

    final override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? = null

    final override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    final override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    internal class Client(
        private val process: String,
        private val type: KClass<out TaskExecutor>,
        private val uncaughtExceptionHandler: Thread.UncaughtExceptionHandler
    ) : TaskExecutor {

        override suspend fun execute(application: Application) {
            try {
                suspendCoroutine<Unit> { continuation ->
                    var binder by Delegates.notNull<IBinder>()
                    val callback = object : IRemoteTaskCallback.Stub(), IBinder.DeathRecipient {
                        override fun onCompleted() {
                            binder.unlinkToDeath(this, 0)
                            continuation.resume(Unit)
                        }

                        override fun onException(ex: RemoteTaskException) {
                            binder.unlinkToDeath(this, 0)
                            continuation.resumeWithException(ex)
                        }

                        override fun binderDied() {
                            continuation.resumeWithException(DeadObjectException())
                        }
                    }
                    val bundle = Bundle()
                    BundleCompat.putBinder(bundle, BINDER_KEY, callback)
                    val result = application.contentResolver.call(
                        Uri.parse("content://${application.packageName}.remote-task-executor${process}"),
                        type.qualifiedName ?: throw AssertionError(),
                        null,
                        bundle
                    ) ?: throw AssertionError()
                    binder = BundleCompat.getBinder(result, BINDER_KEY)
                        ?: throw AssertionError()
                    binder.linkToDeath(callback, 0)
                }
            } catch (e: Throwable) {
                handleException(e)
                return
            }

        }

        private fun handleException(e: Throwable) {
            uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), e)
        }
    }

}