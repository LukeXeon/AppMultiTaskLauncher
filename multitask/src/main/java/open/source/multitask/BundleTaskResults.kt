package open.source.multitask

import android.os.Bundle
import android.os.Parcelable

internal class BundleTaskResults(private val bundle: Bundle) : TaskResults {
    override fun get(name: String): Parcelable? {
        return bundle.getParcelable(name)
    }

    override fun containsKey(name: String?): Boolean {
        return bundle.containsKey(name)
    }

    override fun toBundle(): Bundle {
        return bundle
    }
}