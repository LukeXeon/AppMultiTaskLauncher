package open.source.multitask

import android.app.Application
import android.os.Parcelable
import kotlin.reflect.KClass

interface TaskExecutor {
    suspend fun execute(
        application: Application,
        results: Map<KClass<out TaskExecutor>, Parcelable>
    ): Parcelable?
}