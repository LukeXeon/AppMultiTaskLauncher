package open.source.startup.app

import android.app.Application
import open.source.multitask.ActionTaskExecutor
import open.source.multitask.TaskExecutor
import open.source.multitask.annotations.Task
import open.source.multitask.annotations.TaskExecutorType

@Task(name = "aaaa", executor = TaskExecutorType.Main)
class MainTask : ActionTaskExecutor() {
    override fun run(application: Application) {

    }
}