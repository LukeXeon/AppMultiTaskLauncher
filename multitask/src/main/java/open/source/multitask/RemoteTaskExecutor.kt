package open.source.multitask

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.*
import androidx.collection.ArrayMap
import androidx.core.app.BundleCompat
import kotlinx.coroutines.*
import java.lang.Runnable
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
        private const val ARGS_KEY = "args"
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
        private val ALIVE_BINDER = Bundle().apply {
            BundleCompat.putBinder(this, BINDER_KEY, Binder())
        }
    }

    private val jobs = ArrayMap<String, Deferred<Parcelable?>>()
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
                val args = extras.getBundle(ARGS_KEY) ?: throw AssertionError()
                val results = FromBundleMap(args)
                val taskInfo = serviceLoader.find { it.type.qualifiedName == method }
                    ?: throw ClassNotFoundException("task name \"${method}\" not found !")
                val job = synchronized(jobs) {
                    jobs.getOrPut(method) {
                        async(DISPATCHER) {
                            try {
                                return@async taskInfo.directExecute(application, results)
                            } finally {
                                @Suppress("DeferredResultUnused")
                                synchronized(jobs) {
                                    jobs.remove(method)
                                }
                            }
                        }
                    }
                }
                callback.onCompleted(RemoteTaskResult(job.await()))
            } catch (e: Throwable) {
                callback.onException(RemoteTaskException(e))
            }
        }
        return ALIVE_BINDER
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
        private val uncaughtExceptionHandler: RemoteTaskExceptionHandler
    ) : TaskExecutor {

        override suspend fun execute(
            application: Application,
            results: Map<String, Parcelable>
        ): Parcelable? {
            try {
                return suspendCoroutine { continuation ->
                    var binder by Delegates.notNull<IBinder>()
                    val callback = object : IRemoteTaskCallback.Stub(), IBinder.DeathRecipient {

                        override fun onCompleted(result: RemoteTaskResult) {
                            binder.unlinkToDeath(this, 0)
                            continuation.resume(result.value)
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
                    val args = Bundle()
                    for ((k, v) in results) {
                        args.putParcelable(k, v)
                    }
                    BundleCompat.putBinder(bundle, BINDER_KEY, callback)
                    bundle.putBundle(ARGS_KEY, args)
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
                return uncaughtExceptionHandler.handleException(e)
            }
        }
    }

}