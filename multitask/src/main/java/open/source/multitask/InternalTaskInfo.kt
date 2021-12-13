package open.source.multitask

import open.source.multitask.annotations.TaskExecutorType
import kotlin.reflect.KClass

internal abstract class InternalTaskInfo(
    type: KClass<out TaskExecutor>,
    dependencies: Collection<KClass<out TaskExecutor>>
) : TaskInfo(
    type,
    type.java.name,
    TaskExecutorType.Async,
    "",
    dependencies
)