package open.source.multitask

import android.app.Application

abstract class Task(
    val name: String,
    val isMainThread: Boolean = false,
    val dependencies: List<Class<out Task>> = emptyList()
) {
    abstract suspend fun execute(application: Application)
}