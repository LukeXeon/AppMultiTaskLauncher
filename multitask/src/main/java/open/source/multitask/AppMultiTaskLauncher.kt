package open.source.multitask

import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.collection.ArrayMap
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class AppMultiTaskLauncher {
    companion object {
        private const val TAG = "AppMultiTaskLauncher"
        private val REENTRY_CHECK = AtomicBoolean()
        private val COMPLETE_TASK_NAME = UUID.randomUUID().toString()
    }

    init {
        if (!REENTRY_CHECK.compareAndSet(false, true)) {
            throw IllegalStateException()
        }
    }

    private val tasks = LinkedList<Task>()
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
            if (name != COMPLETE_TASK_NAME) {
                Log.v(
                    TAG, "task started: $name, " +
                            "thread: ${Thread.currentThread().name}"
                )
            }
            action.execute(application)
            if (name != COMPLETE_TASK_NAME) {
                Log.v(
                    TAG, "task finish: $name, " +
                            "thread: ${Thread.currentThread().name}, " +
                            "use time: ${SystemClock.uptimeMillis() - start}"
                )
            }
        }
    }

    private fun startJobs(
        application: Application,
        task: Task,
        types: Map<Class<*>, Task>,
        jobs: ArrayMap<Task, Job>
    ): Job {
        var job = jobs[task]
        if (job == null) {
            val dependenciesJobs = LinkedList<Job>()
            for (dependencyType in task.dependencies) {
                val awaitTask = types.getValue(dependencyType)
                dependenciesJobs.add(startJobs(application, awaitTask, types, jobs))
            }
            job = startJob(application, task, dependenciesJobs)
            jobs[task] = job
        }
        return job
    }

    @MainThread
    fun addTask(task: Task): AppMultiTaskLauncher {
        tasks.add(task)
        return this
    }

    @MainThread
    fun start(application: Application) {
        val start = SystemClock.uptimeMillis()
        tasks.add(object : ActionTask(
            COMPLETE_TASK_NAME,
            false,
            tasks.map { it.javaClass }
        ) {
            override fun run(application: Application) {
                (mainThread.executor as ExecutorService).shutdown()
                (backgroundThread.executor as ExecutorService).shutdown()
                Log.d(TAG, "all task finished, use time: ${SystemClock.uptimeMillis() - start}")
            }
        })
        val types = ArrayMap<Class<*>, Task>(tasks.size)
        for (task in tasks) {
            if (types.put(task.javaClass, task) != null) {
                throw IllegalArgumentException()
            }
        }
        val jobs = ArrayMap<Task, Job>(tasks.size)
        for (task in tasks) {
            if (jobs.size < tasks.size) {
                startJobs(application, task, types, jobs)
            } else {
                break
            }
        }
    }

}