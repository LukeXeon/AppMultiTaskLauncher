package open.source.multitask

import android.app.Application
import android.os.Parcelable

abstract class ActionTaskExecutor : TaskExecutor {

    final override suspend fun execute(
        application: Application,
        results: TaskResults
    ): Parcelable? {
        run(application, results)
        return null
    }

    open fun run(
        application: Application,
        results: TaskResults
    ) {
        run(application)
    }

    abstract fun run(application: Application)
}