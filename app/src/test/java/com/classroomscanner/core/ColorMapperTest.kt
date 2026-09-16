package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorMapperTest {
    private fun name(h: Float, s: Float, v: Float) = ColorMapper.nameFromHsv(h, s, v)

    @Test fun black() = assertEquals("black", name(0f, 0f, 0.1f))
    @Test fun blackWinsOverHue() = assertEquals("black", name(220f, 1f, 0.15f))
    @Test fun white() = assertEquals("white", name(0f, 0.05f, 0.95f))
    @Test fun gray() = assertEquals("gray", name(0f, 0.05f, 0.5f))
    @Test fun red() = assertEquals("red", name(0f, 1f, 1f))
    @Test fun redNear360() = assertEquals("red", name(350f, 0.9f, 0.8f))
    @Test fun lightRedIsPink() = assertEquals("pink", name(5f, 0.3f, 0.9f))
    @Test fun orange() = assertEquals("orange", name(30f, 1f, 1f))
    @Test fun darkOrangeIsBrown() = assertEquals("brown", name(30f, 0.8f, 0.4f))
    @Test fun yellow() = assertEquals("yellow", name(60f, 1f, 1f))
    @Test fun green() = assertEquals("green", name(120f, 1f, 1f))
    @Test fun blue() = assertEquals("blue", name(220f, 1f, 1f))
    @Test fun purple() = assertEquals("purple", name(275f, 1f, 1f))
    @Test fun pink() = assertEquals("pink", name(320f, 1f, 1f))
    @Test fun darkFrameIsUnknown() = assertNull(ColorMapper.nameFromHsv(220f, 1f, 1f, frameIsDark = true))
}
