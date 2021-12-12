package open.source.startup.app

import android.app.Application
import open.source.multitask.TaskExecutor
import open.source.multitask.annotations.Task
import open.source.multitask.annotations.TaskExecutorType

@Task(name = "bbb", executor = TaskExecutorType.Async, dependencies = [MainTask::class])
class AwaitTask : TaskExecutor {
    override suspend fun execute(application: Application) {
    }
}