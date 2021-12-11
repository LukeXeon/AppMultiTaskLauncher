package open.source.multitask

import android.app.Application
import androidx.annotation.RestrictTo

@RestrictTo(RestrictTo.Scope.LIBRARY)
abstract class Task(
    internal val name: String,
    internal val isMainThread: Boolean = false,
    internal val dependencies: Set<Class<out Task>> = emptySet()
) {
    abstract suspend fun execute(application: Application)
}