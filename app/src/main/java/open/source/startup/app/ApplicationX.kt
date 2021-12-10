package open.source.startup.app

import android.app.Application
import open.source.multitask.ActionTask
import open.source.multitask.AppMultiTaskLauncher
import java.util.concurrent.ConcurrentLinkedQueue

class ApplicationX : Application() {

    private val queue = ConcurrentLinkedQueue<Runnable>()


    override fun onCreate() {
        super.onCreate()
        val task1 = object : ActionTask("1", true) {
            override fun run(application: Application) {

            }
        }
        val task2 = object : ActionTask("2", true) {
            override fun run(application: Application) {

            }
        }
        val task3 = object : ActionTask("3") {
            override fun run(application: Application) {

            }
        }
        val task4 = object : ActionTask(
            "4",
            isMainThread = false,
            dependencies = listOf(task1.javaClass, task2.javaClass)
        ) {
            override fun run(application: Application) {

            }
        }

        AppMultiTaskLauncher()
            .addTask(task1)
            .addTask(task2)
            .addTask(task3)
            .addTask(task4)
            .start(this)
    }
}