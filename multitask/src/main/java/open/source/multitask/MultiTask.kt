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
import androidx.core.os.TraceCompat
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
    private val defaultUncaughtExceptionHandler: UncaughtExceptionHandler = UncaughtExceptionHandler.Default,
    private val classLoader: ClassLoader? = application.classLoader
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
    private val handlers by lazy {
        ServiceLoader.load(
            HandlerInfo::class.java,
            classLoader
        )
    }

    private fun startJob(
        task: TaskInfo,
        results: ConcurrentTaskResults,
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
                val uncaughtExceptionHandler = handlers.asSequence()
                    .filter { it.taskType == task.type }
                    .maxByOrNull { it.priority }
                    ?.newInstance() ?: defaultUncaughtExceptionHandler
                uncaughtExceptionHandler.handleException(task.type, e)
            }
            val time = SystemClock.uptimeMillis() - start
            if (!isInternalTask) {
                tracker.onTaskFinished(name, time)
            }
            val key = task.type.qualifiedName
            if (!key.isNullOrEmpty()) {
                results.map[key] = result ?: Unit
            }
        }
    }

    private suspend fun onUnlockMainThread(time: Long) {
        mainThread.close()
        tracker.onUnlockMainThread(time)
    }

    private suspend fun onStartupFinished(time: Long) {
        tracker.onStartupFinished(time)
    }

    internal inner class UnlockMainThreadTask(private val start: Long) : TaskExecutor {

        override suspend fun execute(
            application: Application,
            results: TaskResults
        ): Parcelable? {
            onUnlockMainThread(SystemClock.uptimeMillis() - start)
            return null
        }
    }

    internal inner class StartupFinishedTask(private val start: Long) : TaskExecutor {
        override suspend fun execute(
            application: Application,
            results: TaskResults
        ): Parcelable? {
            onStartupFinished(SystemClock.uptimeMillis() - start)
            return null
        }

    }

    private fun startByTopologicalSort(graph: Map<KClass<out TaskExecutor>, TaskInfo>) {
        val unmarked = ArrayList<TaskInfo>(graph.size)
        val temporaryMarked = ArraySet<TaskInfo>(graph.size)
        val results = ConcurrentTaskResults(graph.size)
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
            TraceCompat.beginSection(TAG)
            val it = ServiceLoader.load(TaskInfo::class.java, classLoader)
                .iterator()
            var userTasks: MutableMap<KClass<out TaskExecutor>, TaskInfo>? = null
            var mainThreadAwaitDependencies: ArrayList<KClass<out TaskExecutor>>? = null
            while (it.hasNext()) {
                if (userTasks == null) {
                    userTasks = ArrayMap(128)
                }
                val task = it.next()
                userTasks.add(task)
                if (task.executor == TaskExecutorType.Main
                    || task.executor == TaskExecutorType.Await
                    || task.executor == TaskExecutorType.RemoteAwait
                ) {
                    if (mainThreadAwaitDependencies == null) {
                        mainThreadAwaitDependencies = ArrayList(128)
                    }
                    mainThreadAwaitDependencies.add(task.type)
                }
            }

            if (userTasks.isNullOrEmpty()) {
                val time = SystemClock.uptimeMillis() - start
                onUnlockMainThread(time)
                onStartupFinished(time)
                return@launch
            }

            val graph = ArrayMap<KClass<out TaskExecutor>, TaskInfo>(
                userTasks.size + internalTasks.size
            )

            graph.putAll(userTasks)

            if (!mainThreadAwaitDependencies.isNullOrEmpty()) {
                val unlockMainThreadTask = object : InternalTaskInfo(
                    UnlockMainThreadTask::class,
                    mainThreadAwaitDependencies
                ) {
                    override fun newInstance(): TaskExecutor {
                        return UnlockMainThreadTask(start)
                    }

                }
                graph.add(unlockMainThreadTask)
            } else {
                onUnlockMainThread(SystemClock.uptimeMillis() - start)
            }

            val startupFinishedTask = object : InternalTaskInfo(
                StartupFinishedTask::class,
                userTasks.keys
            ) {
                override fun newInstance(): TaskExecutor {
                    return StartupFinishedTask(start)
                }
            }
            graph.add(startupFinishedTask)
            startByTopologicalSort(graph)
            TraceCompat.endSection()
            Log.d(TAG, "end invoke startup, use time: ${SystemClock.uptimeMillis() - start}")
        }
    }

}