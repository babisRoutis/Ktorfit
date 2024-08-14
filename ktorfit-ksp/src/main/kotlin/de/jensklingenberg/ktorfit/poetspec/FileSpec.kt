package de.jensklingenberg.ktorfit.poetspec

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import de.jensklingenberg.ktorfit.KtorfitOptions
import de.jensklingenberg.ktorfit.model.ClassData
import de.jensklingenberg.ktorfit.model.KtorfitError.Companion.PROPERTIES_NOT_SUPPORTED
import de.jensklingenberg.ktorfit.model.converterHelper
import de.jensklingenberg.ktorfit.model.internalApi
import de.jensklingenberg.ktorfit.model.ktorfitClass
import de.jensklingenberg.ktorfit.model.providerClass
import de.jensklingenberg.ktorfit.model.toClassName
import de.jensklingenberg.ktorfit.utils.addImports

/**
 * Transform a [ClassData] to a [FileSpec] for KotlinPoet
 */
fun ClassData.getImplClassSpec(
    resolver: Resolver,
    ktorfitOptions: KtorfitOptions,
): FileSpec {
    val classData = this
    val optinAnnotation =
        AnnotationSpec
            .builder(ClassName("kotlin", "OptIn"))
            .addMember(
                "%T::class",
                internalApi,
            ).build()

    val suppressAnnotation =
        AnnotationSpec
            .builder(ClassName("kotlin", "Suppress"))
            .addMember("\"warnings\"")
            .build()

    val createExtensionFunctionSpec = getCreateExtensionFunctionSpec(classData)

    val implClassProperties =
        classData.properties.map { property ->
            propertySpec(property)
        }

    val helperProperty =
        PropertySpec
            .builder(converterHelper.objectName, converterHelper.toClassName())
            .initializer("${converterHelper.name}(${ktorfitClass.objectName})")
            .addModifiers(KModifier.PRIVATE)
            .build()

    val providerClass =
        TypeSpec
            .classBuilder("_${classData.name}Provider")
            .addModifiers(classData.modifiers)
            .addSuperinterface(
                providerClass.toClassName().parameterizedBy(ClassName(classData.packageName, classData.name)),
            ).addFunction(
                FunSpec
                    .builder("create")
                    .returns(ClassName(classData.packageName, classData.name))
                    .addStatement("return _${classData.name}Impl(${ktorfitClass.objectName})")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter(ktorfitClass.objectName, ktorfitClass.toClassName())
                    .build(),
            ).build()

    val implClassName = "_${classData.name}Impl"

    val implClassSpec =
        TypeSpec
            .classBuilder(implClassName)
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter(ktorfitClass.objectName, ktorfitClass.toClassName())
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder(ktorfitClass.objectName, ktorfitClass.toClassName())
                    .initializer(ktorfitClass.objectName)
                    .addModifiers(KModifier.PRIVATE)
                    .build(),
            ).addAnnotation(optinAnnotation)
            .addModifiers(classData.modifiers)
            .addSuperinterface(ClassName(classData.packageName, classData.name))
            .addKtorfitSuperInterface(classData.superClasses)
            .addProperties(listOf(helperProperty) + implClassProperties)
            .addFunctions(
                classData.functions.map {
                    it.toFunSpec(
                        resolver,
                        ktorfitOptions.setQualifiedType,
                        ksFile.filePath
                    )
                }
            ).build()

    return FileSpec
        .builder(classData.packageName, implClassName)
        .addAnnotation(suppressAnnotation)
        .addFileComment("Generated by Ktorfit")
        .addImports(classData.imports)
        .addType(implClassSpec)
        .addType(providerClass)
        .addFunction(createExtensionFunctionSpec)
        .build()
}

private fun propertySpec(property: KSPropertyDeclaration): PropertySpec {
    val propBuilder =
        PropertySpec
            .builder(
                property.simpleName.asString(),
                property.type.toTypeName(),
            ).addModifiers(KModifier.OVERRIDE)
            .mutable(property.isMutable)
            .getter(
                FunSpec
                    .getterBuilder()
                    .addStatement(PROPERTIES_NOT_SUPPORTED)
                    .build(),
            )

    if (property.isMutable) {
        propBuilder.setter(
            FunSpec
                .setterBuilder()
                .addParameter("value", property.type.toTypeName())
                .build(),
        )
    }

    return propBuilder.build()
}

/**
 * Support for extending multiple interfaces, is done with Kotlin delegation. Ktorfit interfaces can only extend other Ktorfit interfaces, so there will
 * be a generated implementation for each interface that we can use.
 */
private fun TypeSpec.Builder.addKtorfitSuperInterface(superClasses: List<KSTypeReference>): TypeSpec.Builder {
    (superClasses).forEach { superClassReference ->
        val superClassDeclaration = superClassReference.resolve().declaration
        val superTypeClassName = superClassDeclaration.simpleName.asString()
        val superTypePackage = superClassDeclaration.packageName.asString()
        this.addSuperinterface(
            ClassName(superTypePackage, superTypeClassName),
            CodeBlock.of(
                "%L._%LImpl(${ktorfitClass.objectName})",
                superTypePackage,
                superTypeClassName,
            ),
        )
    }

    return this
}

/**
 * public fun Ktorfit.createExampleApi(): ExampleApi = this.create(_ExampleApiImpl()
 */
private fun getCreateExtensionFunctionSpec(classData: ClassData): FunSpec {
    val functionName = "create${classData.name}"
    return FunSpec
        .builder(functionName)
        .addModifiers(classData.modifiers)
        .addStatement("return _${classData.name}Impl(this)")
        .receiver(ktorfitClass.toClassName())
        .returns(ClassName(classData.packageName, classData.name))
        .build()
}