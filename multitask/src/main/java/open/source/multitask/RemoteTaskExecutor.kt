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
import open.source.multitask.annotations.TaskExecutorType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KClass


open class RemoteTaskExecutor : ContentProvider() {

    companion object {
        private const val BINDER_KEY = "binder"
        private val STATES_MUTEX = Mutex()
        private val STATES = ArrayMap<String, TaskState>(BuildInModules.PRE_ALLOC_SIZE)
        private val SERVICES_MUTEX = Mutex()
        private val SERVICES = ArrayMap<String, ServiceConnection>(BuildInModules.PRE_ALLOC_SIZE)

        private suspend fun <T> broadcast(
            application: Application,
            process: String,
            action: suspend (IRemoteTaskExecutorService) -> T
        ): T {
            SERVICES_MUTEX.withLock {
                if (SERVICES.isEmpty) {
                    application.packageManager
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
                            it.processName to ServiceConnection(Uri.parse("content://${it.authority}"))
                        }.toMap(SERVICES)
                }
            }
            val service = SERVICES.getValue(application.packageName + process)
            return service.broadcast(application, action)
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
                        val types = modules.taskTypes
                        val taskInfo = tasks.values.find {
                            it.executor == executor && it.name == name && it.process == process
                                    && it.type.qualifiedName == type
                                    && it.dependencies.all { c ->
                                dependencies.contains(
                                    c.qualifiedName
                                )
                            }
                        } ?: throw ClassNotFoundException("task class $type not found ")
                        val map = ArrayMap<KClass<out TaskExecutor>, Parcelable>(results.size)
                        for ((k, v) in results) {
                            val t = types[k]
                            if (t != null) {
                                map[t] = v
                            }
                        }
                        val state = STATES_MUTEX.withLock {
                            STATES.getOrPut(type) { TaskState() }
                        }
                        callback.onCompleted(
                            state.execute(
                                application,
                                map,
                                taskInfo
                            )
                        )
                    } catch (e: Throwable) {
                        callback.onException(RemoteTaskException(e))
                    }
                }
            }
        })
    }

    internal class ServiceConnection(private val path: Uri) {
        private val mutex = Mutex()
        private val callback = RemoteCallbackList<IRemoteTaskExecutorService>()

        suspend fun <T> broadcast(
            application: Application,
            action: suspend (IRemoteTaskExecutorService) -> T
        ): T {
            mutex.withLock {
                try {
                    val service = if (callback.beginBroadcast() == 1) {
                        callback.getBroadcastItem(0)
                    } else {
                        IRemoteTaskExecutorService.Stub
                            .asInterface(
                                BundleCompat.getBinder(
                                    application.contentResolver.call(path, "", null, null)!!,
                                    BINDER_KEY
                                )
                            ).apply {
                                callback.register(this)
                            }
                    }
                    return action(service)
                } finally {
                    callback.finishBroadcast()
                }
            }
        }
    }

    internal class TaskState {
        private val mutex = Mutex()

        private var result: RemoteTaskResult? = null

        internal suspend fun execute(
            application: Application,
            results: Map<KClass<out TaskExecutor>, Parcelable>,
            taskInfo: TaskInfo
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

    internal class Client(
        private val taskInfo: TaskInfo
    ) : TaskExecutor {

        override suspend fun execute(
            application: Application,
            results: Map<KClass<out TaskExecutor>, Parcelable>
        ): Parcelable? {
            return broadcast(application, taskInfo.process) { service ->
                suspendCoroutine { continuation ->
                    val isCompleted = AtomicBoolean()
                    val isAsync = taskInfo.executor == TaskExecutorType.RemoteAsync
                    val callback = object : IRemoteTaskCallback.Stub(), IBinder.DeathRecipient {
                        override fun onCompleted(result: RemoteTaskResult) {
                            if (isCompleted.compareAndSet(false, true)) {
                                service.asBinder().unlinkToDeath(this, 0)
                                continuation.resume(result.value)
                            }
                        }

                        override fun onException(ex: RemoteTaskException) {
                            if (isCompleted.compareAndSet(false, true)) {
                                service.asBinder().unlinkToDeath(this, 0)
                                continuation.resumeWithException(ex)
                            }
                        }

                        override fun binderDied() {
                            if (isCompleted.compareAndSet(false, true)) {
                                continuation.resumeWithException(DeadObjectException())
                            }
                        }
                    }
                    service.asBinder().linkToDeath(callback, 0)
                    service.execute(
                        taskInfo.name,
                        taskInfo.type.qualifiedName,
                        isAsync,
                        taskInfo.process,
                        taskInfo.dependencies.mapTo(ArrayList(taskInfo.dependencies.size)) { it.qualifiedName },
                        results.map { ParcelKeyValue(it.key.qualifiedName, it.value) },
                        callback
                    )
                }
            }
        }
    }

}