package open.source.multitask

import android.os.Parcel
import android.os.Parcelable

internal object NullMarker : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
    }

    override fun describeContents(): Int {
        return 0
    }

    @JvmField
    val CREATOR = object : Parcelable.Creator<NullMarker?> {
        override fun createFromParcel(`in`: Parcel): NullMarker {
            return NullMarker
        }

        override fun newArray(size: Int): Array<NullMarker?> {
            return arrayOfNulls(size)
        }
    }
}
