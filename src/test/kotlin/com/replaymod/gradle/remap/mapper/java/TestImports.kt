package com.replaymod.gradle.remap.mapper.java

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TestImports {
    @Test
    fun `remaps imported method`() {
        TestData.remap("""
            import static a.pkg.A.createA;
            class test { Object test = createA(); }
        """.trimIndent()) shouldBe """
            import static b.pkg.B.createB;
            class test { Object test = createB(); }
        """.trimIndent()
    }

    @Test
    fun `remaps ambiguous imported method when all referenced elements remap to the same name`() {
        TestData.remap("""
            import static a.pkg.A.aStaticOverload;
            class test { Object test = aStaticOverload() + aStaticOverload(1); }
        """.trimIndent()) shouldBe """
            import static b.pkg.B.bStaticOverload;
            class test { Object test = bStaticOverload() + bStaticOverload(1); }
        """.trimIndent()
    }

    @Test
    fun `remaps ambiguous imported method when only one is actually used`() {
        TestData.remap("""
            import static a.pkg.A.aAmbiguousMethod;
            class test { Object test = aAmbiguousMethod(); }
        """.trimIndent()) shouldBe """
            import static b.pkg.B.bAmbiguousMethodWithoutInt;
            class test { Object test = bAmbiguousMethodWithoutInt(); }
        """.trimIndent()

        TestData.remap("""
            import static a.pkg.A.aAmbiguousMethod;
            class test { Object test = aAmbiguousMethod(0); }
        """.trimIndent()) shouldBe """
            import static b.pkg.B.bAmbiguousMethodWithInt;
            class test { Object test = bAmbiguousMethodWithInt(0); }
        """.trimIndent()
    }

    @Test
    fun `refuses to remap ambiguous imported method`() {
        val (_, errors) = TestData.remapWithErrors("""
            import static a.pkg.A.aAmbiguousMethod;
            class test { Object test = aAmbiguousMethod() + aAmbiguousMethod(0); }
        """.trimIndent())
        errors shouldHaveSize 1
        val (line, error) = errors[0]
        line shouldBe 0
        error shouldContain "aAmbiguousMethod"
        error shouldContain "bAmbiguousMethodWithInt"
        error shouldContain "bAmbiguousMethodWithoutInt"
    }
}