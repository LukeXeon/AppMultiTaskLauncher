package open.source.multitask

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.*
import androidx.core.app.BundleCompat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import open.source.multitask.annotations.TaskExecutorType
import kotlin.reflect.KClass


open class RemoteTaskExecutor : ContentProvider() {

    companion object {
        internal const val BINDER_KEY = "binder"
        private val STATES_MUTEX = Mutex()
        private val STATES = HashMap<TaskInfo, TaskState>()
    }

    internal class TaskState(
        private val taskInfo: TaskInfo
    ) {
        private val mutex = Mutex()

        private var result: RemoteTaskResult? = null

        internal suspend fun execute(
            application: Application,
            results: Map<KClass<out TaskExecutor>, Parcelable>
        ): RemoteTaskResult {
            mutex.withLock {
                var value = result
                if (value == null) {
                    value = RemoteTaskResult(
                        taskInfo.execute(
                            application,
                            results,
                            direct = true
                        )
                    )
                    result = value
                }
                return value
            }
        }
    }

    private val service = Bundle().apply {
        BundleCompat.putBinder(this, BINDER_KEY, object : IRemoteTaskExecutorService.Stub() {
            override fun execute(
                name: String,
                type: String,
                isAsync: Boolean,
                process: String,
                dependencies: List<String>,
                results: List<ParcelKeyValue>,
                callback: IRemoteTaskCallback
            ) {
                val application = context?.applicationContext as? Application
                if (application == null) {
                    callback.onException(RemoteTaskException(NullPointerException("application is null")))
                    return
                }
                GlobalScope.launch(MultiTask.BACKGROUND_THREAD) {
                    try {
                        val executor = if (isAsync)
                            TaskExecutorType.RemoteAsync
                        else
                            TaskExecutorType.RemoteAwait
                        val modules = BuildInModules.get(application)
                        val tasks = modules.tasks
                        val taskInfo = tasks.values.find {
                            it.executor == executor && it.name == name && it.process == process
                                    && it.type.qualifiedName == type
                                    && it.dependencies.all { c ->
                                dependencies.contains(
                                    c.qualifiedName
                                )
                            }
                        } ?: throw ClassNotFoundException("task class $type not found ")
                        val types = tasks.mapKeys { it.key.qualifiedName }
                        val map = HashMap<KClass<out TaskExecutor>, Parcelable>(results.size)
                        for ((k, v) in results) {
                            val t = types[k]?.type
                            if (t != null && v != null) {
                                map[t] = v
                            }
                        }
                        val state = STATES_MUTEX.withLock {
                            STATES.getOrPut(taskInfo) { TaskState(taskInfo) }
                        }
                        callback.onCompleted(
                            state.execute(
                                application,
                                map
                            )
                        )
                    } catch (e: Throwable) {
                        callback.onException(RemoteTaskException(e))
                    }
                }
            }
        })
    }

    final override fun onCreate(): Boolean {
        return true
    }

    final override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle {
        return service
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

}