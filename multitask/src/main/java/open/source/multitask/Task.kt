package open.source.multitask

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Task(
    val name: String,
    val runner: TaskRunnerType = TaskRunnerType.Async,
    val isAwait: Boolean = true,
    val process: String = "",
    val dependencies: Array<KClass<out TaskExecutor>> = []
)
