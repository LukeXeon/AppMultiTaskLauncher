package open.source.multitask

import android.app.Application
import android.os.Build
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
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

    private val mainThread = ExclusiveMainThreadExecutor()
        .asCoroutineDispatcher()


    private fun startJob(
        task: TaskInfo,
        results: MutableMap<KClass<out TaskExecutor>, Parcelable>,
        dependencies: List<Job>
    ): Job {
        return GlobalScope.launch(if (task.isMainThread) mainThread else BACKGROUND_THREAD) {
            dependencies.joinAll()
            val name = task.name
            tracker.onTaskStart(task.type, name)
            val start = SystemClock.uptimeMillis()
            val result = try {
                task.execute(application, results)
            } catch (e: Throwable) {
                val uncaughtExceptionHandler = BuildInModules.get(application)
                    .handlers[task.type]?.newInstance() ?: defaultUncaughtExceptionHandler
                uncaughtExceptionHandler.handleException(application, e)
            }
            val time = SystemClock.uptimeMillis() - start
            tracker.onTaskFinish(task.type, name, time)
            if (result != null) {
                results[task.type] = result
            }
        }
    }

    private fun startByTopologicalSort(graph: Map<KClass<out TaskExecutor>, TaskInfo>): MutableMap<KClass<out TaskExecutor>, Job> {
        val unmarked = ArrayList<TaskInfo>(graph.size)
        val temporaryMarked = HashSet<TaskInfo>(graph.size)
        val results = ConcurrentHashMap<KClass<out TaskExecutor>, Parcelable>(graph.size)
        // sorted list modify to map ↓
        val jobs = HashMap<KClass<out TaskExecutor>, Job>(graph.size)
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
        return jobs
    }

    fun start() {
        val start = SystemClock.uptimeMillis()
        GlobalScope.launch(BACKGROUND_THREAD) {
            val modules = BuildInModules.get(application)
            val graph = modules.tasks
            val mainThreadAwaitDependencies = modules.awaitDependencies
            if (mainThreadAwaitDependencies.isEmpty()) {
                mainThread.close()
                tracker.onStartFinish(SystemClock.uptimeMillis() - start)
            }
            if (graph.isNullOrEmpty()) {
                val time = SystemClock.uptimeMillis() - start
                tracker.onStartFinish(time)
                tracker.onTasksFinish(time)
                return@launch
            }
            val jobs = trace(TAG, "startByTopologicalSort") { startByTopologicalSort(graph) }
            if (mainThreadAwaitDependencies.isNotEmpty()) {
                for (type in mainThreadAwaitDependencies) {
                    val job = jobs[type]
                    if (job != null) {
                        jobs.remove(type)
                        job.join()
                    }
                }
                mainThread.close()
                tracker.onStartFinish(SystemClock.uptimeMillis() - start)
            }
            jobs.values.joinAll()
            tracker.onTasksFinish(SystemClock.uptimeMillis() - start)
        }
    }
}