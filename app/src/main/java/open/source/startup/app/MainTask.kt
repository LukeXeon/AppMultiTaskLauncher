package open.source.startup.app

import android.app.Application
import open.source.multitask.Task
import open.source.multitask.TaskExecutor
import open.source.multitask.TaskRunnerType

@Task(name = "", runner = TaskRunnerType.Main)
class MainTask : TaskExecutor {
    override suspend fun execute(application: Application) {

    }
}