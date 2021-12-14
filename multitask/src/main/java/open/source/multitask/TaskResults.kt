package open.source.multitask

import android.os.Parcelable
import kotlin.reflect.KClass

interface TaskResults {
    val keys: Set<String>

    val size: Int

    operator fun get(name: String): Parcelable?

    operator fun get(key: KClass<out TaskExecutor>): Parcelable? {
        return get(key.qualifiedName ?: "")
    }

    fun containsKey(name: String): Boolean

    fun containsKey(key: KClass<out TaskExecutor>): Boolean {
        return containsKey(key.qualifiedName ?: "")
    }
}