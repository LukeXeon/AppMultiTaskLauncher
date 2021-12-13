package open.source.multitask

import android.os.Bundle
import android.os.Parcelable

internal class MapTaskResults(private val map: Map<String, Parcelable>) : TaskResults {
    override fun get(name: String): Parcelable? {
        return map[name]
    }

    override fun containsKey(name: String?): Boolean {
        return map.containsKey(name)
    }

    override fun toBundle(): Bundle {
        val bundle = Bundle()
        for ((k, v) in map) {
            bundle.putParcelable(k, if (v is NullMarker) null else v)
        }
        return bundle
    }
}