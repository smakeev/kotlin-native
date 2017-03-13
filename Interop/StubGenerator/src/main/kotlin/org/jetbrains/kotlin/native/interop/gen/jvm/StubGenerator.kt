package org.jetbrains.kotlin.native.interop.gen.jvm

import org.jetbrains.kotlin.native.interop.indexer.*
import java.lang.IllegalStateException

enum class KotlinPlatform {
    JVM,
    NATIVE
}

// TODO: mostly rename 'jni' to 'c'.

class StubGenerator(
        val nativeIndex: NativeIndex,
        val configuration: InteropConfiguration,
        val libName: String,
        val dumpShims: Boolean,
        val platform: KotlinPlatform = KotlinPlatform.JVM) {

    val pkgName: String
        get() = configuration.pkgName

    val excludedFunctions: Set<String>
        get() = configuration.excludedFunctions

    val keywords = setOf("object", "val") // TODO

    fun String.mangleIfKeyword(): String {
        return if (this in keywords) {
            "_" + this
        } else {
            this
        }
    }

    /**
     * The names that should not be used for struct classes to prevent name clashes
     */
    val forbiddenStructNames = run {
        val functionNames = nativeIndex.functions.map { it.name }
        val fieldNames = nativeIndex.structs.mapNotNull { it.def }.flatMap { it.fields }.map { it.name }
        (functionNames + fieldNames).toSet() + keywords
    }

    val StructDecl.isAnonymous: Boolean
        get() = spelling.contains("(anonymous ") // TODO: it is a hack

    val anonymousStructKotlinNames = mutableMapOf<StructDecl, String>()

    /**
     * The name to be used for this struct in Kotlin
     */
    val StructDecl.kotlinName: String
        get() {
            if (this.isAnonymous) {
                val names = anonymousStructKotlinNames
                return names.getOrPut(this) {
                    "anonymousStruct${names.size + 1}"
                }
            }

            val strippedCName = if (spelling.startsWith("struct ") || spelling.startsWith("union ")) {
                spelling.substringAfter(' ')
            } else {
                spelling
            }

            return if (strippedCName !in forbiddenStructNames) strippedCName else (strippedCName + "Struct")
        }

    val EnumDef.isAnonymous: Boolean
        get() = spelling.contains("(anonymous ") // TODO: it is a hack

    /**
     * Indicates whether this enum should be represented as Kotlin enum.
     */
    val EnumDef.isStrictEnum: Boolean
            // TODO: if an anonymous enum defines e.g. a function return value or struct field type,
            // then it probably should be represented as Kotlin enum
        get() {
            if (this.isAnonymous) {
                return false
            }

            val name = this.kotlinName

            if (name in configuration.strictEnums) {
                return true
            }

            if (name in configuration.nonStrictEnums) {
                return false
            }

            // Let the simple heuristic decide:
            return !this.constants.any { it.isExplicitlyDefined }
        }

    /**
     * The name to be used for this enum in Kotlin
     */
    val EnumDef.kotlinName: String
        get() = if (spelling.startsWith("enum ")) {
            spelling.substringAfter(' ')
        } else {
            assert (!isAnonymous)
            spelling
        }

    val RecordType.kotlinName: String
        get() = decl.kotlinName


    val functionsToBind = nativeIndex.functions.filter { it.name !in excludedFunctions }

    private val usedFunctionTypes = mutableMapOf<FunctionType, String>()

    val FunctionType.kotlinName: String
        get() {
            return usedFunctionTypes.getOrPut(this, {
                "CFunctionType" + (usedFunctionTypes.size + 1)
            })
        }

    private val macroConstantsByName = nativeIndex.macroConstants.associateBy { it.name }

    /**
     * The output currently used by the generator.
     * Should append line separator after any usage.
     */
    private var out: (String) -> Unit = {
        throw IllegalStateException()
    }

    fun <R> withOutput(output: (String) -> Unit, action: () -> R): R {
        val oldOut = out
        out = output
        try {
            return action()
        } finally {
            out = oldOut
        }
    }

    private fun <R> transaction(action: () -> R): R {
        val lines = mutableListOf<String>()
        val res = withOutput({ lines.add(it) }, action)
        // if action is completed successfully:
        lines.forEach(out)
        return res
    }

    private fun <R> indent(action: () -> R): R {
        val oldOut = out
        return withOutput({ oldOut("    $it") }, action)
    }

    private fun <R> block(header: String, body: () -> R): R {
        out("$header {")
        val res = indent {
            body()
        }
        out("}")
        return res
    }

    /**
     * Returns the expression which could be used for this type in C code.
     *
     * TODO: use libclang to implement:
     */
    fun Type.getStringRepresentation(): String {
        return when (this) {

            is VoidType -> "void"
            is Int8Type -> "char"
            is UInt8Type -> "unsigned char"
            is Int16Type -> "short"
            is UInt16Type -> "unsigned short"
            is Int32Type -> "int"
            is UInt32Type -> "unsigned int"
            is IntPtrType -> "intptr_t"
            is UIntPtrType -> "uintptr_t"
            is Int64Type -> "int64_t"
            is UInt64Type -> "uint64_t"
            is Float32Type -> "float"
            is Float64Type -> "double"

            is PointerType -> {
                val pointeeType = this.pointeeType
                if (pointeeType is FunctionType) {
                    "void*" // TODO
                } else {
                    pointeeType.getStringRepresentation() + "*"
                }
            }

            is RecordType -> this.decl.spelling

            is ArrayType -> "void*" // TODO

            is EnumType -> if (this.def.isAnonymous) {
                this.def.baseType.getStringRepresentation()
            } else {
                this.def.spelling
            }

            is Typedef -> this.def.name

            else -> throw kotlin.NotImplementedError()
        }
    }

    val PrimitiveType.kotlinType: String
        get() = when (this) {

            is Int8Type, is UInt8Type -> "Byte"
            is Int16Type, is UInt16Type -> "Short"
            is Int32Type, is UInt32Type -> "Int"
            is IntPtrType, is UIntPtrType, // TODO: 64-bit specific
            is Int64Type, is UInt64Type -> "Long"
            is Float32Type -> "Float"
            is Float64Type -> "Double"

            else -> throw NotImplementedError()
        }

    /**
     * Describes the Kotlin types used to represent some C type.
     */
    private sealed class TypeMirror(val pointedTypeName: String, val info: TypeInfo) {
        /**
         * Type to be used in bindings for argument or return value.
         */
        abstract val argType: String

        /**
         * Mirror for C type to be represented in Kotlin as by-value type.
         */
        class ByValue(pointedTypeName: String, info: TypeInfo, val valueTypeName: String) :
                TypeMirror(pointedTypeName, info) {

            override val argType: String
                get() = valueTypeName + if (info is TypeInfo.Pointer) "?" else ""
        }

        /**
         * Mirror for C type to be represented in Kotlin as by-ref type.
         */
        class ByRef(pointedTypeName: String, info: TypeInfo) : TypeMirror(pointedTypeName, info) {
            override val argType: String
                get() = pointedTypeName
        }
    }

    /**
     * Describes various type conversions for [TypeMirror].
     */
    private sealed class TypeInfo {
        /**
         * The conversion from [TypeMirror.argType] to [jniType].
         */
        abstract fun argToJni(name: String): String

        /**
         * The conversion from [jniType] to [TypeMirror.argType].
         */
        abstract fun argFromJni(name: String): String

        /**
         * The type to be used for passing [TypeMirror.argType] through JNI.
         */
        abstract val jniType: String

        /**
         * If this info is for [TypeMirror.ByValue], then this method describes how to
         * construct pointed-type from value type.
         */
        abstract fun constructPointedType(valueType: String): String

        class Primitive(override val jniType: String, val varTypeName: String) : TypeInfo() {

            override fun argToJni(name: String) = name
            override fun argFromJni(name: String) = name

            override fun constructPointedType(valueType: String) = "${varTypeName}WithValueMappedTo<$valueType>"
        }

        class Enum(val className: String, val baseType: String) : TypeInfo() {
            override fun argToJni(name: String) = "$name.value"

            override fun argFromJni(name: String) = "$className.byValue($name)"

            override val jniType: String
                get() = baseType

            override fun constructPointedType(valueType: String) = "$className.Var" // TODO: improve

        }

        class Pointer(val pointee: String) : TypeInfo() {
            override fun argToJni(name: String) = "$name.rawValue"

            override fun argFromJni(name: String) = "interpretCPointer<$pointee>($name)"

            override val jniType: String
                get() = "NativePtr"

            override fun constructPointedType(valueType: String) = "CPointerVarWithValueMappedTo<$valueType>"
        }

        class ByRef(val pointed: String) : TypeInfo() {
            override fun argToJni(name: String) = "$name.rawPtr"

            override fun argFromJni(name: String) = "interpretPointed<$pointed>($name)"

            override val jniType: String
                get() = "NativePtr"

            override fun constructPointedType(valueType: String): String {
                // TODO: this method must not exist
                throw UnsupportedOperationException()
            }
        }
    }

    private fun mirror(type: PrimitiveType): TypeMirror.ByValue {
        val varTypeName = when (type) {
            is Int8Type, is UInt8Type -> "CInt8Var"
            is Int16Type, is UInt16Type -> "CInt16Var"
            is Int32Type, is UInt32Type -> "CInt32Var"
            is IntPtrType, is UIntPtrType, // TODO: 64-bit specific
            is Int64Type, is UInt64Type -> "CInt64Var"
            is Float32Type -> "CFloat32Var"
            is Float64Type -> "CFloat64Var"
            else -> TODO(type.toString())
        }

        val info = TypeInfo.Primitive(type.kotlinType, varTypeName)
        return TypeMirror.ByValue(varTypeName, info, type.kotlinType)
    }

    private fun byRefTypeMirror(pointedTypeName: String) : TypeMirror.ByRef {
        val info = TypeInfo.ByRef(pointedTypeName)
        return TypeMirror.ByRef(pointedTypeName, info)
    }

    private fun mirror(type: Type): TypeMirror = when (type) {
        is PrimitiveType -> mirror(type)

        is RecordType -> byRefTypeMirror(type.kotlinName)

        is EnumType -> when {
            type.def.isStrictEnum -> {
                val className = type.def.kotlinName
                val info = TypeInfo.Enum(className, type.def.baseType.kotlinType)
                TypeMirror.ByValue("$className.Var", info, className)
            }
            !type.def.isAnonymous -> {
                val baseTypeMirror = mirror(type.def.baseType)
                val name = type.def.kotlinName
                TypeMirror.ByValue("${name}Var", baseTypeMirror.info, name)
            }
            else -> mirror(type.def.baseType)
        }

        is PointerType -> {
            val pointeeType = type.pointeeType
            if (pointeeType.unwrapTypedefs() is VoidType) {
                val info = TypeInfo.Pointer("COpaque")
                TypeMirror.ByValue("COpaquePointerVar", info, "COpaquePointer")
            } else {
                val pointeeMirror = mirror(pointeeType)
                val info = TypeInfo.Pointer(pointeeMirror.pointedTypeName)
                TypeMirror.ByValue("CPointerVar<${pointeeMirror.pointedTypeName}>", info,
                        "CPointer<${pointeeMirror.pointedTypeName}>")
            }
        }

        is ArrayType -> {
            val elemMirror = mirror(type.elemType)
            byRefTypeMirror("CArray<${elemMirror.pointedTypeName}>")
        }

        is FunctionType -> byRefTypeMirror("CFunction<${type.kotlinName}>")

        is Typedef -> {
            val baseType = mirror(type.def.aliased)
            val name = type.def.name
            when (baseType) {
                is TypeMirror.ByValue -> TypeMirror.ByValue("${name}Var", baseType.info, name)
                is TypeMirror.ByRef -> TypeMirror.ByRef(name, baseType.info)
            }

        }

        else -> TODO(type.toString())
    }

    /**
     * Describes how to represent in Kotlin the value to be passed to native code.
     *
     * @param kotlinType the name of Kotlin type to be used for this value in Kotlin.
     * @param kotlinConv the function such that `kotlinConv(name)` converts variable `name` to pass it into JNI stub
     * @param memScoped the conversion allocates memory and should be placed into `memScoped {}` block
     * @param kotlinJniBridgeType the name of Kotlin type to be used for this value in JNI stub
     */
    class OutValueBinding(val kotlinType: String,
                          val kotlinConv: ((String) -> String)? = null,
                          val memScoped: Boolean = false,
                          val kotlinJniBridgeType: String = kotlinType)

    /**
     * Describes how to represent in Kotlin the value to be received from native code.
     *
     * @param kotlinJniBridgeType the name of Kotlin type to be used for this value in JNI stub
     * @param conv the function such that `conv(name)` converts variable `name` received from JNI stub to `kotlinType`.
     * @param kotlinType the name of Kotlin type to be used for this value in Kotlin.
     */
    class InValueBinding(val kotlinJniBridgeType: String,
                         val conv: ((String) -> String) = { it },
                         val kotlinType: String = kotlinJniBridgeType)

    /**
     * Constructs [OutValueBinding] for the value of given C type.
     */
    fun getOutValueBinding(type: Type): OutValueBinding {
        if (type.unwrapTypedefs() is ArrayType) {
            // array-typed C function argument or return value is actually a pointer to array.
            return getOutValueBinding(PointerType(type))
        }

        val mirror = mirror(type)

        return OutValueBinding(
                kotlinType = mirror.argType,
                kotlinConv = mirror.info::argToJni,
                kotlinJniBridgeType = mirror.info.jniType
        )
    }

    fun getCFunctionParamBinding(type: Type): OutValueBinding {
        if (treatCFunctionParameterAsString(type)) {
            return OutValueBinding(
                    kotlinType = "String?",
                    kotlinConv = { name -> "$name?.toCString(memScope).rawPtr" },
                    memScoped = true,
                    kotlinJniBridgeType = "NativePtr"
            )
        }

        return getOutValueBinding(type)
    }

    private fun treatCFunctionParameterAsString(type: Type) = type is PointerType && type.pointeeType is Int8Type

    fun getCallbackRetValBinding(type: Type): OutValueBinding {
        if (type.unwrapTypedefs() is VoidType) {
            return OutValueBinding(
                    kotlinType = "Unit",
                    kotlinConv = { throw UnsupportedOperationException() },
                    kotlinJniBridgeType = "void"
            )
        }

        return getOutValueBinding(type)
    }

    /**
     * Constructs [InValueBinding] for the value of given C type.
     */
    fun getInValueBinding(type: Type): InValueBinding  {
        if (type.unwrapTypedefs() is ArrayType) {
            // array-typed C function argument or return value is actually a pointer to array.
            return getInValueBinding(PointerType(type))
        }

        val mirror = mirror(type)

        return InValueBinding(
                kotlinJniBridgeType = mirror.info.jniType,
                conv = mirror.info::argFromJni,
                kotlinType = mirror.argType
        )
    }

    fun getCFunctionRetValBinding(type: Type): InValueBinding {
        when (type.unwrapTypedefs()) {
            is VoidType -> return InValueBinding("Unit")
        }

        return getInValueBinding(type)
    }

    fun getCallbackParamBinding(type: Type): InValueBinding {
        return getInValueBinding(type)
    }

    private fun getKotlinFunctionType(type: FunctionType): String {
        return "(" +
                type.parameterTypes.map { getCallbackParamBinding(it).kotlinType }.joinToString(", ") +
                ") -> " +
                getCallbackRetValBinding(type.returnType).kotlinType
    }

    /**
     * Produces to [out] the definition of Kotlin class representing the reference to given struct.
     */
    private fun generateStruct(decl: StructDecl) {
        val def = decl.def
        if (def == null) {
            generateForwardStruct(decl)
            return
        }

        val className = decl.kotlinName
        block("class $className(override val rawPtr: NativePtr) : CStructVar()") {
            out("")
            out("companion object : Type(${def.size}, ${def.align})") // FIXME: align
            out("")
            def.fields.forEach { field ->
                try {
                    if (field.offset < 0) throw NotImplementedError();
                    assert(field.offset % 8 == 0L)
                    val offset = field.offset / 8
                    val fieldRefType = mirror(field.type)
                    out("val ${field.name}: ${fieldRefType.pointedTypeName}")
                    out("    get() = memberAt($offset)")
                    out("")
                } catch (e: Throwable) {
                    println("Warning: cannot generate definition for field $className.${field.name}")
                }
            }
        }
    }

    /**
     * Produces to [out] the definition of Kotlin class representing the reference to given forward (incomplete) struct.
     */
    private fun generateForwardStruct(s: StructDecl) {
        val className = s.kotlinName
        out("class $className(override val rawPtr: NativePtr) : COpaque")
    }

    /**
     * Produces to [out] the Kotlin definitions for given enum.
     */
    private fun generateEnum(e: EnumDef) {
        if (!e.isStrictEnum) {
            generateEnumAsConstants(e)
            return
        }

        val baseTypeMirror = mirror(e.baseType)

        block("enum class ${e.kotlinName}(val value: ${e.baseType.kotlinType})") {
            e.constants.forEach {
                out("${it.name}(${it.value}),")
            }
            out(";")
            out("")
            block("companion object") {
                out("fun byValue(value: ${e.baseType.kotlinType}) = ${e.kotlinName}.values().find { it.value == value }!!")
            }
            out("")
            block("class Var(override val rawPtr: NativePtr) : CEnumVar()") {
                out("companion object : Type(${baseTypeMirror.pointedTypeName}.size.toInt())")
                out("var value: ${e.kotlinName}")
                out("    get() = byValue(this.reinterpret<${baseTypeMirror.pointedTypeName}>().value)")
                out("    set(value) { this.reinterpret<${baseTypeMirror.pointedTypeName}>().value = value.value }")
            }
        }
    }

    /**
     * Produces to [out] the Kotlin definitions for given enum which shouldn't be represented as Kotlin enum.
     *
     * @see isStrictEnum
     */
    private fun generateEnumAsConstants(e: EnumDef) {
        // TODO: if this enum defines e.g. a type of struct field, then it should be generated inside the struct class
        // to prevent name clashing

        val constants = e.constants.filter {
            // Macro "overrides" the original enum constant.
            it.name !in macroConstantsByName
        }

        val typeName: String

        if (e.isAnonymous) {
            if (constants.isNotEmpty()) {
                out("// ${e.spelling}:")
            }

            typeName = e.baseType.kotlinType
        } else {
            val typeMirror = mirror(EnumType(e))
            if (typeMirror !is TypeMirror.ByValue) {
                error("unexpected enum type mirror: $typeMirror")
            }

            // Generate as typedef:
            val varTypeName = typeMirror.info.constructPointedType(typeMirror.valueTypeName)
            out("typealias ${typeMirror.pointedTypeName} = $varTypeName")
            out("typealias ${typeMirror.valueTypeName} = ${e.baseType.kotlinType}")

            if (constants.isNotEmpty()) {
                out("")
            }

            typeName = typeMirror.valueTypeName
        }

        for (constant in constants) {
            out("val ${constant.name}: $typeName = ${constant.value}")
        }
    }

    private fun generateTypedef(def: TypedefDef) {
        val mirror = mirror(Typedef(def))
        val baseMirror = mirror(def.aliased)

        when (baseMirror) {
            is TypeMirror.ByValue -> {
                val name = def.name
                val varTypeName = mirror.info.constructPointedType(name)
                out("typealias ${mirror.pointedTypeName} = $varTypeName")
                out("typealias ${(mirror as TypeMirror.ByValue).valueTypeName} = ${baseMirror.valueTypeName}")
            }
            is TypeMirror.ByRef -> {
                out("typealias ${mirror.pointedTypeName} = ${baseMirror.pointedTypeName}")
            }
        }
    }

    /**
     * Constructs [InValueBinding] for return value of Kotlin binding for given C function.
     */
    private fun retValBinding(func: FunctionDecl) = getCFunctionRetValBinding(func.returnType)

    /**
     * Constructs [OutValueBinding]s for parameters of Kotlin binding for given C function.
     */
    private fun paramBindings(func: FunctionDecl): Array<OutValueBinding> {
        val paramBindings = func.parameters.map { param ->
            getCFunctionParamBinding(param.type)
        }.toMutableList()

        val retValType = func.returnType
        if (retValType.unwrapTypedefs() is RecordType) {
            val retValMirror = mirror(retValType)

            paramBindings.add(OutValueBinding(
                    kotlinType = "NativePlacement",
                    kotlinConv = { name -> "$name.alloc<${retValMirror.pointedTypeName}>().rawPtr" },
                    kotlinJniBridgeType = "NativePtr"
            ))
        }

        return paramBindings.toTypedArray()
    }

    /**
     * Returns names for parameters of Kotlin binding for given C function.
     */
    private fun paramNames(func: FunctionDecl): Array<String> {
        val paramNames = func.parameters.mapIndexed { i: Int, parameter: Parameter ->
            val name = parameter.name
            if (name != null && name != "") {
                name.mangleIfKeyword()
            } else {
                "arg$i"
            }
        }.toMutableList()

        if (func.returnType.unwrapTypedefs() is RecordType) {
            paramNames.add("retValPlacement")
        }

        return paramNames.toTypedArray()
    }

    /**
     * Produces to [out] the definition of Kotlin binding for given C function.
     */
    private fun generateKotlinBindingMethod(func: FunctionDecl) {
        val paramNames = paramNames(func)
        val paramBindings = paramBindings(func)
        val retValBinding = retValBinding(func)

        val args = paramNames.mapIndexed { i: Int, name: String ->
            "$name: " + paramBindings[i].kotlinType
        }.joinToString(", ")

        val header = "fun ${func.name}($args): ${retValBinding.kotlinType}"

        if (!func.requiresKotlinAdapter()) {
            assert(platform == KotlinPlatform.NATIVE)
            out(func.symbolNameAnnotation)
            out("external $header")
            return
        }

        fun generateBody(memScoped: Boolean) {
            val arguments = paramNames.mapIndexed { i: Int, name: String ->
                val binding = paramBindings[i]
                val externalParamName: String

                if (binding.kotlinConv != null) {
                    externalParamName = "_$name"
                    out("val $externalParamName = " + binding.kotlinConv.invoke(name))
                } else {
                    externalParamName = name
                }

                externalParamName

            }
            val callee = when (platform) {
                KotlinPlatform.JVM -> "externals.${func.name}"
                KotlinPlatform.NATIVE -> func.kotlinExternalName
            }
            out("val res = $callee(" + arguments.joinToString(", ") + ")")

            val result = retValBinding.conv("res")
            if (dumpShims) {
                val returnValueRepresentation  = result
                out("print(\"\${${returnValueRepresentation}}\\t= \")")
                out("print(\"${func.name}( \")")
                val argsRepresentation = paramNames.map{"\${${it}}"}.joinToString(", ")
                out("print(\"${argsRepresentation}\")")
                out("println(\")\")")
            }

            if (memScoped) {
                out(result)
            } else {
                out("return " + result)
            }
        }

        block(header) {
            val memScoped = paramBindings.any { it.memScoped }
            if (memScoped) {
                block("return memScoped") {
                    generateBody(true)
                }
            } else {
                generateBody(false)
            }
        }
    }

    /**
     * Returns the expression to be parsed by Kotlin as string literal with given contents,
     * i.e. transforms `foo$bar` to `"foo\$bar"`.
     */
    private fun String.quoteAsKotlinLiteral(): String {
        val sb = StringBuilder()
        sb.append('"')

        this.forEach { c ->
            val escaped = when (c) {
                in 'a' .. 'z', in 'A' .. 'Z', in '0' .. '9', '_' -> c.toString()
                '$' -> "\\$"
                // TODO: improve result readability by preserving more characters.
                else -> "\\u" + "%04X".format(c.toInt())
            }
            sb.append(escaped)
        }

        sb.append('"')
        return sb.toString()
    }

    private fun getFfiStructType(elementTypes: List<Type>) =
            "Struct(" +
                    elementTypes.map { getFfiType(it) }.joinToString(", ") +
                    ")"

    private fun getFfiType(type: Type): String {
        return when(type) {
            is VoidType -> "Void"
            is Int8Type -> "SInt8"
            is UInt8Type -> "UInt8"
            is Int16Type -> "SInt16"
            is UInt16Type -> "UInt16"
            is Int32Type -> "SInt32"
            is UInt32Type -> "UInt32"
            is IntPtrType, is UIntPtrType, // TODO
            is PointerType -> "Pointer"
            is Int64Type -> "SInt64"
            is UInt64Type -> "UInt64"
            is ConstArrayType -> getFfiStructType(
                    Array(type.length.toInt(), { type.elemType }).toList()
            )
            is EnumType -> getFfiType(type.def.baseType)
            is RecordType -> {
                val def = type.decl.def!!
                if (!def.hasNaturalLayout) {
                    throw NotImplementedError(type.kotlinName)
                }
                getFfiStructType(def.fields.map { it.type })
            }
            is Typedef -> getFfiType(type.def.aliased)
            else -> throw NotImplementedError(type.toString())
        }
    }

    private fun getArgFfiType(type: Type) = when (type.unwrapTypedefs()) {
        is ArrayType -> "Pointer"
        else -> getFfiType(type)
    }

    private fun getRetValFfiType(type: Type) = getArgFfiType(type)

    private fun generateFunctionType(type: FunctionType, name: String) = when (platform) {
        KotlinPlatform.JVM -> generateJvmFunctionType(type, name)
        KotlinPlatform.NATIVE -> generateNativeFunctionType(type, name)
    }

    /**
     * Returns `true` iff the function binding for Kotlin Native
     * requires non-trivial Kotlin adapter to convert arguments.
     */
    private fun FunctionDecl.requiresKotlinAdapter(): Boolean {
        if (platform != KotlinPlatform.NATIVE) {
            return true
        }

        return this.parameters.any { treatCFunctionParameterAsString(it.type) } ||
                this.returnType.unwrapTypedefs() is RecordType
    }

    /**
     * Returns `true` iff the function binding for Kotlin Native
     * requires non-trivial C adapter to convert arguments.
     */
    private fun FunctionDecl.requiresCAdapter(): Boolean {
        if (platform != KotlinPlatform.NATIVE) {
            return true
        }

        val parameterTypes = this.parameters.map { it.type }
        return (parameterTypes + returnType).any { it.unwrapTypedefs() is RecordType }
    }

    private fun FunctionType.requiresAdapterOnNative(): Boolean {
        assert (platform == KotlinPlatform.NATIVE)
        return (parameterTypes + returnType).any { it.unwrapTypedefs() is RecordType }
    }

    private fun generateNativeFunctionType(type: FunctionType, name: String) {
        if (type.requiresAdapterOnNative()) {
            out("object $name : CFunctionType {}")
        } else {
            val kotlinFunctionType = getKotlinFunctionType(type)
            out("object $name : CTriviallyAdaptedFunctionType<$kotlinFunctionType>()")
        }
    }

    private fun generateJvmFunctionType(type: FunctionType, name: String) {
        val kotlinFunctionType = getKotlinFunctionType(type)

        val constructorArgs = try {
            listOf(getRetValFfiType(type.returnType)) +
                    type.parameterTypes.map { getArgFfiType(it) }

        } catch (e: Throwable) {
            println("Warning: cannot generate definition for function type $name")
            out("object $name : CFunctionType {}")
            return
        }

        val constructorArgsStr = constructorArgs.joinToString(", ")

        block("object $name : CAdaptedFunctionTypeImpl<$kotlinFunctionType>($constructorArgsStr)") {
            block("override fun invoke(function: $kotlinFunctionType,  args: CArray<COpaquePointerVar>, ret: COpaquePointer)") {
                val args = type.parameterTypes.mapIndexed { i, paramType ->
                    val pointedTypeName = mirror(paramType).pointedTypeName
                    val ref = "args[$i].value!!.reinterpret<$pointedTypeName>().pointed"
                    when (paramType.unwrapTypedefs()) {
                        is RecordType -> ref
                        else -> "$ref.value"
                    }
                }.joinToString(", ")

                out("val res = function($args)")

                when (type.returnType.unwrapTypedefs()) {
                    is RecordType -> throw NotImplementedError()
                    is VoidType -> {} // nothing to do
                    else -> {
                        val pointedTypeName = mirror(type.returnType).pointedTypeName
                        out("ret.reinterpret<$pointedTypeName>().pointed.value = res")
                    }
                }

            }
        }
    }

    private fun integerLiteral(type: Type, value: Long): String? {
        if (value == Long.MIN_VALUE) {
            return "${value + 1} - 1" // Workaround for "The value is out of range" compile error.
        }

        val unwrappedType = type.unwrapTypedefs()
        if (unwrappedType !is PrimitiveType) {
            return null
        }

        val narrowedValue: Number = when (unwrappedType.kotlinType) {
            "Byte" -> value.toByte()
            "Short" -> value.toShort()
            "Int" -> value.toInt()
            "Long" -> value
            else -> return null
        }

        return narrowedValue.toString()
    }

    private fun floatingLiteral(type: Type, value: Double): String? {
        return when (type.unwrapTypedefs()) {
            Float32Type -> {
                val floatValue = value.toFloat()
                val bits = java.lang.Float.floatToRawIntBits(floatValue)
                "bitsToFloat($bits) /* == $floatValue */"
            }
            Float64Type -> {
                val bits = java.lang.Double.doubleToRawLongBits(value)
                "bitsToDouble($bits) /* == $value */"
            }
            else -> null
        }
    }

    private fun generateConstant(constant: ConstantDef) {
        val literal = when (constant) {
            is IntegerConstantDef -> integerLiteral(constant.type, constant.value) ?: return
            is FloatingConstantDef -> floatingLiteral(constant.type, constant.value) ?: return
            else -> {
                // Not supported yet, ignore:
                return
            }
        }

        val kotlinType = mirror(constant.type).argType

        // TODO: improve value rendering.

        // TODO: consider using `const` modifier.
        // It is not currently possible for floating literals.
        // Also it provokes constant propagation which can reduce binary compatibility
        // when replacing interop stubs without recompiling the application.

        out("val ${constant.name}: $kotlinType = $literal")
    }

    /**
     * The name of C adapter to be used for the function binding for Kotlin Native.
     */
    private val FunctionDecl.cStubName: String
        get() {
            require(platform == KotlinPlatform.NATIVE)
            return "kni_" + pkgName.replace('/', '_') + '_' + this.name
        }

    private val FunctionDecl.kotlinExternalName: String
        get() {
            require(platform == KotlinPlatform.NATIVE)
            require(this.requiresKotlinAdapter())
            return "kni_$name"
        }

    /**
     * The annotation for an external function on Kotlin Native
     * to bind it to either the C function itself or the C adapter.
     */
    private val FunctionDecl.symbolNameAnnotation: String
        get() {
            require(platform == KotlinPlatform.NATIVE)
            val symbolName = if (requiresCAdapter()) {
                cStubName
            } else {
                // `@SymbolName` annotation on Kotlin Native defines the LLVM function name
                // which is assumed to match the C function name.
                // However on some platforms (e.g. macOS) C function names are being mangled.
                // Using `0x01` prefix prevents LLVM from mangling the name.
                "\u0001${binaryName}"
            }
            return "@SymbolName(${symbolName.quoteAsKotlinLiteral()})"
        }

    /**
     * Produces to [out] the definition of Kotlin JNI function used in binding for given C function.
     */
    private fun generateKotlinExternalMethod(func: FunctionDecl) {
        val paramNames = paramNames(func)
        val paramBindings = paramBindings(func)
        val retValBinding = retValBinding(func)

        val args = paramNames.mapIndexed { i: Int, name: String ->
            "$name: " + paramBindings[i].kotlinJniBridgeType
        }.joinToString(", ")

        when (platform) {
            KotlinPlatform.JVM -> {
                out("external fun ${func.name}($args): ${retValBinding.kotlinJniBridgeType}")
            }
            KotlinPlatform.NATIVE -> {
                out(func.symbolNameAnnotation)
                out("private external fun ${func.kotlinExternalName}($args): ${retValBinding.kotlinJniBridgeType}")
            }
            else -> TODO(platform.toString())
        }
    }

    /**
     * Produces to [out] the contents of file with Kotlin bindings.
     */
    fun generateKotlinFile() {
        if (pkgName != "") {
            out("package $pkgName")
            out("")
        }
        out("import kotlinx.cinterop.*")
        out("")

        functionsToBind.forEach {
            try {
                transaction {
                    generateKotlinBindingMethod(it)
                    out("")
                }
            } catch (e: Throwable) {
                println("Warning: cannot generate binding definition for function ${it.name}")
            }
        }

        nativeIndex.macroConstants.forEach {
            generateConstant(it)
        }
        out("")

        nativeIndex.structs.forEach { s ->
            try {
                transaction {
                    generateStruct(s)
                    out("")
                }
            } catch (e: Throwable) {
                println("Warning: cannot generate definition for struct ${s.kotlinName}")
            }
        }

        nativeIndex.enums.forEach { e ->
            generateEnum(e)
            out("")
        }

        nativeIndex.typedefs.forEach { t ->
            try {
                transaction {
                    generateTypedef(t)
                    out("")
                }
            } catch (e: Throwable) {
                println("Warning: cannot generate typedef ${t.name}")
            }
        }

        usedFunctionTypes.entries.forEach {
            generateFunctionType(it.key, it.value)
            out("")
        }

        generateKotlinExternals()
    }

    private fun generateKotlinExternals() = when (platform) {
        KotlinPlatform.JVM -> block("object externals") {
            out("init { System.loadLibrary(\"$libName\") }")
            functionsToBind.forEach {
                try {
                    transaction {
                        generateKotlinExternalMethod(it)
                        out("")
                    }
                } catch (e: Throwable) {
                    println("Warning: cannot generate external definition for function ${it.name}")
                }
            }
        }

        KotlinPlatform.NATIVE -> functionsToBind.forEach {
            try {
                transaction {
                    if (it.requiresKotlinAdapter()) {
                        generateKotlinExternalMethod(it)
                        out("")
                    }
                }
            } catch (e: Throwable) {
                println("Warning: cannot generate external definition for function ${it.name}")
            }
        }
    }

    /**
     * Returns the C type to be used for value of given Kotlin type in JNI function implementation.
     */
    fun getCBridgeType(kotlinJniBridgeType: String): String {
        return when (platform) {
            KotlinPlatform.JVM -> getCJniBridgeType(kotlinJniBridgeType)
            KotlinPlatform.NATIVE -> getCNativeBridgeType(kotlinJniBridgeType)
        }
    }

    fun getCNativeBridgeType(kotlinJniBridgeType: String) = when (kotlinJniBridgeType) {
        "Unit" -> "void"
        "Byte" -> "int8_t"
        "Short" -> "int16_t"
        "Int" -> "int32_t"
        "Long" -> "int64_t"
        "Float" -> "float"
        "Double" -> "double" // TODO: float32_t, float64_t?
        "NativePtr" -> "void*"
        else -> TODO(kotlinJniBridgeType)
    }

    /**
     * Returns the C type to be used for value of given Kotlin type in JNI function implementation.
     */
    fun getCJniBridgeType(kotlinJniBridgeType: String) = when (kotlinJniBridgeType) {
        "Unit" -> "void"
        "Byte" -> "jbyte"
        "Short" -> "jshort"
        "Int" -> "jint"
        "Long", "NativePtr" -> "jlong"
        "Float" -> "jfloat"
        "Double" -> "jdouble"
        else -> throw NotImplementedError(kotlinJniBridgeType)
    }

    /**
     * Produces to [out] the contents of C source file to be compiled into JNI lib used for Kotlin bindings impl.
     */
    fun generateCFile(headerFiles: List<String>) {
        out("#include <stdint.h>")
        if (platform == KotlinPlatform.JVM) {
            out("#include <jni.h>")
        }
        headerFiles.forEach {
            out("#include <$it>")
        }
        out("")

        functionsToBind.forEach { func ->
            try {
                if (func.requiresCAdapter()) {
                    generateCJniFunction(func)
                }
            } catch (e: Throwable) {
                System.err.println("Warning: cannot generate C JNI function definition ${func.name}")
            }
        }
    }

    private tailrec fun Type.unwrapTypedefs(): Type = if (this is Typedef) {
        this.def.aliased.unwrapTypedefs()
    } else {
        this
    }

    /**
     * Produces to [out] the implementation of JNI function used in Kotlin binding for given C function.
     */
    private fun generateCJniFunction(func: FunctionDecl) {
        val funcReturnType = func.returnType.unwrapTypedefs()
        val paramNames = paramNames(func)
        val paramBindings = paramBindings(func)
        val retValBinding = retValBinding(func)

        val parameters = paramBindings
                .map { getCBridgeType(it.kotlinJniBridgeType) }
                .mapIndexed { i, type -> "$type ${paramNames[i]}" }

        val cReturnType = getCBridgeType(retValBinding.kotlinJniBridgeType)

        val funcDecl = when (platform) {
            KotlinPlatform.JVM -> {
                val joinedParameters = if (!parameters.isEmpty()) {
                    parameters.joinToString(separator = ", ", prefix = ", ")
                } else {
                    ""
                }

                val funcFullName = if (pkgName.isEmpty()) {
                    "externals.${func.name}"
                } else {
                    "$pkgName.externals.${func.name}"
                }
                val functionName = "Java_" + funcFullName.replace("_", "_1").replace('.', '_').replace("$", "_00024")
                "JNIEXPORT $cReturnType JNICALL $functionName (JNIEnv *jniEnv, jobject externalsObj$joinedParameters)"
            }
            KotlinPlatform.NATIVE -> {
                val joinedParameters = parameters.joinToString(", ")
                val functionName = func.cStubName
                "$cReturnType $functionName ($joinedParameters)"
            }
        }

        val arguments = func.parameters.mapIndexed { i, parameter ->
            val cType = parameter.type.getStringRepresentation()
            val name = paramNames[i]
            val unwrappedType = parameter.type.unwrapTypedefs()
            when (unwrappedType) {
                is RecordType -> "*($cType*)$name"
                is ArrayType -> {
                    val pointerCType = PointerType(unwrappedType.elemType).getStringRepresentation()
                    "($pointerCType)$name"
                }
                else -> "($cType)$name"
            }
        }.joinToString(", ")

        val callExpr = "${func.name}($arguments)"

        block(funcDecl) {

            if (cReturnType == "void") {
                out("$callExpr;")
            } else if (funcReturnType is RecordType) {
                out("*(${funcReturnType.decl.spelling}*)retValPlacement = $callExpr;")
                out("return ($cReturnType) retValPlacement;")
            } else {
                out("return ($cReturnType) ($callExpr);")
            }
        }
    }
}
