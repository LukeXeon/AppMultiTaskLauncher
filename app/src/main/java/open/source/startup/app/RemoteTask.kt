package open.source.startup.app

import android.app.Application
import open.source.multitask.ActionTaskExecutor
import open.source.multitask.annotations.Task
import open.source.multitask.annotations.TaskExecutorType

@Task("ccc", executor = TaskExecutorType.RemoteAsync, process = ":remote")
class RemoteTask : ActionTaskExecutor() {
    override fun run(application: Application) {

    }
}