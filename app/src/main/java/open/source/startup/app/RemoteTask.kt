package open.source.startup.app

import android.app.Application
import open.source.multitask.TaskExecutor
import open.source.multitask.annotations.Task
import open.source.multitask.annotations.TaskExecutorType

@Task("ccc", executor = TaskExecutorType.Remote, isAwait = false, process = ":remote")
class RemoteTask : TaskExecutor {
    override suspend fun execute(application: Application) {

    }
}