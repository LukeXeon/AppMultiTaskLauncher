package open.source.multitask

import android.app.Application
import android.os.Parcelable

abstract class ActionTaskExecutor : TaskExecutor {

    final override suspend fun execute(
        application: Application,
        results: Map<String, Parcelable>
    ): Parcelable? {
        run(application, results)
        return null
    }

    open fun run(
        application: Application,
        results: Map<String, Parcelable>
    ) {
        run(application)
    }

    abstract fun run(application: Application)
}