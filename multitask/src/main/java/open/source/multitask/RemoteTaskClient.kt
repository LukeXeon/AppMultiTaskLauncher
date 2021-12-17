package open.source.multitask

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import androidx.core.app.BundleCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import open.source.multitask.annotations.TaskExecutorType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KClass

internal class RemoteTaskClient(
    private val taskInfo: TaskInfo
) : TaskExecutor {

    companion object {
        private val SERVICES_MUTEX = Mutex()
        private const val PRE_ALLOC_SIZE = 8
        private lateinit var SERVICES: Map<String, ServiceConnection>
        private suspend fun getServices(application: Application): Map<String, ServiceConnection> {
            SERVICES_MUTEX.withLock {
                if (!::SERVICES.isInitialized) {
                    SERVICES = application.packageManager
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
                            other != null && RemoteTaskExecutor::class.java.isAssignableFrom(
                                other
                            )
                        }.map {
                            it.processName to ServiceConnection(Uri.parse("content://${it.authority}"))
                        }.toMap(HashMap(PRE_ALLOC_SIZE))
                }
            }
            return SERVICES
        }
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
                                    RemoteTaskExecutor.BINDER_KEY
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

    override suspend fun execute(
        application: Application,
        results: Bundle
    ): Parcelable? {
        val services = getServices(application)
        val connection = services.getValue(taskInfo.process)
        return connection.broadcast(application) { service ->
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
                    results,
                    callback
                )
            }
        }
    }
}