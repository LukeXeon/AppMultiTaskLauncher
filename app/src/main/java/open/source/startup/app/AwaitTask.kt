package open.source.startup.app

import android.app.Application
import open.source.multitask.ActionTaskExecutor
import open.source.multitask.annotations.Task
import open.source.multitask.annotations.TaskExecutorType

@Task(name = "bbb", executor = TaskExecutorType.Await, dependencies = [MainTask::class])
class AwaitTask : ActionTaskExecutor() {
    override fun run(application: Application) {

    }
}