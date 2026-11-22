package dev.nyushu.optimizer.core

import dev.nyushu.optimizer.core.ini.IniDocument
import dev.nyushu.optimizer.core.switch.TitleId
import dev.nyushu.optimizer.core.switch.TitleKind
import org.junit.Assert.*
import org.junit.Test

class IniAndTitleTest {
    private val sample = "; header comment\n[Renderer]\nresolution_setup\\use_global=true\nresolution_setup=3\nunknown_future_key=42\n\n[Cpu]\ncpu_accuracy=0\n"

    @Test fun roundTripIsLossless() = assertEquals(sample, IniDocument.parse(sample).serialize())

    @Test fun readsKeysWithBackslashes() {
        val d = IniDocument.parse(sample)
        assertEquals("true", d.get("Renderer", "resolution_setup\\use_global"))
        assertNull(d.get("Cpu", "resolution_setup"))
    }

    @Test fun setUpdatesExistingAndPreservesUnknown() {
        val d = IniDocument.parse(sample)
        d.set("Renderer", "resolution_setup", "5")
        d.set("Renderer", "new_key", "x")
        val out = d.serialize()
        assertTrue(out.contains("unknown_future_key=42"))
        assertTrue(out.contains("; header comment"))
        assertEquals("5", IniDocument.parse(out).get("Renderer", "resolution_setup"))
        assertEquals("x", IniDocument.parse(out).get("Renderer", "new_key"))
        // new key must stay inside [Renderer], before [Cpu]
        assertTrue(out.indexOf("new_key") < out.indexOf("[Cpu]"))
    }

    @Test fun setCreatesMissingSection() {
        val d = IniDocument.empty(); d.set("Core", "use_multi_core", "true")
        assertEquals("[Core]\nuse_multi_core=true\n", d.serialize())
    }

    @Test fun removeWorksOnlyInSection() {
        val d = IniDocument.parse(sample)
        assertTrue(d.remove("Renderer", "unknown_future_key"))
        assertFalse(d.remove("Cpu", "unknown_future_key"))
        assertNull(d.get("Renderer", "unknown_future_key"))
    }

    @Test fun handlesCrLfAndEmpty() {
        assertEquals("a", IniDocument.parse("[S]\r\nk=a\r\n").get("S", "k"))
        assertEquals("", IniDocument.parse("").serialize())
    }

    @Test fun titleIdValidationAndKinds() {
        assertTrue(TitleId.isValid("0100000000010000"))
        assertFalse(TitleId.isValid("0100000000010"))
        assertFalse(TitleId.isValid("01000000000100ZZ"))
        assertEquals("0100ABCD00000000".uppercase(), TitleId.normalize(" 0100abcd00000000 "))
        assertEquals(TitleKind.BASE_GAME, TitleId.kindOf("0100000000010000"))
        assertEquals(TitleKind.UPDATE, TitleId.kindOf("0100000000010800"))
        assertEquals(TitleKind.DLC, TitleId.kindOf("0100000000011001"))
        assertEquals(TitleKind.OTHER, TitleId.kindOf("0500000000010000"))
        assertEquals("0100000000010000", TitleId.baseOf("0100000000010800"))
    }

    @Test fun titleIdFromFileName() {
        assertEquals("0100000000010000", TitleId.fromFileName("Some Game [0100000000010000][v0].nsp"))
        assertNull(TitleId.fromFileName("Some Game.nsp"))
        assertNull(TitleId.fromFileName("Game [1234].nsp"))
    }
}
