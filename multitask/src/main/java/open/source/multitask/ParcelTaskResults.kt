package open.source.multitask

import android.os.Parcel
import android.os.Parcelable
import androidx.collection.ArrayMap

class ParcelTaskResults
private constructor(
    private val map: Map<String, Parcelable>
) : TaskResults, Parcelable {

    constructor(taskResults: TaskResults) : this(ArrayMap<String, Parcelable>(taskResults.size).apply {
        for (key in taskResults.keys) {
            this[key] = taskResults[key]
        }
    })

    override val keys: Set<String>
        get() = map.keys

    override val size: Int
        get() = map.size

    override fun get(name: String): Parcelable? {
        return map[name]
    }

    override fun containsKey(name: String): Boolean {
        return map.containsKey(name)
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(map.size)
        for ((key, value) in map) {
            parcel.writeString(key)
            parcel.writeParcelable(value, flags)
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ParcelTaskResults> {

        override fun createFromParcel(parcel: Parcel): ParcelTaskResults {
            val size = parcel.readInt()
            return if (size == 0) {
                ParcelTaskResults(emptyMap())
            } else {
                val map = ArrayMap<String, Parcelable>(size)
                repeat(size) {
                    val key = parcel.readString()
                    val value = parcel.readParcelable<Parcelable>(
                        ParcelTaskResults::class.java.classLoader
                    )
                    map[key] = value
                }
                ParcelTaskResults(map)
            }
        }

        override fun newArray(size: Int): Array<ParcelTaskResults?> {
            return arrayOfNulls(size)
        }
    }
}