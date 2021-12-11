package open.source.multitask

abstract class AwaitTask(
    name: String,
    isMainThread: Boolean = false,
    internal val isAwait: Boolean = true,
    dependencies: Set<Class<out Task>> = emptySet()
) : Task(name, isMainThread, dependencies)