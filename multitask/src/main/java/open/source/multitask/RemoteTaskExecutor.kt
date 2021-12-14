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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.properties.Delegates


open class RemoteTaskExecutor : ContentProvider() {

    companion object {
        private const val BINDER_KEY = "binder"
        private const val NAME_KEY = "name"
        private const val TYPE_KEY = "type"
        private const val PROCESS_KEY = "process"
        private const val DEPENDENCIES_KEY = "dependencies"
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

    final override fun onCreate(): Boolean {
        return true
    }

    final override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        extras ?: return null
        val application = context?.applicationContext as? Application ?: return null
        val name = extras.getString(NAME_KEY)
        val process = extras.getString(PROCESS_KEY)
        val type = extras.getString(TYPE_KEY)
        val dependencies = extras.getStringArrayList(DEPENDENCIES_KEY) ?: emptyList<String>()
        val args = extras.getBundle(ARGS_KEY) ?: Bundle.EMPTY
        val results = BundleTaskResults(args)
        val callback = IRemoteTaskCallback.Stub.asInterface(
            BundleCompat.getBinder(extras, BINDER_KEY)
        )
        GlobalScope.launch(MultiTask.BACKGROUND_THREAD) {
            try {
                MultiTask.loadModules(application)
                val taskInfo = MultiTask.TASKS.find {
                    it.name == name && it.process == process
                            && it.type.qualifiedName == type
                            && it.dependencies.all { c ->
                        dependencies.contains(
                            c.qualifiedName
                        )
                    }
                } ?: throw ClassNotFoundException("task class $type not found ")
                val holder = holdersMutex.withLock {
                    holders.getOrPut(type) { MutexResultHolder() }
                }
                holder.mutex.withLock {
                    var value = holder.value
                    if (value == null) {
                        value = RemoteTaskResult(
                            taskInfo.execute(
                                application,
                                results,
                                direct = true
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
        private val taskInfo: TaskInfo
    ) : TaskExecutor {

        override suspend fun execute(
            application: Application,
            results: TaskResults
        ): Parcelable? {
            return suspendCoroutine { continuation ->
                var binder by Delegates.notNull<IBinder>()
                val isCompleted = AtomicBoolean()
                val callback = object : IRemoteTaskCallback.Stub(), IBinder.DeathRecipient {

                    override fun onCompleted(result: RemoteTaskResult) {
                        if (isCompleted.compareAndSet(false, true)) {
                            runCatching { binder.unlinkToDeath(this, 0) }
                            continuation.resume(result.value)
                        }
                    }

                    override fun onException(ex: RemoteTaskException) {
                        if (isCompleted.compareAndSet(false, true)) {
                            runCatching { binder.unlinkToDeath(this, 0) }
                            continuation.resumeWithException(ex)
                        }
                    }

                    override fun binderDied() {
                        if (isCompleted.compareAndSet(false, true)) {
                            continuation.resumeWithException(DeadObjectException())
                        }
                    }
                }
                val bundle = Bundle()
                bundle.putString(NAME_KEY, taskInfo.name)
                bundle.putString(TYPE_KEY, taskInfo.type.qualifiedName)
                bundle.putString(PROCESS_KEY, taskInfo.process)
                bundle.putStringArrayList(
                    DEPENDENCIES_KEY,
                    taskInfo.dependencies.mapTo(ArrayList(taskInfo.dependencies.size)) { it.qualifiedName }
                )
                val args = Bundle()
                for (k in results.keySet()) {
                    args.putParcelable(k, results[k])
                }
                BundleCompat.putBinder(bundle, BINDER_KEY, callback)
                bundle.putBundle(ARGS_KEY, args)
                binder = application.contentResolver.call(
                    Uri.parse("content://${application.packageName}.remote-task-executor${taskInfo.process}"),
                    "",
                    null,
                    bundle
                )?.let { BundleCompat.getBinder(it, BINDER_KEY) }
                    ?: throw RemoteException("unknown")
                if (!isCompleted.get()) {
                    binder.linkToDeath(callback, 0)
                }
            }
        }
    }

}