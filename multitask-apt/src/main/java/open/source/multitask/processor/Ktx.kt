package open.source.multitask.processor

import java.io.PrintWriter
import java.io.StringWriter
import java.util.*
import java.util.stream.Collectors
import javax.lang.model.element.*
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.ErrorType
import javax.lang.model.type.TypeMirror
import javax.lang.model.type.TypeVariable
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.SimpleAnnotationValueVisitor8
import javax.lang.model.util.SimpleElementVisitor8
import javax.lang.model.util.SimpleTypeVisitor8
import kotlin.collections.HashMap
import kotlin.collections.HashSet

internal abstract class DefaultVisitor<T>(private val clazz: Class<T>) :
    SimpleAnnotationValueVisitor8<T, Void?>() {
    override fun defaultAction(o: Any, unused: Void?): T {
        throw IllegalArgumentException(
            "Expected a " + clazz.simpleName + ", got instead: " + o
        )
    }
}

internal object EnumVisitor : DefaultVisitor<VariableElement>(VariableElement::class.java) {
    override fun visitEnumConstant(value: VariableElement, unused: Void?): VariableElement {
        return value
    }
}

private abstract class CastingElementVisitor<T>(private val label: String) :
    SimpleElementVisitor8<T, Void?>() {
    override fun defaultAction(e: Element, ignore: Void?): T {
        throw IllegalArgumentException("$e does not represent a $label")
    }
}

private object TypeElementVisitor : CastingElementVisitor<TypeElement?>("type element") {
    override fun visitType(e: TypeElement?, p1: Void?): TypeElement? {
        return e
    }
}


private abstract class CastingTypeVisitor<T>(private val label: String) :
    SimpleTypeVisitor8<T, Void?>() {
    override fun defaultAction(e: TypeMirror, v: Void?): T {
        throw java.lang.IllegalArgumentException("$e does not represent a $label")
    }
}

private object DeclaredTypeVisitor : CastingTypeVisitor<DeclaredType?>("declared type") {
    override fun visitDeclared(type: DeclaredType, ignore: Void?): DeclaredType {
        return type
    }
}

private object AsElementVisitor : SimpleTypeVisitor8<Element, Void?>() {
    override fun defaultAction(e: TypeMirror, p: Void?): Element {
        throw IllegalArgumentException("$e cannot be converted to an Element")
    }

    override fun visitDeclared(t: DeclaredType, p: Void?): Element {
        return t.asElement()
    }

    override fun visitError(t: ErrorType, p: Void?): Element {
        return t.asElement()
    }

    override fun visitTypeVariable(t: TypeVariable, p: Void?): Element {
        return t.asElement()
    }
}

fun Element.toTypeElement(): TypeElement {
    return this.accept(TypeElementVisitor, null)!!
}

fun TypeMirror.toDeclaredType(): DeclaredType {
    return accept(DeclaredTypeVisitor, null)!!
}

fun Element.getAnnotationMirror(
    annotationName: String
): AnnotationMirror {
    for (annotationMirror in this.annotationMirrors) {
        val annotationTypeElement = annotationMirror.annotationType.asElement().toTypeElement()
        if (annotationTypeElement.qualifiedName.contentEquals(annotationName)) {
            return annotationMirror
        }
    }
    throw NullPointerException()
}

fun Element.getAnnotationMirror(
    annotationClass: Class<out Annotation>
): AnnotationMirror {
    return getAnnotationMirror(annotationClass.canonicalName)
}

fun AnnotationMirror.getAnnotationValuesWithDefaults(): Map<ExecutableElement, AnnotationValue> {
    val values = HashMap<ExecutableElement, AnnotationValue>()
    // Use unmodifiableMap to eliminate wildcards, which cause issues for our nullness checker.
    val declaredValues = Collections.unmodifiableMap(this.elementValues)
    for (method in ElementFilter.methodsIn(this.annotationType.asElement().enclosedElements)) {
        // Must iterate and put in this order, to ensure consistency in generated code.
        if (declaredValues.containsKey(method)) {
            values[method] = declaredValues.getValue(method)
        } else if (method.defaultValue != null) {
            values[method] = method.defaultValue
        } else {
            throw IllegalStateException(
                "Unset annotation value without default should never happen: "
                        + method.enclosingElement.toTypeElement().qualifiedName
                        + '.'
                        + method.simpleName
                        + "()"
            )
        }
    }
    return values
}

fun AnnotationMirror.getAnnotationElementAndValue(elementName: String): Map.Entry<ExecutableElement, AnnotationValue> {
    for (entry in getAnnotationValuesWithDefaults().entries) {
        if (entry.key.simpleName.contentEquals(elementName)) {
            return entry
        }
    }
    throw IllegalArgumentException(
        String.format(
            "@%s does not define an element %s()",
            this.annotationType.asElement().toTypeElement().qualifiedName,
            elementName
        )
    )
}

fun AnnotationMirror.getAnnotationValue(
    elementName: String
): AnnotationValue {
    return getAnnotationElementAndValue(elementName).value
}

fun AnnotationMirror.getAnnotationStringValue(
    elementName: String
): String {
    return getAnnotationValue(elementName).valueOfType(String::class.java)
}

fun AnnotationMirror.getAnnotationIntValue(
    elementName: String
): Int {
    return getAnnotationValue(elementName).value as Int
}

internal inline fun <reified T : Enum<T>> AnnotationMirror.getAnnotationEnumValue(elementName: String): T {
    val annotation = getAnnotationValue(elementName)
    @Suppress("UNCHECKED_CAST")
    return java.lang.Enum.valueOf(
        T::class.java,
        EnumVisitor.visit(annotation).simpleName.toString()
    )
}

fun AnnotationMirror.getAnnotationClassesValue(elementName: String): Set<DeclaredType> {
    return getAnnotationValue(elementName)
        .accept(
            object : SimpleAnnotationValueVisitor8<Set<DeclaredType>, Void?>(HashSet()) {
                override fun visitType(typeMirror: TypeMirror, v: Void?): Set<DeclaredType> {
                    return setOf(typeMirror.toDeclaredType())
                }

                override fun visitArray(
                    values: MutableList<out AnnotationValue>,
                    v: Void?
                ): Set<DeclaredType> {
                    return values.stream().flatMap {
                        it.accept(this, null).stream()
                    }.collect(Collectors.toSet())
                }
            },
            null
        )
}

fun TypeMirror.toElement(): Element {
    return this.accept(AsElementVisitor, null)
}

private fun <T> AnnotationValue.valueOfType(type: Class<T>): T {
    val value = value
    require(type.isInstance(value)) { "Expected " + type.simpleName + ", got instead: " + value }
    return type.cast(value)
}

fun Throwable.joinToString(): String {
    val stringWriter = StringWriter()
    printStackTrace(PrintWriter(stringWriter))
    return stringWriter.toString()
}