package open.source.multitask

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
open class MultiTaskApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MultiTask.start(this)
    }
}