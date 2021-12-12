package open.source.multitask

import android.app.Application
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.collection.ArrayMap
import androidx.core.os.TraceCompat
import kotlinx.coroutines.*
import open.source.multitask.annotations.TaskExecutorType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.reflect.KClass

class MultiTask @JvmOverloads @MainThread constructor(
    private val tracker: TaskTracker = TaskTracker.Default,
    private val uncaughtExceptionHandler: RemoteTaskExceptionHandler = RemoteTaskExceptionHandler.Default,
    private val classLoader: ClassLoader? = MultiTask::class.java.classLoader
) {
    companion object {
        private const val TAG = "AppMultiTaskLauncher"
        private val REENTRY_CHECK = AtomicBoolean()
        private fun <K, V> createMap(capacity: Int): MutableMap<K, V> {
            return ArrayMap(capacity)
        }

        fun start(application: Application) {
            MultiTask().start(application)
        }
    }

    init {
        if (!REENTRY_CHECK.compareAndSet(false, true)) {
            throw IllegalStateException()
        }
    }

    private val internalTasks = arrayOf(AwaitTaskFinished::class, AllTaskFinished::class)
    private val mainThread = ExclusiveMainThreadExecutor()
        .asCoroutineDispatcher()
    private val backgroundThread = ScheduledThreadPoolExecutor(
        max(4, Runtime.getRuntime().availableProcessors()),
        object : ThreadFactory {

            private val count = AtomicInteger(0)

            override fun newThread(r: Runnable): Thread {
                return object : Thread("startup-background-" + count.getAndIncrement()) {
                    override fun run() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                        r.run()
                    }
                }
            }
        }
    ).asCoroutineDispatcher()

    private fun startJob(
        application: Application,
        task: TaskInfo,
        results: MutableMap<String, Parcelable>,
        dependencies: List<Job>
    ): Job {
        return GlobalScope.launch(if (task.executor == TaskExecutorType.Main) mainThread else backgroundThread) {
            dependencies.forEach { it.join() }
            val name = task.name
            if (!internalTasks.contains(task.type)) {
                tracker.onTaskStarted(name)
            }
            val start = SystemClock.uptimeMillis()
            val result = task.execute(application, results, uncaughtExceptionHandler)
            val time = SystemClock.uptimeMillis() - start
            if (!internalTasks.contains(task.type)) {
                tracker.onTaskFinished(name, time)
            }
            val key = task.type.qualifiedName
            if (result != null && !key.isNullOrEmpty()) {
                results[key] = result
            }
        }
    }

    private fun startJobs(
        application: Application,
        task: TaskInfo,
        results: MutableMap<String, Parcelable>,
        types: Map<KClass<out TaskExecutor>, TaskInfo>,
        jobs: MutableMap<TaskInfo, Job>
    ): Job {
        return jobs.getOrPut(task) {
            if (task.dependencies.isEmpty()) {
                startJob(application, task, results, emptyList())
            } else {
                val dependenciesJobs = ArrayList<Job>(task.dependencies.size)
                for (dependencyType in task.dependencies) {
                    val awaitTask = types.getValue(dependencyType)
                    dependenciesJobs.add(startJobs(application, awaitTask, results, types, jobs))
                }
                startJob(application, task, results, dependenciesJobs)
            }
        }
    }

    private fun awaitTasksFinished(time: Long) {
        (mainThread.executor as ExecutorService).shutdown()
        tracker.onAwaitTasksFinished(time)
    }

    private fun allTasksFinished(time: Long) {
        (backgroundThread.executor as ExecutorService).shutdown()
        tracker.onAllTasksFinished(time)
    }

    internal inner class AwaitTaskFinished(private val start: Long) : ActionTaskExecutor() {
        override fun run(application: Application) {
            awaitTasksFinished(SystemClock.uptimeMillis() - start)
        }
    }

    internal inner class AllTaskFinished(private val start: Long) : ActionTaskExecutor() {
        override fun run(application: Application) {
            allTasksFinished(SystemClock.uptimeMillis() - start)
        }
    }

    fun start(application: Application) {
        val start = SystemClock.uptimeMillis()
        backgroundThread.executor.execute {
            TraceCompat.beginSection(TAG)
            val it = ServiceLoader.load(TaskInfo::class.java, classLoader)
                .iterator()
            var tasks: MutableMap<KClass<out TaskExecutor>, TaskInfo>? = null
            var awaitDependencies: ArrayList<KClass<out TaskExecutor>>? = null
            while (it.hasNext()) {
                if (tasks == null) {
                    tasks = createMap(128)
                }
                val task = it.next()
                tasks[task.type] = task
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

            if (tasks == null || tasks.isEmpty()) {
                val time = SystemClock.uptimeMillis() - start
                awaitTasksFinished(time)
                allTasksFinished(time)
                return@execute
            }
            val realTasks = createMap<KClass<out TaskExecutor>, TaskInfo>(
                tasks.size + internalTasks.size
            )
            realTasks.putAll(tasks)

            if (!awaitDependencies.isNullOrEmpty()) {
                val awaitFinishedTask = object : TaskInfo(
                    name = AwaitTaskFinished::class.qualifiedName!!,
                    dependencies = awaitDependencies
                ) {
                    override fun newInstance(): TaskExecutor {
                        return AwaitTaskFinished(start)
                    }

                    override val type: KClass<out TaskExecutor>
                        get() = AwaitTaskFinished::class

                }
                realTasks[awaitFinishedTask.type] = awaitFinishedTask
            } else {
                awaitTasksFinished(SystemClock.uptimeMillis() - start)
            }

            val allFinishedTask = object : TaskInfo(
                name = AllTaskFinished::class.qualifiedName!!,
                dependencies = tasks.keys
            ) {
                override fun newInstance(): TaskExecutor {
                    return AllTaskFinished(start)
                }

                override val type: KClass<out TaskExecutor>
                    get() = AllTaskFinished::class

            }
            realTasks[allFinishedTask.type] = allFinishedTask
            val results = ConcurrentHashMap<String, Parcelable>(realTasks.size)
            val jobs = ArrayMap<TaskInfo, Job>(realTasks.size)
            for (task in realTasks.values) {
                if (jobs.size < realTasks.size) {
                    startJobs(application, task, results, realTasks, jobs)
                } else {
                    break
                }
            }
            TraceCompat.endSection()
        }
    }

}