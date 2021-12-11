package open.source.multitask

import android.app.Application
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.collection.ArrayMap
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
import kotlin.reflect.KClass

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

    private val internalTasks = Array(2) { UUID.randomUUID().toString() }
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
        dependencies: List<Job>
    ): Job {
        return GlobalScope.launch(if (task.runner == TaskRunnerType.Main) mainThread else backgroundThread) {
            dependencies.joinAll()
            val name = task.name
            val start = SystemClock.uptimeMillis()
            if (!internalTasks.contains(name)) {
                tracker.onTaskStarted(name)
            }
            task.create().execute(application)
            if (!internalTasks.contains(name)) {
                tracker.onTaskFinished(name, SystemClock.uptimeMillis() - start)
            }
        }
    }

    private fun startJobs(
        application: Application,
        task: TaskInfo,
        types: Map<KClass<out TaskExecutor>, TaskInfo>,
        jobs: ArrayMap<TaskInfo, Job>
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
    fun start(application: Application) {
        TraceCompat.beginSection(TAG)
        val start = SystemClock.uptimeMillis()
        val tasks = ServiceLoader.load(TaskInfo::class.java)
            .iterator()
            .asSequence()
            .map {
                it.type to it
            }.toMap(ArrayMap(16))
        if (tasks.isEmpty) {
            val time = SystemClock.uptimeMillis() - start
            (mainThread.executor as ExecutorService).shutdown()
            (backgroundThread.executor as ExecutorService).shutdown()
            tracker.onAwaitTasksFinished(time)
            tracker.onAllTasksFinished(time)
            return
        }
        val realTasks: MutableMap<KClass<out TaskExecutor>, TaskInfo> = ArrayMap(
            tasks.size + internalTasks.size
        )
        realTasks.putAll(tasks)

        class AwaitFinishedTask : TaskExecutor {
            override suspend fun execute(application: Application) {
                (mainThread.executor as ExecutorService).shutdown()
                tracker.onAwaitTasksFinished(SystemClock.uptimeMillis() - start)
            }
        }

        val awaitDependencies = ArrayList<KClass<out TaskExecutor>>(tasks.size)

        for (task in tasks.values) {
            if (task.isAwait) {
                awaitDependencies.add(task.type)
            }
        }

        val awaitFinishedTask = object : TaskInfo(
            name = internalTasks[0],
            dependencies = awaitDependencies
        ) {
            override fun newInstance(): TaskExecutor {
                return AwaitFinishedTask()
            }

            override val type: KClass<out TaskExecutor>
                get() = AwaitFinishedTask::class

        }
        realTasks[AwaitFinishedTask::class] = awaitFinishedTask

        class AllFinishedTask : TaskExecutor {
            override suspend fun execute(application: Application) {
                (backgroundThread.executor as ExecutorService).shutdown()
                tracker.onAllTasksFinished(SystemClock.uptimeMillis() - start)
            }
        }

        val allFinishedTask = object : TaskInfo(
            internalTasks[1],
            dependencies = tasks.map { it.key }
        ) {
            override fun newInstance(): TaskExecutor {
                return AllFinishedTask()
            }

            override val type: KClass<out TaskExecutor>
                get() = AllFinishedTask::class

        }
        realTasks[AllFinishedTask::class] = allFinishedTask

        val jobs = ArrayMap<TaskInfo, Job>(realTasks.size)
        for (task in realTasks.values) {
            if (jobs.size < realTasks.size) {
                startJobs(application, task, realTasks, jobs)
            } else {
                break
            }
        }
        TraceCompat.endSection()
    }

}