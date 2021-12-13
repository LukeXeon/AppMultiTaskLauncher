package open.source.multitask

import android.os.Parcelable
import kotlin.reflect.KClass

interface UncaughtExceptionHandler {
    suspend fun handleException(
        task: KClass<out TaskExecutor>,
        e: Throwable
    ): Parcelable?

    companion object Default : UncaughtExceptionHandler {
        override suspend fun handleException(
            task: KClass<out TaskExecutor>,
            e: Throwable
        ): Parcelable? {
            throw e
        }
    }
}