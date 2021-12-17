package open.source.multitask

import android.app.Application
import android.os.Bundle
import android.os.Parcelable
import kotlin.reflect.KClass

interface TaskExecutor {
    suspend fun execute(
        application: Application,
        results: Bundle
    ): Parcelable?
}