package open.source.startup.app

import android.app.Application
import open.source.multitask.TaskExecutor
import open.source.multitask.annotations.Task
import open.source.multitask.annotations.TaskExecutorType

@Task(name = "aaaa", executor = TaskExecutorType.Main)
class MainTask : TaskExecutor {
    override suspend fun execute(application: Application) {
    }
}