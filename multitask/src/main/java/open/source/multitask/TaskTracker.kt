package open.source.multitask

import android.util.Log

interface TaskTracker {
    suspend fun onTaskStartup(name: String)

    suspend fun onTaskFinished(name: String, time: Long)

    suspend fun onUnlockMainThread(time: Long)

    suspend fun onStartupFinished(time: Long)

    companion object Default : TaskTracker {
        private const val TAG = "TaskTracker"

        override suspend fun onTaskStartup(name: String) {
            Log.v(
                TAG, "task startup: $name, " +
                        "thread: ${Thread.currentThread().name}"
            )
        }

        override suspend fun onTaskFinished(name: String, time: Long) {
            Log.v(
                TAG, "task finished: $name, " +
                        "thread: ${Thread.currentThread().name}, " +
                        "use time: $time"
            )
        }

        override suspend fun onUnlockMainThread(time: Long) {
            Log.d(TAG, "main thread unlock, use time: $time")
        }

        override suspend fun onStartupFinished(time: Long) {
            Log.d(TAG, "all task finished, use time: $time")
        }

    }
}