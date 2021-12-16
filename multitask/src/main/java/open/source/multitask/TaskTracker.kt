package open.source.multitask

import android.util.Log
import kotlin.reflect.KClass

interface TaskTracker {
    fun onTaskStart(type: KClass<out TaskExecutor>, name: String)

    fun onTaskFinish(type: KClass<out TaskExecutor>, name: String, time: Long)

    fun onStartFinish(time: Long)

    fun onTasksFinish(time: Long)

    companion object Default : TaskTracker {
        private const val TAG = "TaskTracker"

        override fun onTaskStart(
            type: KClass<out TaskExecutor>,
            name: String
        ) {
            Log.v(
                TAG, "task startup: $name, " +
                        "thread: ${Thread.currentThread().name}"
            )
        }

        override fun onTaskFinish(
            type: KClass<out TaskExecutor>,
            name: String,
            time: Long
        ) {
            Log.v(
                TAG, "task finished: $name, " +
                        "thread: ${Thread.currentThread().name}, " +
                        "use time: $time"
            )
        }

        override fun onStartFinish(time: Long) {
            Log.d(TAG, "start finish, use time: $time")
        }

        override fun onTasksFinish(time: Long) {
            Log.d(TAG, "tasks finished, use time: $time")
        }

    }
}