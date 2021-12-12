package open.source.multitask.processor

import open.source.multitask.annotations.TaskExecutorType
import javax.lang.model.element.TypeElement

data class TaskInfo(
    val type: TypeElement,
    val name: String,
    val executor: TaskExecutorType,
    val isAwait: Boolean,
    val process: String,
    val dependencies: List<TypeElement>
)