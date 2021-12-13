package open.source.multitask

import kotlin.reflect.KClass

abstract class HandlerInfo(
    val taskType: KClass<out TaskExecutor>,
    val priority: Int
) {
    abstract fun newInstance(): UncaughtExceptionHandler
}