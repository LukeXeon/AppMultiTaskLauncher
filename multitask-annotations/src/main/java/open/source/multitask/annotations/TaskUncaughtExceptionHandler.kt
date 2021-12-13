package open.source.multitask.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class TaskUncaughtExceptionHandler(
    val taskType: KClass<*>,
    val priority: Int = Int.MAX_VALUE
)
