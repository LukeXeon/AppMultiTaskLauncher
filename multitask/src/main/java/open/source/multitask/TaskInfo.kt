package open.source.multitask

import kotlin.reflect.KClass

internal abstract class TaskInfo(
    val name: String,
    val runner: TaskRunnerType = TaskRunnerType.Async,
    val isAwait: Boolean = true,
    private val process: String = "",
    val dependencies: List<KClass<out TaskExecutor>>,
) {
    fun create(): TaskExecutor {
        if (runner == TaskRunnerType.Remote) {
            return RemoteTaskExecutor.Client(process, type)
        }
        return newInstance()
    }

    protected abstract fun newInstance(): TaskExecutor
    abstract val type: KClass<out TaskExecutor>
}