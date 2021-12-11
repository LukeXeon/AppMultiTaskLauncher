package open.source.multitask

import android.app.Application

abstract class ActionTask @JvmOverloads constructor(
    name: String,
    isMainThread: Boolean = false,
    isAwait: Boolean = true,
    dependencies: Set<Class<out Task>> = emptySet()
) : AwaitTask(name, isMainThread, isAwait, dependencies) {

    final override suspend fun execute(application: Application) {
        run(application)
    }

    abstract fun run(application: Application)
}