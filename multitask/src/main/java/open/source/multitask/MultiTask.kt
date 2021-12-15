package open.source.multitask

import android.app.Application
import android.os.Build
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.collection.ArrayMap
import androidx.collection.ArraySet
import kotlinx.coroutines.*
import open.source.multitask.annotations.TaskExecutorType
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass


class MultiTask @JvmOverloads @MainThread constructor(
    private val application: Application,
    private val tracker: TaskTracker = TaskTracker.Default,
    private val defaultUncaughtExceptionHandler: UncaughtExceptionHandler = UncaughtExceptionHandler.Default
) {
    companion object {
        private const val TAG = "MultiTask"
        private val REENTRY_CHECK = AtomicBoolean()
        private const val MAX_CAP = 0x7fff // max #workers - 1
        internal val BACKGROUND_THREAD = run {
            val parallelism = min(MAX_CAP, max(4, Runtime.getRuntime().availableProcessors()))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ForkJoinPool(
                    parallelism,
                    { pool ->
                        object : ForkJoinWorkerThread(pool) {
                            override fun onStart() {
                                super.onStart()
                                name = "${BuildConfig.LIBRARY_PACKAGE_NAME}(${name})"
                                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                            }
                        }
                    },
                    null,
                    true
                )
            } else {
                jsr166y.ForkJoinPool(
                    parallelism,
                    { pool ->
                        object : jsr166y.ForkJoinWorkerThread(pool) {
                            override fun onStart() {
                                super.onStart()
                                name = "${BuildConfig.LIBRARY_PACKAGE_NAME}(${name})"
                                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                            }
                        }
                    },
                    null,
                    true
                )
            }.asCoroutineDispatcher()
        }

        private fun MutableMap<KClass<out TaskExecutor>, TaskInfo>.add(task: TaskInfo) {
            put(task.type, task)
        }

        @JvmStatic
        @MainThread
        fun start(application: Application) {
            MultiTask(application).start()
        }
    }

    init {
        if (!REENTRY_CHECK.compareAndSet(false, true)) {
            throw IllegalStateException()
        }
    }

    private val internalTasks = arrayOf(
        UnlockMainThreadTask::class,
        StartupFinishedTask::class
    )
    private val mainThread = ExclusiveMainThreadExecutor()
        .asCoroutineDispatcher()


    private fun startJob(
        task: TaskInfo,
        results: MutableMap<KClass<out TaskExecutor>, Parcelable>,
        dependencies: List<Job>
    ): Job {
        return GlobalScope.launch(if (task.isMainThread) mainThread else BACKGROUND_THREAD) {
            dependencies.forEach { it.join() }
            val name = task.name
            val isInternalTask = internalTasks.contains(task.type)
            if (!isInternalTask) {
                tracker.onTaskStartup(name)
            }
            val start = SystemClock.uptimeMillis()
            val result = try {
                task.execute(application, results)
            } catch (e: Throwable) {
                val uncaughtExceptionHandler = ModulesInfo.get(application)
                    .handlers[task.type]?.newInstance() ?: defaultUncaughtExceptionHandler
                uncaughtExceptionHandler.handleException(application, e)
            }
            val time = SystemClock.uptimeMillis() - start
            if (!isInternalTask) {
                tracker.onTaskFinished(name, time)
            }
            if (result != null) {
                results[task.type] = result
            }
        }
    }

    private suspend fun unlockMainThread(time: Long) {
        mainThread.close()
        tracker.onUnlockMainThread(time)
    }

    private suspend fun startupFinished(time: Long) {
        tracker.onStartupFinished(time)
    }

    internal inner class UnlockMainThreadTask(private val start: Long) : TaskExecutor {

        override suspend fun execute(
            application: Application,
            results: Map<KClass<out TaskExecutor>, Parcelable>
        ): Parcelable? {
            unlockMainThread(SystemClock.uptimeMillis() - start)
            return null
        }
    }

    internal inner class StartupFinishedTask(private val start: Long) : TaskExecutor {
        override suspend fun execute(
            application: Application,
            results: Map<KClass<out TaskExecutor>, Parcelable>
        ): Parcelable? {
            startupFinished(SystemClock.uptimeMillis() - start)
            return null
        }

    }

    private fun startByTopologicalSort(graph: Map<KClass<out TaskExecutor>, TaskInfo>) {
        val unmarked = ArrayList<TaskInfo>(graph.size)
        val temporaryMarked = ArraySet<TaskInfo>(graph.size)
        val results = ConcurrentHashMap<KClass<out TaskExecutor>, Parcelable>(graph.size)
        // sorted list modify to map ↓
        val jobs = ArrayMap<KClass<out TaskExecutor>, Job>(graph.size)
        fun visit(node: TaskInfo) {
            if (jobs.containsKey(node.type)) {
                return
            }
            check(node !in temporaryMarked) {
                "cyclic dependency detected, $node already visited"
            }

            temporaryMarked.add(node)

            for (key in node.dependencies) {
                visit(graph.getValue(key))
            }

            unmarked.remove(node)
            temporaryMarked.remove(node)
            if (node.dependencies.isEmpty()) {
                jobs[node.type] = startJob(
                    node,
                    results,
                    emptyList()
                )
            } else {
                jobs[node.type] = startJob(
                    node,
                    results,
                    node.dependencies.map { jobs.getValue(it) }
                )
            }
        }

        unmarked.addAll(graph.values)
        while (unmarked.isNotEmpty()) {
            visit(unmarked.first())
        }

    }

    fun start() {
        val start = SystemClock.uptimeMillis()
        Log.d(TAG, "begin invoke startup")
        GlobalScope.launch(BACKGROUND_THREAD) {
            val modules = ModulesInfo.get(application)
            val tasks: MutableMap<KClass<out TaskExecutor>, TaskInfo> = ArrayMap(modules.tasks.size)
            val mainThreadAwaitDependencies =
                ArrayList<KClass<out TaskExecutor>>(modules.tasks.size)
            for (task in modules.tasks) {
                tasks[task.type] = task
                if (task.isAwait) {
                    mainThreadAwaitDependencies.add(task.type)
                }
            }
            if (tasks.isNullOrEmpty()) {
                val time = SystemClock.uptimeMillis() - start
                unlockMainThread(time)
                startupFinished(time)
                return@launch
            }

            val graph = ArrayMap<KClass<out TaskExecutor>, TaskInfo>(
                tasks.size + internalTasks.size
            )
            graph.putAll(tasks)

            if (!mainThreadAwaitDependencies.isNullOrEmpty()) {
                val unlockMainThreadTask = object : InternalTaskInfo(
                    UnlockMainThreadTask::class,
                    mainThreadAwaitDependencies,
                    TaskExecutorType.Main
                ) {
                    override fun newInstance(): TaskExecutor {
                        return UnlockMainThreadTask(start)
                    }

                }
                graph.add(unlockMainThreadTask)
            } else {
                unlockMainThread(SystemClock.uptimeMillis() - start)
            }

            val startupFinishedTask = object : InternalTaskInfo(
                StartupFinishedTask::class,
                tasks.keys
            ) {
                override fun newInstance(): TaskExecutor {
                    return StartupFinishedTask(start)
                }
            }
            graph.add(startupFinishedTask)
            startByTopologicalSort(graph)
            Log.d(TAG, "end invoke startup, use time: ${SystemClock.uptimeMillis() - start}")
        }
    }
}