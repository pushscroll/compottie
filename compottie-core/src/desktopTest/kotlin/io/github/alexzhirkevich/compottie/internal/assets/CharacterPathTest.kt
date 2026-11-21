package io.github.alexzhirkevich.compottie.internal.assets

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.LottiePainterImpl
import io.github.alexzhirkevich.compottie.internal.LottieJson
import io.github.alexzhirkevich.compottie.mockFontFamilyResolver
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class CharacterPathTest {

    /** A text draws the same glyph at each place where its character occurs. */
    @Test
    fun drawingAGlyphTwiceDrawsItTheSameWay() {
        val glyph = LottieJson.decodeFromString(
            CharacterPath.serializer(),
            """{"shapes":[{"ty":"gr","it":[
                {"ty":"sh","ks":{"a":0,"k":{"c":true,"v":[[0,0],[40,0],[40,40],[0,40]],"i":[[0,0],[0,0],[0,0],[0,0]],"o":[[0,0],[0,0],[0,0],[0,0]]}}},
                {"ty":"tr","a":{"a":0,"k":[0,0]},"p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}}
            ]}]}"""
        )
        val composition = runBlocking {
            LottieCompositionSpec.JsonString(
                """{"v":"5.7.0","fr":60,"ip":0,"op":60,"w":100,"h":100,"layers":[]}"""
            ).load()
        }
        val painter = LottiePainterImpl(
            composition = composition,
            progress = { 0f },
            assets = emptyList(),
            fonts = emptyMap(),
            theme = null,
            dynamicProperties = null,
            textMeasurer = TextMeasurer(mockFontFamilyResolver(), Density(1f), LayoutDirection.Ltr),
            applyOpacityToLayers = false,
            clipTextToBoundingBoxes = false,
            enableTextGrouping = false,
            clipToCompositionBounds = true,
            enableMergePaths = false,
            enableExpressions = false,
            coroutineContext = EmptyCoroutineContext,
            expressionEngineFactory = { _, _ -> error("no expressions") },
        )
        val matrix = Matrix().apply {
            translate(10f, 10f)
            scale(0.5f, 0.5f)
        }
        val fill = Paint().apply { color = Color.Red }
        val stroke = Paint().apply { strokeWidth = 0f }

        fun draw(): IntArray = painter.withState { state ->
            val bitmap = ImageBitmap(100, 100)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(100f, 100f)) {
                glyph.draw(this, state, matrix, stroke, fill)
            }
            bitmap.toPixelMap().buffer
        }

        val first = draw()
        assertTrue(first.any { it != 0 }, "the glyph draws nothing")
        assertContentEquals(first, draw())
    }
}
