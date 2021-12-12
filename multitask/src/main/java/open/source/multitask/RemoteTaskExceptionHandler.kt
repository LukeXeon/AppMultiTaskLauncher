package open.source.multitask

import android.os.Parcelable

interface RemoteTaskExceptionHandler {
    fun handleException(e: Throwable): Parcelable?

    companion object Default : RemoteTaskExceptionHandler {
        override fun handleException(e: Throwable): Parcelable? {
            throw e
        }
    }
}