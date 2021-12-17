package open.source.multitask

import android.app.Application
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import open.source.multitask.annotations.TaskExecutorType
import kotlin.reflect.KClass

abstract class TaskInfo(
    val type: KClass<out TaskExecutor>,
    val name: String,
    val executor: TaskExecutorType = TaskExecutorType.Await,
    val process: String = "",
    val dependencies: Collection<KClass<out TaskExecutor>> = emptyList(),
) {

    internal inline val isMainThread: Boolean
        get() = executor == TaskExecutorType.Main

    internal inline val isAwait: Boolean
        get() {
            return executor == TaskExecutorType.Main
                    || executor == TaskExecutorType.Await
                    || executor == TaskExecutorType.RemoteAwait
        }

    private val isRemote: Boolean
        get() {
            return executor == TaskExecutorType.RemoteAwait
                    || executor == TaskExecutorType.RemoteAsync
        }

    internal suspend fun execute(
        application: Application,
        results: Bundle
    ): Parcelable? {
        return newInstance().execute(application, results)
    }

    internal suspend fun execute(
        application: Application,
        results: Bundle,
        tracker: TaskTracker
    ): Parcelable? {
        val executor = if (isRemote) {
            RemoteTaskClient(this)
        } else {
            newInstance()
        }
        val start = SystemClock.uptimeMillis()
        tracker.onTaskStart(executor, name)
        val r = executor.execute(application, results)
        tracker.onTaskFinish(executor, name, SystemClock.uptimeMillis() - start)
        return r
    }

    protected abstract fun newInstance(): TaskExecutor

}