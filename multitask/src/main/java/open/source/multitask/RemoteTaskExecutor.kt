package open.source.multitask

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.*
import androidx.collection.ArrayMap
import androidx.core.app.BundleCompat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.properties.Delegates
import kotlin.reflect.KClass


open class RemoteTaskExecutor : ContentProvider() {

    companion object {
        private const val BINDER_KEY = "binder"
        private val DISPATCHER = ScheduledThreadPoolExecutor(
            0,
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
        ).asCoroutineDispatcher()
        private val ALIVE_BINDER = Binder()
    }

    private val jobs = ArrayMap<String, Job>()
    private val serviceLoader by lazy {
        ServiceLoader.load(TaskInfo::class.java, classLoader)
    }
    private val application by lazy {
        context?.applicationContext as Application
    }
    open val classLoader: ClassLoader?
        get() = javaClass.classLoader

    final override fun onCreate(): Boolean {
        return true
    }

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
        GlobalScope.launch(DISPATCHER) {
            try {
                val taskInfo = serviceLoader.find { it.type.qualifiedName == method }
                    ?: throw ClassNotFoundException("task name \"${method}\" not found !")
                val job = synchronized(jobs) {
                    jobs.getOrPut(method) {
                        launch(DISPATCHER) {
                            try {
                                taskInfo.directExecute(application)
                            } finally {
                                synchronized(jobs) {
                                    jobs.remove(method)
                                }
                            }
                        }
                    }
                }
                job.join()
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