package open.source.multitask

import android.app.Application

interface TaskExecutor {
    suspend fun execute(application: Application)
}