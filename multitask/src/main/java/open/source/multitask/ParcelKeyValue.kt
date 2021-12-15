package open.source.multitask

import android.os.Parcel
import android.os.Parcelable

internal data class ParcelKeyValue(
    val key: String?,
    val value: Parcelable?
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString(),
        parcel.readParcelable(ParcelKeyValue::class.java.classLoader)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(key)
        parcel.writeParcelable(value, flags)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ParcelKeyValue> {
        override fun createFromParcel(parcel: Parcel): ParcelKeyValue {
            return ParcelKeyValue(parcel)
        }

        override fun newArray(size: Int): Array<ParcelKeyValue?> {
            return arrayOfNulls(size)
        }
    }
}