package com.claytonmuhoza.easybilling.domain

import com.claytonmuhoza.easybilling.domain.shared.Money
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Project rule: an amount is never a Double or a Float. The domain holds every amount, so none of
 * its classes may store, take or return a binary floating number.
 */
class NoFloatingPointTest {

    private val floatingTypes = setOf(
        Double::class.javaPrimitiveType, Double::class.javaObjectType,
        Float::class.javaPrimitiveType, Float::class.javaObjectType,
    )

    private fun domainClasses(): List<Class<*>> {
        val root = File(Money::class.java.protectionDomain.codeSource.location.toURI())
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .map { Class.forName(it, false, javaClass.classLoader) }
            .toList()
    }

    @Test
    fun `the domain classes are found`() {
        assertTrue(domainClasses().any { it == Money::class.java })
    }

    @Test
    fun `no domain class uses a Double or a Float`() {
        val offenders = domainClasses().flatMap { type ->
            val fields = type.declaredFields
                .filter { it.type in floatingTypes }
                .map { "${type.name}.${it.name}" }
            val methods = type.declaredMethods
                .filter { !Modifier.isVolatile(it.modifiers) } // bridges repeat their target
                .filter { it.returnType in floatingTypes || it.parameterTypes.any { p -> p in floatingTypes } }
                .map { "${type.name}.${it.name}()" }
            fields + methods
        }

        assertEquals(emptyList(), offenders)
    }
}
