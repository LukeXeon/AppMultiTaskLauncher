package open.source.multitask

import android.util.Log

interface TaskTracker {
    fun taskStarted(name: String)

    fun taskFinished(name: String, time: Long)

    fun allTasksFinished(time: Long)

    companion object Default : TaskTracker {
        private const val TAG = "TaskTracker"

        override fun taskStarted(name: String) {
            Log.v(
                TAG, "task started: $name, " +
                        "thread: ${Thread.currentThread().name}"
            )
        }

        override fun taskFinished(name: String, time: Long) {
            Log.v(
                TAG, "task finish: $name, " +
                        "thread: ${Thread.currentThread().name}, " +
                        "use time: $time"
            )
        }

        override fun allTasksFinished(time: Long) {
            Log.d(TAG, "all task finished, use time: $time")
        }

    }
}