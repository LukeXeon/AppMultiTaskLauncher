package open.source.multitask

import android.app.Application
import android.os.Parcelable
import kotlin.reflect.KClass

abstract class ActionTaskExecutor : TaskExecutor {

    final override suspend fun execute(
        application: Application,
        results: Map<KClass<out TaskExecutor>, Parcelable>
    ): Parcelable? {
        run(application, results)
        return null
    }

    open fun run(
        application: Application,
        results: Map<KClass<out TaskExecutor>, Parcelable>
    ) {
        run(application)
    }

    abstract fun run(application: Application)
}