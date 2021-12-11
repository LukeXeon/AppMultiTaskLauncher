package open.source.startup.app

import android.app.Application
import android.os.Bundle
import open.source.multitask.RemoteTaskExecutor

class AppRemoteTaskExecutor : RemoteTaskExecutor() {

    companion object {
        private const val TAG = "AppRemoteTaskExecutor"
    }

    init {
        addMethod("test", object : RemoteMethod {
            override fun execute(application: Application, method: String, args: Bundle?) {

            }
        })
    }
}