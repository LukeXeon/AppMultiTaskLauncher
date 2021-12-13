package open.source.multitask

import android.app.Application
import android.os.Parcelable
import open.source.multitask.annotations.TaskExecutorType
import kotlin.reflect.KClass

abstract class TaskInfo(
    val type: KClass<out TaskExecutor>,
    val name: String,
    val executor: TaskExecutorType = TaskExecutorType.Await,
    private val process: String = "",
    val dependencies: Collection<KClass<out TaskExecutor>> = emptyList(),
) {

    internal inline val isMainThread: Boolean
        get() = executor == TaskExecutorType.Main

    internal suspend inline fun execute(
        application: Application,
        results: Map<String, Parcelable>,
        direct: Boolean = false
    ): Parcelable? {
        return if (!direct && executor == TaskExecutorType.RemoteAsync) {
            RemoteTaskExecutor.Client(process, type)
                .execute(application, results)
        } else {
            return newInstance().execute(application, results)
        }
    }

    protected abstract fun newInstance(): TaskExecutor

}