package open.source.multitask

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.collection.ArrayMap
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
        ShutdownExclusiveMainThreadTask::class,
        ShutdownBackgroundThreadTask::class,
        CheckCyclicDependenceTask::class
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
        results: MutableMap<String, Parcelable>,
        dependencies: List<Job>
    ): Job {
        return GlobalScope.launch(if (task.executor == TaskExecutorType.Main) mainThread else BACKGROUND_THREAD) {
            dependencies.forEach { it.join() }
            val name = task.name
            val isInternalTask = internalTasks.contains(task.type)
            if (!isInternalTask) {
                tracker.onTaskStarted(name)
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
            if (result != null && !key.isNullOrEmpty()) {
                results[key] = result
            }
        }
    }

    private fun startJobs(
        task: TaskInfo,
        results: MutableMap<String, Parcelable>,
        types: Map<KClass<out TaskExecutor>, TaskInfo>,
        jobs: MutableMap<TaskInfo, Job>
    ): Job {
        return jobs.getOrPut(task) {
            if (task.dependencies.isEmpty()) {
                startJob(task, results, emptyList())
            } else {
                val dependenciesJobs = ArrayList<Job>(task.dependencies.size)
                for (dependencyType in task.dependencies) {
                    val awaitTask = types.getValue(dependencyType)
                    dependenciesJobs.add(startJobs(awaitTask, results, types, jobs))
                }
                startJob(task, results, dependenciesJobs)
            }
        }
    }

    private fun awaitTasksFinished(time: Long) {
        (mainThread.executor as ExecutorService).shutdown()
        tracker.onAwaitTasksFinished(time)
    }

    private fun allTasksFinished(time: Long) {
        tracker.onAllTasksFinished(time)
    }

    internal inner class ShutdownExclusiveMainThreadTask(private val start: Long) :
        ActionTaskExecutor() {
        override fun run(application: Application) {
            awaitTasksFinished(SystemClock.uptimeMillis() - start)
        }
    }

    internal inner class ShutdownBackgroundThreadTask(private val start: Long) :
        ActionTaskExecutor() {
        override fun run(application: Application) {
            allTasksFinished(SystemClock.uptimeMillis() - start)
        }
    }

    internal inner class CheckCyclicDependenceTask(
        private val graph: Tasks
    ) : ActionTaskExecutor() {

        private fun topologicalSort(): List<KClass<out TaskExecutor>> {
            val unmarked = ArrayList<KClass<out TaskExecutor>>(graph.size)
            val sorted = ArrayList<KClass<out TaskExecutor>>(graph.size)
            val temporaryMarked = ArrayList<KClass<out TaskExecutor>>(graph.size)
            fun visit(node: KClass<out TaskExecutor>) {
                if (node in sorted) {
                    return
                }
                check(node !in temporaryMarked) {
                    "cyclic dependency detected, $node already visited"
                }

                temporaryMarked.add(node)
                graph[node]?.dependencies?.forEach { visit(it) }

                unmarked.remove(node)
                temporaryMarked.remove(node)
                sorted.add(node)
            }

            unmarked.addAll(graph.keys)
            while (unmarked.isNotEmpty()) {
                visit(unmarked.first())
            }

            return sorted
        }

        override fun run(application: Application) {
            Log.d(
                TAG, "Check cyclic dependence: " + topologicalSort()
                    .joinToString {
                        it.qualifiedName ?: ""
                    }
            )
        }
    }

    internal inner class CheckCyclicDependenceTaskInfo : InternalTaskInfo(
        CheckCyclicDependenceTask::class,
        executor = TaskExecutorType.Async
    ) {
        lateinit var tasksToBeAnalyzed: Tasks

        override fun newInstance(): TaskExecutor {
            return CheckCyclicDependenceTask(tasksToBeAnalyzed)
        }
    }

    fun start() {
        val start = SystemClock.uptimeMillis()
        BACKGROUND_THREAD.executor.execute {
            TraceCompat.beginSection(TAG)
            val it = ServiceLoader.load(TaskInfo::class.java, classLoader)
                .iterator()
            var tasksBuilder: Tasks.Builder? = null
            var awaitDependencies: ArrayList<KClass<out TaskExecutor>>? = null
            var checkCyclicDependenceTask: CheckCyclicDependenceTaskInfo? = null
            while (it.hasNext()) {
                if (tasksBuilder == null) {
                    tasksBuilder = Tasks.Builder(128)
                    if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        checkCyclicDependenceTask = CheckCyclicDependenceTaskInfo()
                        tasksBuilder.add(checkCyclicDependenceTask)
                    }
                }
                val task = it.next()
                tasksBuilder.add(task)
                if (task.executor == TaskExecutorType.Main
                    || task.executor == TaskExecutorType.Await
                    || task.executor == TaskExecutorType.RemoteAwait
                ) {
                    if (awaitDependencies == null) {
                        awaitDependencies = ArrayList(128)
                    }
                    awaitDependencies.add(task.type)
                }
            }

            if (tasksBuilder == null) {
                val time = SystemClock.uptimeMillis() - start
                awaitTasksFinished(time)
                allTasksFinished(time)
                return@execute
            }

            val tasks = tasksBuilder.build()
            checkCyclicDependenceTask?.tasksToBeAnalyzed = tasks
            val realTasksBuilder = Tasks.Builder(
                tasks.size + internalTasks.size
            )

            realTasksBuilder.addAll(tasks)

            if (!awaitDependencies.isNullOrEmpty()) {
                val shutdownExclusiveMainThreadTask = object : InternalTaskInfo(
                    ShutdownExclusiveMainThreadTask::class,
                    dependencies = awaitDependencies
                ) {
                    override fun newInstance(): TaskExecutor {
                        return ShutdownExclusiveMainThreadTask(start)
                    }

                }
                realTasksBuilder.add(shutdownExclusiveMainThreadTask)
            } else {
                awaitTasksFinished(SystemClock.uptimeMillis() - start)
            }

            val shutdownBackgroundThreadTask = object : InternalTaskInfo(
                ShutdownBackgroundThreadTask::class,
                dependencies = tasks.keys
            ) {
                override fun newInstance(): TaskExecutor {
                    return ShutdownBackgroundThreadTask(start)
                }
            }

            realTasksBuilder.add(shutdownBackgroundThreadTask)
            val realTasks = realTasksBuilder.build()
            val results = ConcurrentHashMap<String, Parcelable>(realTasks.size)
            val jobs = ArrayMap<TaskInfo, Job>(realTasks.size)
            for (task in realTasks.values) {
                if (jobs.size < realTasks.size) {
                    startJobs(task, results, realTasks, jobs)
                } else {
                    break
                }
            }
            TraceCompat.endSection()
        }
    }

}