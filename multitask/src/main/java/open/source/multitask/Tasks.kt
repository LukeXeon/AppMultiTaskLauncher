package open.source.multitask

import java.util.*
import kotlin.collections.AbstractList
import kotlin.collections.ArrayList
import kotlin.reflect.KClass

internal class Tasks internal constructor(private val sortedList: List<TaskInfo>) :
    Map<KClass<out TaskExecutor>, TaskInfo>, Set<TaskInfo> {
    inner class KeySetList : AbstractList<KClass<out TaskExecutor>>(),
        Set<KClass<out TaskExecutor>> {
        override val size: Int
            get() = sortedList.size

        override fun iterator(): Iterator<KClass<out TaskExecutor>> {
            return sortedList.iterator()
                .asSequence()
                .map {
                    it.type
                }.iterator()
        }

        override fun get(index: Int): KClass<out TaskExecutor> {
            return sortedList[index].type
        }

        override fun spliterator(): Spliterator<KClass<out TaskExecutor>> {
            return super<AbstractList>.spliterator()
        }
    }

    class Builder(initialCapacity: Int) {
        private val list = ArrayList<TaskInfo>(initialCapacity)

        fun add(task: TaskInfo) {
            list.add(task)
        }

        fun addAll(tasks: Tasks) {
            list.addAll(tasks.sortedList)
        }

        fun build(): Tasks {
            list.sortBy { it.type.qualifiedName }
            return Tasks(list)
        }
    }

    override val entries: Set<TaskInfo>
        get() = this

    override val keys: KeySetList by lazy {
        KeySetList()
    }

    override val size: Int
        get() = sortedList.size

    override val values: List<TaskInfo>
        get() = sortedList

    override fun containsKey(key: KClass<out TaskExecutor>): Boolean {
        return sortedList.binarySearch {
            compareValues(
                it.key.qualifiedName,
                key.qualifiedName
            )
        } != -1
    }



    override fun containsValue(value: TaskInfo): Boolean {
        return sortedList.binarySearch {
            compareValues(
                it.type.qualifiedName,
                value.type.qualifiedName
            )
        } != -1
    }

    override fun get(key: KClass<out TaskExecutor>): TaskInfo? {
        val index = sortedList.binarySearch {
            compareValues(
                it.key.qualifiedName,
                key.qualifiedName
            )
        }
        return if (index != -1) {
            sortedList[index]
        } else {
            null
        }
    }

    override fun isEmpty(): Boolean {
        return sortedList.isEmpty()
    }

    override fun contains(element: TaskInfo): Boolean {
        return sortedList.contains(element)
    }

    override fun containsAll(elements: Collection<TaskInfo>): Boolean {
        return sortedList.containsAll(elements)
    }

    override fun iterator(): Iterator<TaskInfo> {
        return sortedList.iterator()
    }

}