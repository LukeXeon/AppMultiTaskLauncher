package open.source.multitask.processor

import androidx.annotation.Keep
import androidx.annotation.RestrictTo
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.MemberName.Companion.member
import open.source.multitask.annotations.TaskUncaughtExceptionHandler
import open.source.multitask.annotations.Task
import open.source.multitask.annotations.TaskExecutorType
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.util.*
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic
import javax.tools.StandardLocation
import kotlin.collections.ArrayList

@AutoService(value = [Processor::class])
class TaskInfoProcessor : AbstractProcessor() {

    companion object {
        private const val ANDROID_HANDLER_INFO_CLASS_NAME = "open.source.multitask.HandlerInfo"
        private const val ANDROID_UNCAUGHT_EXCEPTION_HANDLER_CLASS_NAME =
            "open.source.multitask.UncaughtExceptionHandler"
        private const val ANDROID_TASK_INFO_CLASS_NAME = "open.source.multitask.TaskInfo"
        private const val ANDROID_TASK_EXECUTOR_CLASS_NAME = "open.source.multitask.TaskExecutor"
        private const val ANDROID_MODULE_INFO_CLASS_NAME = "open.source.multitask.ModuleInfo"
        private const val ANDROID_MODULES_PACKAGE_NAME = "open.source.multitask.modules"
    }

    private val tasks = ArrayList<TaskInfoElement>()
    private val handlers = ArrayList<HandlerInfoElement>()
    private val exceptionStacks = ArrayList<String>()
    private lateinit var taskInfoType: TypeElement
    private lateinit var taskExecutorType: TypeElement
    private lateinit var handlerInfoType: TypeElement
    private lateinit var exceptionHandlerType: TypeElement

    override fun getSupportedAnnotationTypes(): Set<String> {
        return setOf(Task::class.java.name, TaskUncaughtExceptionHandler::class.java.name)
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latestSupported()
    }

    override fun process(
        annotations: Set<TypeElement>,
        roundEnv: RoundEnvironment
    ): Boolean {
        try {
            processImpl(annotations, roundEnv)
        } catch (e: Throwable) {
            // We don't allow exceptions of any kind to propagate to the compiler
            val trace = e.joinToString()
            exceptionStacks.add(trace)
            fatalError(trace)
        }
        return false
    }

    private fun processImpl(
        annotations: Set<TypeElement>,
        roundEnv: RoundEnvironment
    ) {
        return if (roundEnv.processingOver()) {
            generateFiles()
        } else {
            processAnnotations(annotations, roundEnv)
        }
    }

    private fun TypeSpec.Builder.addGenericAnnotations(): TypeSpec.Builder {
        return addAnnotation(
            AnnotationSpec.builder(RestrictTo::class)
                .addMember(
                    "%N = [%T.%M]",
                    "value",
                    RestrictTo.Scope::class,
                    RestrictTo.Scope::class.asTypeName()
                        .member(RestrictTo.Scope.LIBRARY.name)
                )
                .build()
        )
    }

    private fun generateTaskFiles(): List<TypeSpec> {
        val elementUtils = processingEnv.elementUtils
        val superclass = taskInfoType.asClassName()
        val newServices = ArrayList<TypeSpec>(tasks.size)
        for (task in tasks) {
            val packageName = elementUtils.getPackageOf(task.type).qualifiedName
            val name = "${
                packageName?.toString()?.replace('.', '_') ?: ""
            }_${task.type.simpleName}_TaskInfo"
            val type = TypeSpec.classBuilder(
                name
            ).superclass(superclass)
                .addGenericAnnotations()
                .addModifiers(KModifier.PRIVATE)
                .addSuperclassConstructorParameter("%N = %T::class", "type", task.type)
                .addSuperclassConstructorParameter("%N = %S", "name", task.name)
                .addSuperclassConstructorParameter(
                    "%N = %T.%M",
                    "executor",
                    TaskExecutorType::class,
                    TaskExecutorType::class.asTypeName()
                        .member(task.executor.name)
                )
                .addSuperclassConstructorParameter(
                    "%N = %S",
                    "process",
                    if (task.executor == TaskExecutorType.RemoteAsync || task.executor == TaskExecutorType.RemoteAwait) {
                        task.process
                    } else {
                        ""
                    }
                )
                .addSuperclassConstructorParameter(
                    "%N = listOf(${task.dependencies.joinToString { "%T::class" }})",
                    "dependencies",
                    *task.dependencies.toTypedArray()
                )
                .addFunction(
                    FunSpec.builder("newInstance")
                        .addCode("return %T()", task.type)
                        .addModifiers(KModifier.OVERRIDE)
                        .build()
                )
                .build()
            newServices.add(type)
        }
        return newServices
    }

    private fun generateHandlerFiles(): List<TypeSpec> {
        val elementUtils = processingEnv.elementUtils
        val newServices = ArrayList<TypeSpec>(handlers.size)
        val superclass = handlerInfoType.asClassName()
        for (handler in handlers) {
            val packageName = elementUtils.getPackageOf(handler.type).qualifiedName
            val name = "${
                packageName?.toString()?.replace('.', '_') ?: ""
            }_${handler.type.simpleName}_HandlerInfo"
            val type = TypeSpec.classBuilder(name)
                .addGenericAnnotations()
                .addModifiers(KModifier.PRIVATE)
                .superclass(superclass)
                .addSuperclassConstructorParameter(
                    "%N = %T::class",
                    "taskType",
                    handler.taskType
                )
                .addSuperclassConstructorParameter("%N = %L", "priority", handler.priority)
                .addFunction(
                    FunSpec.builder("newInstance")
                        .addCode("return %T()", handler.type)
                        .addModifiers(KModifier.OVERRIDE)
                        .build()
                )
                .build()
            newServices.add(type)
        }
        return newServices
    }

    private fun generateModuleFiles(
        tasks: List<TypeSpec>,
        handlers: List<TypeSpec>
    ): String {
        val filer = processingEnv.filer
        val names = tasks.asSequence()
            .plus(handlers)
            .mapNotNull { it.name }
            .sorted()
            .joinToString()
        val bytes = MessageDigest.getInstance("md5")
            .digest(names.toByteArray())
        val bigInt = BigInteger(1, bytes)
        var md5 = bigInt.toString(16)
        if (md5.length < 32) {
            md5 = "0$md5"
        }
        val md5Name = "ModuleInfo_${md5}"

        val file = FileSpec.builder(ANDROID_MODULES_PACKAGE_NAME, md5Name)
        tasks.forEach {
            file.addType(it)
        }
        handlers.forEach {
            file.addType(it)
        }
        file.addType(
            TypeSpec.classBuilder(md5Name)
                .superclass(ClassName.bestGuess(ANDROID_MODULE_INFO_CLASS_NAME))
                .addAnnotation(Keep::class)
                .addGenericAnnotations()
                .addAnnotation(
                    AnnotationSpec.builder(Deprecated::class.java)
                        .addMember(
                            "%N = %S",
                            "message",
                            "The file generated by the annotation processor is used internally by the framework. Please do not modify it"
                        )
                        .addMember(
                            "%N = %T.%M",
                            "level",
                            DeprecationLevel::class,
                            DeprecationLevel::class.asTypeName()
                                .member(DeprecationLevel.HIDDEN.name)
                        )
                        .build()
                )
                .addSuperclassConstructorParameter(
                    "%N = listOf(${tasks.joinToString { "%T()" }})",
                    "tasks",
                    *tasks.mapNotNull { it.name }
                        .map { ClassName(ANDROID_MODULES_PACKAGE_NAME, it) }
                        .toTypedArray()
                )
                .addSuperclassConstructorParameter(
                    "%N = listOf(${handlers.joinToString { "%T()" }})",
                    "handlers",
                    *handlers.mapNotNull { it.name }
                        .map { ClassName(ANDROID_MODULES_PACKAGE_NAME, it) }
                        .toTypedArray()
                )
                .build()
        )
        file.build().writeTo(filer)
        return "${ANDROID_MODULES_PACKAGE_NAME}.${md5Name}"
    }

    private fun generateServicesFiles(newServices: List<String>) {
        val filer = processingEnv.filer
        val resourceFile = "META-INF/services/$ANDROID_MODULE_INFO_CLASS_NAME"
        try {
            val allServices = TreeSet<String>()
            val oldServices = ArrayList<String>()
            try {
                // would like to be able to print the full path
                // before we attempt to get the resource in case the behavior
                // of filer.getResource does change to match the spec, but there's
                // no good way to resolve CLASS_OUTPUT without first getting a resource.
                val existingFile = filer.getResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    resourceFile
                )
                log("Looking for existing resource file at " + existingFile.toUri())
                oldServices.addAll(
                    existingFile.openInputStream()
                        .use { it.reader().use { reader -> reader.readLines() } }
                )
                log("Existing service entries: $oldServices")
                allServices.addAll(oldServices)
            } catch (e: IOException) {
                // According to the javadoc, Filer.getResource throws an exception
                // if the file doesn't already exist.  In practice this doesn't
                // appear to be the case.  Filer.getResource will happily return a
                // FileObject that refers to a non-existent file but will throw
                // IOException if you try to open an input stream for it.
                log("Resource file did not already exist.")
            }
            if (!allServices.addAll(newServices)) {
                log("No new service entries being added.")
                return
            }
            log("New service file contents: $allServices")
            val fileObject = filer.createResource(StandardLocation.CLASS_OUTPUT, "", resourceFile)
            fileObject.openOutputStream()
                .use {
                    it.writer().use { writer ->
                        allServices.forEach { text ->
                            writer.appendLine(text)
                        }
                    }
                }

            log("Wrote to: " + fileObject.toUri())
        } catch (e: IOException) {
            fatalError("Unable to create $resourceFile, $e")
            return
        }
    }

    private fun generateFiles() {
        val tasks = generateTaskFiles()
        val handlers = generateHandlerFiles()
        val module = generateModuleFiles(tasks, handlers)
        generateServicesFiles(listOf(module))
    }

    private fun processAnnotations(
        annotations: Set<TypeElement>,
        roundEnv: RoundEnvironment
    ) {
        if (!::taskInfoType.isInitialized) {
            taskInfoType = processingEnv
                .elementUtils
                .getTypeElement(ANDROID_TASK_INFO_CLASS_NAME)
        }
        if (!::taskExecutorType.isInitialized) {
            taskExecutorType = processingEnv
                .elementUtils
                .getTypeElement(ANDROID_TASK_EXECUTOR_CLASS_NAME)
        }
        var elements = roundEnv.getElementsAnnotatedWith(
            Task::class.java
        )
        log(annotations.toString())
        log(elements.toString())
        for (element in elements) {
            val typeElement = element.toTypeElement()
            val annotationMirror = typeElement.getAnnotationMirror(Task::class.java)
            val name = annotationMirror
                .getAnnotationStringValue(Task::name.name)
            val process = annotationMirror
                .getAnnotationStringValue(Task::process.name)
            val executorType = annotationMirror
                .getAnnotationEnumValue<TaskExecutorType>(Task::executor.name)
            val dependencies = annotationMirror
                .getAnnotationClassesValue(Task::dependencies.name).asSequence().map {
                    it.toElement().toTypeElement()
                }.sortedBy { it.qualifiedName.toString() }
                .toList()

            val task = TaskInfoElement(
                type = typeElement,
                name = name,
                executor = executorType,
                process = process,
                dependencies = dependencies
            )
            if (checkImplementer(typeElement, taskExecutorType, annotationMirror)) {
                tasks.add(task)
            } else {
                val message = "ServiceProviders must implement their service provider interface. " +
                        typeElement.qualifiedName +
                        " does not implement " +
                        taskExecutorType.qualifiedName
                error(message, element, annotationMirror)
            }
        }
        if (!::handlerInfoType.isInitialized) {
            handlerInfoType = processingEnv
                .elementUtils
                .getTypeElement(ANDROID_HANDLER_INFO_CLASS_NAME)
        }
        if (!::exceptionHandlerType.isInitialized) {
            exceptionHandlerType = processingEnv.elementUtils
                .getTypeElement(ANDROID_UNCAUGHT_EXCEPTION_HANDLER_CLASS_NAME)
        }
        elements = roundEnv.getElementsAnnotatedWith(
            TaskUncaughtExceptionHandler::class.java
        )
        log(elements.toString())
        for (element in elements) {
            val typeElement = element.toTypeElement()
            val annotationMirror = typeElement
                .getAnnotationMirror(TaskUncaughtExceptionHandler::class.java)
            val taskType = annotationMirror.getAnnotationClassesValue(
                TaskUncaughtExceptionHandler::taskType.name
            ).single().toElement().toTypeElement()
            val priority = annotationMirror.getAnnotationIntValue(
                TaskUncaughtExceptionHandler::priority.name
            )
            if (checkImplementer(typeElement, exceptionHandlerType, annotationMirror)) {
                handlers.add(HandlerInfoElement(typeElement, taskType, priority))
            } else {
                val message = "ServiceProviders must implement their service provider interface. " +
                        typeElement.qualifiedName +
                        " does not implement " +
                        exceptionHandlerType.qualifiedName
                error(message, element, annotationMirror)
            }

        }
    }

    private fun checkImplementer(
        providerImplementer: TypeElement,
        providerType: TypeElement,
        annotationMirror: AnnotationMirror
    ): Boolean {
        val verify = processingEnv.options["verify"]
        if (verify == null || !verify.toBoolean()) {
            return true
        }

        // TODO: We're currently only enforcing the subtype relationship
        // constraint. It would be nice to enforce them all.
        val types = processingEnv.typeUtils
        if (types.isSubtype(providerImplementer.asType(), providerType.asType())) {
            return true
        }

        // Maybe the provider has generic type, but the argument to @AutoService can't be generic.
        // So we allow that with a warning, which can be suppressed with @SuppressWarnings("rawtypes").
        // See https://github.com/google/auto/issues/870.
        if (types.isSubtype(providerImplementer.asType(), types.erasure(providerType.asType()))) {
            if (!rawTypesSuppressed(providerImplementer)) {
                warning(
                    "TaskExecutor "
                            + providerType
                            + " is generic, so it can't be named exactly by @${Task::class.qualifiedName}."
                            + " If this is OK, add @SuppressWarnings(\"rawtypes\").",
                    providerImplementer,
                    annotationMirror
                )
            }
            return true
        }
        return false
    }

    private fun rawTypesSuppressed(element: Element): Boolean {
        var e: Element? = element
        while (e != null) {
            val suppress = e.getAnnotation(
                SuppressWarnings::class.java
            )
            if (suppress != null && suppress.value.contains("rawtypes")) {
                return true
            }
            e = e.enclosingElement
        }
        return false
    }

    private fun log(msg: String) {
        if (processingEnv.options.containsKey("debug")) {
            processingEnv.messager.printMessage(Diagnostic.Kind.NOTE, msg)
        }
    }

    private fun warning(msg: String, element: Element, annotation: AnnotationMirror) {
        processingEnv.messager.printMessage(Diagnostic.Kind.WARNING, msg, element, annotation)
    }

    private fun error(msg: String, element: Element, annotation: AnnotationMirror) {
        processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg, element, annotation)
    }

    private fun fatalError(msg: String) {
        processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "FATAL ERROR: $msg")
    }

}