package open.source.multitask

import android.app.Application
import android.os.Parcelable
import open.source.multitask.annotations.TaskExecutorType
import kotlin.reflect.KClass

abstract class TaskInfo(
    val name: String,
    val executor: TaskExecutorType = TaskExecutorType.Await,
    private val process: String = "",
    val dependencies: List<KClass<out TaskExecutor>> = emptyList(),
) : Map.Entry<KClass<out TaskExecutor>, TaskInfo> {

    internal suspend inline fun execute(
        application: Application,
        results: Map<String, Parcelable>,
        uncaughtExceptionHandler: RemoteTaskExceptionHandler
    ): Parcelable? {
        return if (executor == TaskExecutorType.RemoteAsync) {
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

    override val key: KClass<out TaskExecutor>
        get() = type
    override val value: TaskInfo
        get() = this

}