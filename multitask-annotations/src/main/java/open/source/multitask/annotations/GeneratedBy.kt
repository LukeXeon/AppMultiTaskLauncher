package open.source.multitask.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class GeneratedBy(val value: KClass<*>)
