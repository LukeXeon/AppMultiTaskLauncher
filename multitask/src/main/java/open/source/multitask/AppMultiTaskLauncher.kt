package open.source.multitask

import android.app.Application
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.collection.ArrayMap
import androidx.collection.ArraySet
import androidx.core.os.TraceCompat
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList
import kotlin.math.max

class AppMultiTaskLauncher @JvmOverloads constructor(
    private val tracker: TaskTracker = TaskTracker.Default
) {
    companion object {
        private const val TAG = "AppMultiTaskLauncher"
        private val REENTRY_CHECK = AtomicBoolean()
    }

    init {
        if (!REENTRY_CHECK.compareAndSet(false, true)) {
            throw IllegalStateException()
        }
    }

    private val skipTaskNames = Array(2) { UUID.randomUUID().toString() }
    private val tasks = LinkedList<AwaitTask>()
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
        action: Task,
        dependencies: List<Job>
    ): Job {
        return GlobalScope.launch(if (action.isMainThread) mainThread else backgroundThread) {
            dependencies.joinAll()
            val name = action.name
            val start = SystemClock.uptimeMillis()
            if (!skipTaskNames.contains(name)) {
                tracker.taskStarted(name)
            }
            action.execute(application)
            if (!skipTaskNames.contains(name)) {
                tracker.taskFinished(name, SystemClock.uptimeMillis() - start)
            }
        }
    }

    private fun startJobs(
        application: Application,
        task: Task,
        types: Map<Class<*>, Task>,
        jobs: ArrayMap<Task, Job>
    ): Job {
        return jobs.getOrPut(task) {
            if (task.dependencies.isEmpty()) {
                startJob(application, task, emptyList())
            } else {
                val dependenciesJobs = ArrayList<Job>(task.dependencies.size)
                for (dependencyType in task.dependencies) {
                    val awaitTask = types.getValue(dependencyType)
                    dependenciesJobs.add(startJobs(application, awaitTask, types, jobs))
                }
                startJob(application, task, dependenciesJobs)
            }
        }
    }

    @MainThread
    fun addTask(task: AwaitTask): AppMultiTaskLauncher {
        tasks.add(task)
        return this
    }

    @MainThread
    fun addTasks(t: Collection<AwaitTask>): AppMultiTaskLauncher {
        tasks.addAll(t)
        return this
    }

    @MainThread
    fun start(application: Application) {
        TraceCompat.beginSection(TAG)
        val start = SystemClock.uptimeMillis()
        val allTasks: MutableMap<Class<out AwaitTask>, AwaitTask> = ArrayMap(tasks.size)
        for (task in tasks) {
            if (allTasks.put(task.javaClass, task) != null) {
                throw IllegalArgumentException()
            }
        }
        val awaitDependencies = ArraySet<Class<out Task>>(tasks.size)
        for ((type, task) in allTasks) {
            if (task.isAwait) {
                awaitDependencies.add(type)
            }
        }
        val runningTasks = ArrayMap<Class<*>, Task>(tasks.size + 2)
        runningTasks.putAll(allTasks)
        val awaitFinishedTask = object : Task(
            skipTaskNames[0],
            isMainThread = false,
            dependencies = awaitDependencies
        ) {
            override suspend fun execute(application: Application) {
                (mainThread.executor as ExecutorService).shutdown()
                tracker.awaitTasksFinished(SystemClock.uptimeMillis() - start)
            }
        }
        runningTasks[awaitFinishedTask.javaClass] = awaitFinishedTask
        val allFinishedTask = object : Task(
            skipTaskNames[1],
            isMainThread = false,
            dependencies = allTasks.keys
        ) {
            override suspend fun execute(application: Application) {
                (backgroundThread.executor as ExecutorService).shutdown()
                tracker.allTasksFinished(SystemClock.uptimeMillis() - start)
            }
        }
        runningTasks[allFinishedTask.javaClass] = allFinishedTask
        val jobs = ArrayMap<Task, Job>(runningTasks.size)
        for (task in runningTasks.values) {
            if (jobs.size < runningTasks.size) {
                startJobs(application, task, runningTasks, jobs)
            } else {
                break
            }
        }
        TraceCompat.endSection()
    }

}