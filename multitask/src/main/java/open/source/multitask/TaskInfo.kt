package open.source.multitask

import android.app.Application
import android.os.Parcelable
import open.source.multitask.annotations.TaskExecutorType
import kotlin.reflect.KClass

abstract class TaskInfo(
    val type: KClass<out TaskExecutor>,
    val name: String,
    val executor: TaskExecutorType = TaskExecutorType.Await,
    val process: String = "",
    val dependencies: Collection<KClass<out TaskExecutor>> = emptyList(),
) {

    internal inline val isMainThread: Boolean
        get() = executor == TaskExecutorType.Main

    internal inline val isAwait: Boolean
        get() {
            return executor == TaskExecutorType.Main
                    || executor == TaskExecutorType.Await
                    || executor == TaskExecutorType.RemoteAwait
        }

    private val isRemote: Boolean
        get() {
            return executor == TaskExecutorType.RemoteAwait
                    || executor == TaskExecutorType.RemoteAsync
        }

    internal suspend fun execute(
        application: Application,
        results: TaskResults,
        direct: Boolean = false
    ): Parcelable? {
        return if (!direct && isRemote) {
            RemoteTaskExecutor.Client(this)
                .execute(application, results)
        } else {
            return newInstance().execute(application, results)
        }
    }

    protected abstract fun newInstance(): TaskExecutor

}