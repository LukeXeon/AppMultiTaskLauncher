package open.source.startup.app

import android.os.Parcelable
import open.source.multitask.TaskExecutor
import open.source.multitask.UncaughtExceptionHandler
import open.source.multitask.annotations.TaskUncaughtExceptionHandler
import kotlin.reflect.KClass

@TaskUncaughtExceptionHandler(RemoteTask::class)
class H : UncaughtExceptionHandler {
    override suspend fun handleException(
        task: KClass<out TaskExecutor>,
        e: Throwable
    ): Parcelable? {
        throw e
    }
}