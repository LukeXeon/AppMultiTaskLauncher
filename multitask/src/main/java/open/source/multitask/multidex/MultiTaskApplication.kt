package open.source.multitask.multidex

import androidx.multidex.MultiDexApplication
import open.source.multitask.MultiTask

open class MultiTaskApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        MultiTask.start(this)
    }
}