package open.source.multitask

import android.util.Log

interface TaskTracker {
    fun onTaskStarted(name: String)

    fun onTaskFinished(name: String, time: Long)

    fun onAwaitTasksFinished(time: Long)

    fun onAllTasksFinished(time: Long)

    companion object Default : TaskTracker {
        private const val TAG = "TaskTracker"

        override fun onTaskStarted(name: String) {
            Log.v(
                TAG, "task started: $name, " +
                        "thread: ${Thread.currentThread().name}"
            )
        }

        override fun onTaskFinished(name: String, time: Long) {
            Log.v(
                TAG, "task finish: $name, " +
                        "thread: ${Thread.currentThread().name}, " +
                        "use time: $time"
            )
        }

        override fun onAwaitTasksFinished(time: Long) {
            Log.d(TAG, "await task finished, use time: $time")
        }

        override fun onAllTasksFinished(time: Long) {
            Log.d(TAG, "all task finished, use time: $time")
        }

    }
}