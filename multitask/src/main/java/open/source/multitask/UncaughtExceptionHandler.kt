package open.source.multitask

import android.app.Application
import android.os.Parcelable
import kotlin.reflect.KClass

interface UncaughtExceptionHandler {
    suspend fun handleException(
        application: Application,
        task: KClass<out TaskExecutor>,
        e: Throwable
    ): Parcelable?

    companion object Default : UncaughtExceptionHandler {
        override suspend fun handleException(
            application: Application,
            task: KClass<out TaskExecutor>,
            e: Throwable
        ): Parcelable? {
            throw e
        }
    }
}