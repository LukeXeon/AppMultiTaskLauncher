package open.source.multitask

import android.app.Application
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
        uncaughtExceptionHandler: Thread.UncaughtExceptionHandler
    ) {
        if (executor == TaskExecutorType.Remote) {
            RemoteTaskExecutor.Client(process, type, uncaughtExceptionHandler).execute(application)
        } else {
            newInstance().execute(application)
        }
    }

    protected abstract fun newInstance(): TaskExecutor
    abstract val type: KClass<out TaskExecutor>
}