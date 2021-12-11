package open.source.startup.app

import android.app.Application
import open.source.multitask.AppMultiTaskLauncher

class ApplicationX : Application() {

    override fun onCreate() {
        super.onCreate()
        AppMultiTaskLauncher().start(this)
    }
}