package open.source.multitask

import android.app.Application
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.reflect.KClass

internal data class BuildInModules(
    val tasks: Map<KClass<out TaskExecutor>, TaskInfo>,
    val awaitDependencies: List<KClass<out TaskExecutor>>,
    val handlers: Map<KClass<out TaskExecutor>, HandlerInfo>
) {

    companion object {
        private const val PRE_ALLOC_SIZE = 64
        private const val TAG = "BuildInModules"
        private val MUTEX = Mutex()
        private lateinit var INSTANCE: BuildInModules
        suspend fun get(application: Application): BuildInModules {
            trace(TAG, "get") {
                MUTEX.withLock {
                    if (!::INSTANCE.isInitialized) {
                        val awaitDependencies = ArrayList<KClass<out TaskExecutor>>(PRE_ALLOC_SIZE)
                        val handlers = HashMap<KClass<out TaskExecutor>, HandlerInfo>(
                            PRE_ALLOC_SIZE
                        )
                        val tasks = HashMap<KClass<out TaskExecutor>, TaskInfo>(PRE_ALLOC_SIZE)
                        val it = ServiceLoader.load(ModuleInfo::class.java, application.classLoader)
                            .iterator()
                        while (it.hasNext()) {
                            val module = it.next()
                            for (task in module.tasks) {
                                tasks[task.type] = task
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
                        INSTANCE = BuildInModules(tasks, awaitDependencies, handlers)
                    }
                }
            }
            return INSTANCE
        }
    }
}