package open.source.multitask

import android.app.Application

abstract class ActionTask @JvmOverloads constructor(
    name: String,
    isMainThread: Boolean = false,
    dependencies: List<Class<out Task>> = emptyList()
) : Task(name, isMainThread, dependencies) {

    final override suspend fun execute(application: Application) {
        run(application)
    }

    abstract fun run(application: Application)
}