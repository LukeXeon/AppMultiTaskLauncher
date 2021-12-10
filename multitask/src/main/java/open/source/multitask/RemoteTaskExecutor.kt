package open.source.multitask

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

abstract class RemoteTaskExecutor : ContentProvider() {

    companion object {
        internal const val CODE_KEY = "code"
        internal const val EXCEPTION_KEY = "exception"
        internal const val RESULT_OK = 1
        internal const val RESULT_EXCEPTION = 2
    }

    final override fun onCreate(): Boolean {
        return true
    }

    abstract fun execute(method: String, args: Bundle)

    final override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle {
        return try {
            execute(method, extras!!)
            Bundle().apply {
                putInt(CODE_KEY, RESULT_OK)
            }
        } catch (e: Throwable) {
            Bundle().apply {
                putInt(CODE_KEY, RESULT_OK)
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