package open.source.multitask

import android.os.Parcel
import android.os.Parcelable
import java.lang.Exception

class RemoteTaskException(
    message: String?,
    private val stacks: Array<StackTraceElement>
) : Exception(message.apply {
    temp.set(stacks)
}), Parcelable {

    init {
        temp.remove()
    }

    constructor(throwable: Throwable) : this(
        "remote exception: " + throwable.javaClass.name,
        throwable.stackTrace
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(message)
        parcel.writeInt(stacks.size)
        stacks.forEach {
            parcel.writeString(it.className)
            parcel.writeString(it.fileName)
            parcel.writeString(it.methodName)
            parcel.writeInt(it.lineNumber)
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun fillInStackTrace(): Throwable {
        super.fillInStackTrace()
        // 系统阴间case，调用这个函数的时候，子类构造函数还没执行完成，取不到子类变量
        stackTrace = temp.get() ?: stacks
        return this
    }

    companion object CREATOR : Parcelable.Creator<RemoteTaskException> {

        private val temp = ThreadLocal<Array<StackTraceElement>>()

        override fun createFromParcel(parcel: Parcel): RemoteTaskException {
            val message = parcel.readString()
            val count = parcel.readInt()
            val stacks = Array(count) {
                StackTraceElement(
                    parcel.readString(),
                    parcel.readString(),
                    parcel.readString(),
                    parcel.readInt()
                )
            }
            return RemoteTaskException(message, stacks)
        }

        override fun newArray(size: Int): Array<RemoteTaskException?> {
            return arrayOfNulls(size)
        }
    }
}