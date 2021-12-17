package open.source.multitask

import android.app.Application
import android.os.Bundle
import android.os.Parcelable
import kotlin.reflect.KClass

abstract class ActionTaskExecutor : TaskExecutor {

    final override suspend fun execute(
        application: Application,
        results: Bundle
    ): Parcelable? {
        run(application, results)
        return null
    }

    open fun run(
        application: Application,
        results: Bundle
    ) {
        run(application)
    }

    abstract fun run(application: Application)
}