package open.source.multitask

import android.os.Parcel
import android.os.Parcelable

internal class RemoteTaskResult(val value: Parcelable?) : Parcelable {

    private constructor(parcel: Parcel, classLoader: ClassLoader?) : this(
        parcel.readParcelable<Parcelable>(
            classLoader
        )
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(value, flags)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.ClassLoaderCreator<RemoteTaskResult> {
        override fun createFromParcel(parcel: Parcel): RemoteTaskResult {
            return RemoteTaskResult(parcel, null)
        }

        override fun createFromParcel(source: Parcel, loader: ClassLoader?): RemoteTaskResult {
            return RemoteTaskResult(source, loader)
        }

        override fun newArray(size: Int): Array<RemoteTaskResult?> {
            return arrayOfNulls(size)
        }
    }
}