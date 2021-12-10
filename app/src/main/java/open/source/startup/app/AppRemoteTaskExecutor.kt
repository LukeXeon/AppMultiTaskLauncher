package open.source.startup.app

import android.os.Bundle
import android.util.Log
import open.source.multitask.RemoteTaskExecutor

class AppRemoteTaskExecutor : RemoteTaskExecutor() {
    override fun execute(method: String, args: Bundle) {
        if (method == "test") {
            Log.d(TAG, "execute: ")
            throw Exception()
        }
    }

    companion object {
        private const val TAG = "AppRemoteTaskExecutor"
    }
}