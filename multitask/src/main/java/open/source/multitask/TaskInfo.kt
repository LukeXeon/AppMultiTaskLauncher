package open.source.multitask

import android.app.Application
import android.content.ComponentName
import android.os.Parcelable
import open.source.multitask.annotations.TaskExecutorType
import kotlin.reflect.KClass

abstract class TaskInfo(
    val name: String,
    val executor: TaskExecutorType = TaskExecutorType.Async,
    val isAwait: Boolean = true,
    private val process: String = "",
    val dependencies: Collection<KClass<out TaskExecutor>>,
) {
    internal suspend inline fun execute(
        application: Application,
        results: Map<String, Parcelable>,
        uncaughtExceptionHandler: RemoteTaskExceptionHandler
    ): Parcelable? {
        return if (executor == TaskExecutorType.Remote) {
            RemoteTaskExecutor.Client(process, type, uncaughtExceptionHandler)
                .execute(application, results)
        } else {
            directExecute(application, results)
        }
    }

    internal suspend inline fun directExecute(
        application: Application,
        results: Map<String, Parcelable>
    ): Parcelable? {
        return newInstance().execute(application, results)
    }

    protected abstract fun newInstance(): TaskExecutor
    abstract val type: KClass<out TaskExecutor>
}