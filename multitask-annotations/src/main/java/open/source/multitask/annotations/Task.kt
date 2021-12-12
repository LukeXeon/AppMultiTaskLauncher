package open.source.multitask.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Task(
    val name: String,
    val executor: TaskExecutorType = TaskExecutorType.Async,
    val isAwait: Boolean = true,
    val process: String = "",
    val dependencies: Array<KClass<*>> = []
)