package open.source.multitask

import android.app.Application
import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.collections.ArrayList
import kotlin.reflect.KClass

internal data class BuildInModules(
    val tasks: Map<KClass<out TaskExecutor>, TaskInfo>,
    val handlers: Map<KClass<out TaskExecutor>, HandlerInfo>,
    val taskTypes: Map<String, KClass<out TaskExecutor>>,
    val awaitDependencies: List<KClass<out TaskExecutor>>
) {
    companion object {
        internal const val PRE_ALLOC_SIZE = 128
        private const val TAG = "BuildInModules"
        private val MUTEX = Mutex()
        private lateinit var INSTANCE: BuildInModules
        suspend fun get(application: Application): BuildInModules {
            trace(TAG, "get") {
                MUTEX.withLock {
                    if (!::INSTANCE.isInitialized) {
                        val taskTypes = ArrayMap<String, KClass<out TaskExecutor>>(PRE_ALLOC_SIZE)
                        val awaitDependencies = ArrayList<KClass<out TaskExecutor>>(PRE_ALLOC_SIZE)
                        val handlers =
                            ArrayMap<KClass<out TaskExecutor>, HandlerInfo>(PRE_ALLOC_SIZE)
                        val tasks = ArrayMap<KClass<out TaskExecutor>, TaskInfo>(PRE_ALLOC_SIZE)
                        val it = ServiceLoader.load(ModuleInfo::class.java, application.classLoader)
                            .iterator()
                        while (it.hasNext()) {
                            val module = it.next()
                            for (task in module.tasks) {
                                tasks[task.type] = task
                                taskTypes[task.type.qualifiedName] = task.type
                                if (task.isAwait) {
                                    awaitDependencies.add(task.type)
                                }
                            }
                            for (handler in module.handlers) {
                                val h = handlers[handler.taskType]
                                if (h != null && h.priority >= handler.priority) {
                                    continue
                                }
                                handlers[handler.taskType] = handler
                            }
                        }
                        INSTANCE = BuildInModules(tasks, handlers, taskTypes, awaitDependencies)
                    }
                }
            }
            return INSTANCE
        }
    }
}