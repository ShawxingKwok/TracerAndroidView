package pers.apollokwok.tracer.android

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import pers.apollokwok.ksputil.*
import pers.apollokwok.tracer.common.shared.*

internal class MyProvider : KspProvider(::MyProcessor)

private class MyProcessor : KspProcessor {
    override fun process(times: Int): List<KSAnnotated> {
        if (times == 1) process()
        return emptyList()
    }
}

// add 'override val xx get() = requireActivity()/requireParentFragment()' in 'XxFragmentTracer'
private fun process(){
    // return if error just occurred in tracer-common
    if (!Tags.interfacesBuilt) return

    val (activityType, fragmentType) =
        arrayOf(
            "androidx.appcompat.app.AppCompatActivity",
            "androidx.fragment.app.Fragment",
        )
        .map { resolver.getClassDeclarationByName(it)!!.asStarProjectedType() }

    val wronglyAnnotatedFragmentKlasses = mutableListOf<KSClassDeclaration>()

    getRootNodesKlasses()
        .filter { fragmentType.isAssignableFrom(it.asStarProjectedType()) }
        .mapNotNull { klass ->
            val context = klass.context ?: run {
                wronglyAnnotatedFragmentKlasses += klass
                return@mapNotNull null
            }
            val type = context.asStarProjectedType()
            when {
                activityType.isAssignableFrom(type) -> Triple(klass, context, "requireActivity()")
                fragmentType.isAssignableFrom(type) -> Triple(klass, context, "requireParentFragment()")
                else -> {
                    wronglyAnnotatedFragmentKlasses += klass
                    null
                }
            }
        }
        .forEach { (klass, context, requirement) ->
            val declHeader = "    override val `__${context.contractedName}` get() = "

            val cast = buildString {
                append(context.noPackageName()!!)
                if (context.typeParameters.any()){
                    append("<")
                    append(context.typeParameters.joinToString(", "){ "*" })
                    append(">")
                }
            }

            val declBody = "`_${klass.contractedName}`.$requirement as $cast"
            val outerDeclBody = declBody.replaceFirst("`", "`_")

            val pathEnding = "${Names.GENERATED_PACKAGE}.${getInterfaceNames(klass).first}"
                .replace(".", "/")
                .plus(".kt")

            val correspondingFiles = Environment.codeGenerator.generatedFile.filter { it.path.endsWith(pathEnding) }

            val lines = correspondingFiles.first().readLines().toMutableList()

            // add an import
            lines.add(
                index = lines.indexOfFirst { it.startsWith("import ") },
                element = "import ${context.outermostDecl.qualifiedName()}"
            )

            // add the requirement
            lines.add(index = lines.indexOf("}"), element = "$declHeader$declBody")
            lines.add(index = lines.lastIndexOf("}"), element = "$declHeader$outerDeclBody")

            val newText = lines.joinToString("\n")

            correspondingFiles.forEach { it.writeText(newText) }
        }

    Log.require(wronglyAnnotatedFragmentKlasses.none(), wronglyAnnotatedFragmentKlasses){
        "Each android fragment below must be annotated with ${Names.Nodes}, " +
        "with an arg of its activity class if it's a top fragment " +
        "or an arg of its parent fragment class if it's a child fragment."
    }
}