package open.source.multitask

import android.os.Parcel
import android.os.Parcelable

class RemoteTaskException(
    message: String?,
    private val stackTraceElements: Array<StackTraceElement>
) : Exception(message), Parcelable {

    constructor(throwable: Throwable) : this(
        "remote exception: " + throwable.javaClass.name,
        throwable.stackTrace
    )

    override fun fillInStackTrace(): Throwable {
        super.fillInStackTrace()
        stackTrace = stackTraceElements
        return this
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(message)
        parcel.writeInt(stackTraceElements.size)
        stackTraceElements.forEach {
            parcel.writeSerializable(it)
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<RemoteTaskException> {

        override fun createFromParcel(parcel: Parcel): RemoteTaskException {
            val message = parcel.readString()
            val count = parcel.readInt()
            val stack = Array(count) {
                parcel.readSerializable() as StackTraceElement
            }
            return RemoteTaskException(message, stack)
        }

        override fun newArray(size: Int): Array<RemoteTaskException?> {
            return arrayOfNulls(size)
        }
    }
}