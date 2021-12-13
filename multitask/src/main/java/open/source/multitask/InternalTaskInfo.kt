package open.source.multitask

import open.source.multitask.annotations.TaskExecutorType
import kotlin.reflect.KClass

internal abstract class InternalTaskInfo(
    type: KClass<out TaskExecutor>,
    executor: TaskExecutorType = TaskExecutorType.Await,
    dependencies: Collection<KClass<out TaskExecutor>> = emptyList()
) : TaskInfo(
    type,
    type.java.name,
    executor,
    "",
    dependencies
)