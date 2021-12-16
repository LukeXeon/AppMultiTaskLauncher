package open.source.multitask

import android.app.Application
import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.collections.ArrayList
import kotlin.reflect.KClass

internal data class BuildInModules(
    val tasks: List<TaskInfo>,
    val handlers: Map<KClass<out TaskExecutor>, HandlerInfo>
) {
    companion object {
        private const val TAG = "BuildInModules"
        private val MUTEX = Mutex()
        private lateinit var INSTANCE: BuildInModules
        suspend fun get(application: Application): BuildInModules {
            trace(TAG, "get") {
                MUTEX.withLock {
                    if (!::INSTANCE.isInitialized) {
                        val handlers = ArrayMap<KClass<out TaskExecutor>, HandlerInfo>(128)
                        val tasks = ArrayList<TaskInfo>(128)
                        val it = ServiceLoader.load(ModuleInfo::class.java, application.classLoader)
                            .iterator()
                        while (it.hasNext()) {
                            val module = it.next()
                            tasks.addAll(module.tasks)
                            for (handler in module.handlers) {
                                val h = handlers[handler.taskType]
                                if (h != null && h.priority >= handler.priority) {
                                    continue
                                }
                                handlers[handler.taskType] = handler
                            }
                        }
                        INSTANCE = BuildInModules(tasks, handlers)
                    }
                }
            }
            return INSTANCE
        }
    }
}