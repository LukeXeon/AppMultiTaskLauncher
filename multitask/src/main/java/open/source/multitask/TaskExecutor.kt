package open.source.multitask

import android.app.Application
import android.os.Parcelable

interface TaskExecutor {
    suspend fun execute(
        application: Application,
        results: Map<String, Parcelable>
    ): Parcelable?
}