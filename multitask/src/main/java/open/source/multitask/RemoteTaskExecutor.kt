package open.source.multitask

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
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
        private const val RESULTS_KEY = "results"
        private val ALIVE_BINDER = Bundle().apply {
            BundleCompat.putBinder(this, BINDER_KEY, Binder())
        }
        private val STATES_MUTEX = Mutex()
        private val STATES = ArrayMap<String, TaskState>()
        private val EXECUTORS_MUTEX = Mutex()
        private lateinit var EXECUTORS: Map<String, Uri>
        private suspend fun getExecutors(application: Application): Map<String, Uri> {
            EXECUTORS_MUTEX.withLock {
                if (!::EXECUTORS.isInitialized) {
                    EXECUTORS = application.packageManager
                        .getPackageInfo(
                            application.packageName,
                            PackageManager.GET_PROVIDERS
                        )
                        .providers.asSequence()
                        .filter {
                            !it.multiprocess
                        }.filter {
                            val other = runCatching {
                                Class.forName(it.name, false, application.classLoader)
                            }.getOrNull()
                            other != null && RemoteTaskExecutor::class.java.isAssignableFrom(other)
                        }.map {
                            it.processName to Uri.parse("content://${it.authority}")
                        }.toMap()
                }
            }
            return EXECUTORS
        }
    }

    internal class TaskState {
        val mutex = Mutex()

        var result: RemoteTaskResult? = null
    }

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
        extras.classLoader = application.classLoader
        val results = extras.getParcelable<ParcelTaskResults>(RESULTS_KEY) ?: return null
        val name = extras.getString(NAME_KEY)
        val process = extras.getString(PROCESS_KEY)
        val type = extras.getString(TYPE_KEY)
        val dependencies = extras.getStringArrayList(DEPENDENCIES_KEY) ?: emptyList<String>()
        val callback = IRemoteTaskCallback.Stub.asInterface(
            BundleCompat.getBinder(extras, BINDER_KEY)
        )
        GlobalScope.launch(MultiTask.BACKGROUND_THREAD) {
            try {
                val (tasks) = ModulesInfo.get(application)
                val taskInfo = tasks.find {
                    it.name == name && it.process == process
                            && it.type.qualifiedName == type
                            && it.dependencies.all { c ->
                        dependencies.contains(
                            c.qualifiedName
                        )
                    }
                } ?: throw ClassNotFoundException("task class $type not found ")
                val state = STATES_MUTEX.withLock {
                    STATES.getOrPut(type) { TaskState() }
                }
                state.mutex.withLock {
                    var value = state.result
                    if (value == null) {
                        value = RemoteTaskResult(
                            taskInfo.execute(
                                application,
                                results,
                                direct = true
                            )
                        )
                        state.result = value
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
            val path = getExecutors(application)
                .getValue(application.packageName + taskInfo.process)
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
                BundleCompat.putBinder(bundle, BINDER_KEY, callback)
                bundle.putParcelable(RESULTS_KEY, ParcelTaskResults(results))
                binder = application.contentResolver.call(
                    path,
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