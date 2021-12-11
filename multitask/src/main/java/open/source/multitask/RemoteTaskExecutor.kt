package open.source.multitask

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.collection.ArrayMap

abstract class RemoteTaskExecutor : ContentProvider() {

    companion object {
        internal const val CODE_KEY = "code"
        internal const val EXCEPTION_KEY = "exception"
        internal const val RESULT_OK = 1
        internal const val RESULT_EXCEPTION = 2
    }

    private val methods = ArrayMap<String, Pair<RemoteMethod, BooleanArray>>()

    final override fun onCreate(): Boolean {
        return true
    }

    protected fun addMethod(name: String, method: RemoteMethod) {
        synchronized(methods) {
            if (methods.put(name, method to booleanArrayOf(false)) != null) {
                throw IllegalArgumentException()
            }
        }
    }

    interface RemoteMethod {
        fun execute(application: Application, method: String, args: Bundle?)
    }

    final override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle {
        return try {
            val (invoker, lock) = synchronized(methods) {
                methods.getValue(method)
            }
            synchronized(lock) {
                if (!lock[0]) {
                    invoker.execute(
                        context!!.applicationContext as Application,
                        method,
                        extras
                    )
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

}