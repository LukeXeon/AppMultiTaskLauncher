package open.source.multitask

import android.os.Bundle
import android.os.Parcelable
import androidx.collection.ArraySet

internal class FromBundleMap(private val bundle: Bundle) : Map<String, Parcelable> {
    override val entries: Set<Map.Entry<String, Parcelable>> by lazy {
        bundle.keySet().mapTo(ArraySet(bundle.size())) {
            object : Map.Entry<String, Parcelable> {
                override val key: String
                    get() = it
                override val value: Parcelable
                    get() = bundle.getParcelable(it)!!
            }
        }
    }
    override val keys: Set<String>
        get() = bundle.keySet()
    override val size: Int
        get() = bundle.size()
    override val values: Collection<Parcelable> by lazy {
        object : AbstractCollection<Parcelable>() {
            override val size: Int
                get() = bundle.size()

            override fun iterator(): Iterator<Parcelable> {
                return bundle.keySet()
                    .asSequence()
                    .mapNotNull {
                        bundle.getParcelable(it)
                    }.iterator()
            }
        }
    }

    override fun containsKey(key: String): Boolean {
        return bundle.containsKey(key)
    }

    override fun containsValue(value: Parcelable): Boolean {
        return bundle.keySet().any { bundle.getParcelable<Parcelable>(it) == value }
    }

    override fun get(key: String): Parcelable? {
        return bundle.getParcelable(key)
    }

    override fun isEmpty(): Boolean {
        return bundle.isEmpty
    }
}