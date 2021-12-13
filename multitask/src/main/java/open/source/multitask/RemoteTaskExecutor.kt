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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.properties.Delegates
import kotlin.reflect.KClass


open class RemoteTaskExecutor : ContentProvider() {

    companion object {
        private const val BINDER_KEY = "binder"
        private const val ARGS_KEY = "args"
        private val ALIVE_BINDER = Bundle().apply {
            BundleCompat.putBinder(this, BINDER_KEY, Binder())
        }
    }

    internal class MutexResultHolder {
        val mutex = Mutex()

        @Volatile
        var value: RemoteTaskResult? = null
    }

    private val holdersMutex = Mutex()
    private val holders = ArrayMap<String, MutexResultHolder>()
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
    ): Bundle? {
        extras ?: return null
        val args = extras.getBundle(ARGS_KEY) ?: return null
        val callback = IRemoteTaskCallback.Stub
            .asInterface(BundleCompat.getBinder(extras, BINDER_KEY))
        val results = FromBundleMap(args)
        serviceLoader.find { it.type.qualifiedName == method } ?: return null
        val taskInfo = serviceLoader.find { it.type.qualifiedName == method } ?: return null
        GlobalScope.launch(MultiTask.BACKGROUND_THREAD) {
            val holder = holdersMutex.withLock {
                holders.getOrPut(method) { MutexResultHolder() }
            }
            try {
                holder.mutex.withLock {
                    var value = holder.value
                    if (value == null) {
                        value = RemoteTaskResult(
                            taskInfo.directExecute(
                                application,
                                results
                            )
                        )
                        holder.value = value
                    }
                    callback.onCompleted(value)
                }
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
                            try {
                                binder.unlinkToDeath(this, 0)
                            } finally {
                                continuation.resume(result.value)
                            }
                        }

                        override fun onException(ex: RemoteTaskException) {
                            try {
                                binder.unlinkToDeath(this, 0)
                            } finally {
                                continuation.resumeWithException(ex)
                            }
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
                    )!!
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