package open.source.multitask.processor

import androidx.annotation.Keep
import androidx.annotation.RestrictTo
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import open.source.multitask.annotations.Task
import open.source.multitask.annotations.TaskExecutorType
import java.io.IOException
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
import kotlin.reflect.KClass

@AutoService(value = [Processor::class])
class TaskInfoProcessor : AbstractProcessor() {

    companion object {
        private const val ANDROID_TASK_INFO_CLASS_NAME = "open.source.multitask.TaskInfo"
        private const val ANDROID_TASK_EXECUTOR_CLASS_NAME = "open.source.multitask.TaskExecutor"
    }

    private val tasks = ArrayList<TaskInfo>()
    private val exceptionStacks = ArrayList<String>()
    private lateinit var taskInfoType: TypeElement

    override fun getSupportedAnnotationTypes(): Set<String> {
        return setOf(Task::class.java.name)
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
            generateTaskInfoFiles()
        } else {
            processAnnotations(annotations, roundEnv)
        }
    }

    private fun generateTaskInfoFiles() {
        val filer = processingEnv.filer
        val elementUtils = processingEnv.elementUtils
        val typeName = with(ParameterizedTypeName) {
            KClass::class.asTypeName()
                .parameterizedBy(
                    WildcardTypeName.producerOf(
                        ClassName.bestGuess(
                            ANDROID_TASK_EXECUTOR_CLASS_NAME
                        )
                    )
                )
        }
        val newServices = ArrayList<String>()
        for (task in tasks) {
            val packageName = elementUtils.getPackageOf(task.type).qualifiedName
            val name = "${task.type.simpleName}_TaskInfo_Hash${task.name.hashCode()}"
            if (packageName.isNullOrEmpty()) {
                newServices.add(name)
            } else {
                newServices.add("${packageName}.${name}")
            }
            FileSpec.get(
                packageName.toString(),
                TypeSpec.classBuilder(
                    name
                ).superclass(taskInfoType.asClassName())
                    .addAnnotation(Keep::class)
                    .addAnnotation(
                        AnnotationSpec.builder(RestrictTo::class)
                            .addMember("value = [${RestrictTo.Scope::class.qualifiedName}.${RestrictTo.Scope.LIBRARY}]")
                            .build()
                    )
                    .addAnnotation(
                        AnnotationSpec.builder(Deprecated::class.java)
                            .addMember("message = \"framework internal use!\"")
                            .addMember("level = ${DeprecationLevel::class.qualifiedName}.${DeprecationLevel.HIDDEN}")
                            .build()
                    )
                    .addSuperclassConstructorParameter(
                        "name = \"${task.name}\", " +
                                "executor = ${task.executor.javaClass.name + '.' + task.executor.name}, " +
                                "isAwait = ${task.isAwait}, " +
                                "process = \"${if (task.executor == TaskExecutorType.Remote) task.process else ""}\", " +
                                "dependencies = setOf(${
                                    task.dependencies.joinToString { "${it.qualifiedName}::class" }
                                })"
                    )
                    .addFunction(
                        FunSpec.builder("newInstance")
                            .addCode("return ${task.type.qualifiedName}()")
                            .addModifiers(KModifier.OVERRIDE)
                            .build()
                    )
                    .addProperty(
                        PropertySpec.builder("type", typeName, KModifier.OVERRIDE)
                            .getter(
                                FunSpec.getterBuilder()
                                    .addCode("return ${task.type.qualifiedName}::class")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            ).writeTo(filer)
        }
        val resourceFile = "META-INF/services/$ANDROID_TASK_INFO_CLASS_NAME"
        try {
            val allServices = TreeSet<String>()
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
                val oldServices = existingFile.openInputStream()
                    .use { it.reader().use { it.readLines() } }
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

    private fun processAnnotations(
        annotations: Set<TypeElement>,
        roundEnv: RoundEnvironment
    ) {
        if (!::taskInfoType.isInitialized) {
            taskInfoType = processingEnv.elementUtils.getTypeElement(ANDROID_TASK_INFO_CLASS_NAME)
        }
        val elements = roundEnv.getElementsAnnotatedWith(
            Task::class.java
        )
        log(annotations.toString())
        log(elements.toString())
        for (element in elements) {
            val typeElement = element.toTypeElement()
            typeElement.superclass.toElement()
            val annotationMirror = typeElement.getAnnotationMirror(Task::class.java)
            val name = annotationMirror
                .getAnnotationStringValue(Task::name.name)
            val isAwait = annotationMirror
                .getAnnotationValue(Task::isAwait.name)
                .value == true
            val process = annotationMirror
                .getAnnotationStringValue(Task::process.name)
            val executorType = annotationMirror
                .getAnnotationEnumValue<TaskExecutorType>(Task::executor.name)
            val dependencies = annotationMirror
                .getAnnotationClassesValue(Task::dependencies.name).map {
                    it.toElement().toTypeElement()
                }

            val task = TaskInfo(
                type = typeElement,
                name = name,
                executor = executorType,
                isAwait = isAwait,
                process = process,
                dependencies = dependencies
            )
            if (checkImplementer(typeElement, taskInfoType, annotationMirror)) {
                tasks.add(task)
            } else {
                val message = "ServiceProviders must implement their service provider interface. " +
                        typeElement.qualifiedName +
                        " does not implement " +
                        taskInfoType.qualifiedName
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
        if (verify == null || !java.lang.Boolean.parseBoolean(verify)) {
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