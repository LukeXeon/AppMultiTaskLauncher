package open.source.multitask

import android.os.Parcelable
import java.util.concurrent.ConcurrentHashMap

internal class ConcurrentTaskResults(initialCapacity: Int) : TaskResults {
    internal val map = ConcurrentHashMap<String, Any>(initialCapacity)

    override fun get(name: String): Parcelable? {
        return map[name] as? Parcelable
    }

    override fun containsKey(name: String?): Boolean {
        return map.containsKey(name)
    }

    override fun keySet(): Set<String> {
        return map.keys
    }
}