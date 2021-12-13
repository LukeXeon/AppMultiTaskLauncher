package open.source.multitask.processor

import javax.lang.model.element.TypeElement

data class HandlerInfoElement(
    val type: TypeElement,
    val taskType: TypeElement,
    val priority: Int
)