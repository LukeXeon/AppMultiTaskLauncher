package open.source.multitask

import android.app.Application
import android.net.Uri
import android.os.Bundle

open class RemoteTask @JvmOverloads constructor(
    name: String,
    private val path: Uri,
    private val method: String,
    private val args: Bundle? = null,
    dependencies: List<Class<out Task>> = emptyList()
) : Task(name, false, dependencies) {

    final override suspend fun execute(application: Application) {
        val result = try {
            application.contentResolver.call(path, method, null, args)!!
        } catch (e: Throwable) {
            handleException(e)
            return
        }
        result.classLoader = RemoteTaskException::class.java.classLoader
        val code = try {
            result.getInt(RemoteTaskExecutor.CODE_KEY)
        } catch (e: Throwable) {
            handleException(e)
        }
        when (code) {
            RemoteTaskExecutor.RESULT_OK -> {
                return
            }
            RemoteTaskExecutor.RESULT_EXCEPTION -> {
                val exception = result.getParcelable<RemoteTaskException>(
                    RemoteTaskExecutor.EXCEPTION_KEY
                )!!
                handleException(exception)
            }
            else -> throw AssertionError()
        }

    }

    open fun handleException(throwable: Throwable) {
        throw throwable
    }
}