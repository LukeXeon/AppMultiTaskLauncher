package open.source.multitask

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.collection.ArrayMap
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass


open class RemoteTaskExecutor : ContentProvider() {

    companion object {
        private const val CODE_KEY = "code"
        private const val EXCEPTION_KEY = "exception"
        private const val RESULT_OK = 1
        private const val RESULT_EXCEPTION = 2
    }

    private val tasks = ArrayMap<String, BooleanArray>()

    final override fun onCreate(): Boolean {
        return true
    }

    final override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle {
        return try {
            val lock = synchronized(tasks) {
                tasks.getOrPut(method) {
                    booleanArrayOf(false)
                }
            }
            synchronized(lock) {
                if (!lock[0]) {
                    val application = context!!.applicationContext as Application
                    val executor = Class.forName(method).getDeclaredConstructor()
                        .apply {
                            isAccessible = true
                        }.newInstance() as TaskExecutor
                    runBlocking { executor.execute(application) }
                    lock[0] = true
                }
            }
            Bundle().apply {
                putInt(CODE_KEY, RESULT_OK)
            }
        } catch (e: Throwable) {
            Bundle().apply {
                putInt(CODE_KEY, RESULT_EXCEPTION)
                putParcelable(EXCEPTION_KEY, RemoteTaskException(e))
            }
        }
    }

    final override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    final override fun getType(uri: Uri): String? = null

    final override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? = null

    final override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    final override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    internal class Client(
        private val process: String,
        private val type: KClass<out TaskExecutor>,
    ) : TaskExecutor {

        override suspend fun execute(application: Application) {
            val result = try {
                application.contentResolver.call(
                    Uri.parse("content://${application.packageName}.remote-task-executor${process}"),
                    type.qualifiedName!!,
                    null,
                    null
                )!!
            } catch (e: Throwable) {
                handleException(e)
                return
            }
            result.classLoader = RemoteTaskException::class.java.classLoader
            val code = try {
                result.getInt(CODE_KEY)
            } catch (e: Throwable) {
                handleException(e)
            }
            when (code) {
                RESULT_OK -> {
                    return
                }
                RESULT_EXCEPTION -> {
                    val exception = result.getParcelable<RemoteTaskException>(
                        EXCEPTION_KEY
                    )!!
                    handleException(exception)
                }
                else -> throw AssertionError()
            }
        }

        private fun handleException(throwable: Throwable) {
            throw throwable
        }
    }

}