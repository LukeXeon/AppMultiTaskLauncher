package open.source.multitask

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

abstract class RemoteTaskExecutor : BroadcastReceiver() {

    companion object {
        internal const val EXCEPTION_KEY = "exception"
        internal const val RESULT_OK = 1
        internal const val RESULT_EXCEPTION = 2
        private val executor by lazy {
            ThreadPoolExecutor(
                0,
                max(4, Runtime.getRuntime().availableProcessors()),
                10,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
                object : ThreadFactory {
                    private val count = AtomicInteger(0)

                    override fun newThread(r: Runnable): Thread {
                        return object : Thread("startup-remote-" + count.getAndIncrement()) {
                            override fun run() {
                                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                                r.run()
                            }
                        }
                    }
                }
            )
        }
    }

    protected abstract fun execute(application: Application)

    final override fun onReceive(context: Context, intent: Intent?) {
        val application = context.applicationContext as Application
        val pendingIntent = goAsync()
        executor.execute {
            val exception = runCatching {
                execute(application)
            }.exceptionOrNull()
            if (exception != null) {
                val bundle = Bundle()
                bundle.putParcelable(EXCEPTION_KEY, RemoteTaskException(exception))
                pendingIntent.setResult(RESULT_EXCEPTION, null, bundle)
            } else {
                pendingIntent.setResult(RESULT_OK, null, null)
            }
            pendingIntent.finish()
        }
    }
}