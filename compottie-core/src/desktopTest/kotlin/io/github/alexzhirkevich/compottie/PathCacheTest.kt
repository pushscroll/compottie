package io.github.alexzhirkevich.compottie

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.alexzhirkevich.compottie.dynamic.DynamicCompositionProvider
import io.github.alexzhirkevich.compottie.dynamic.createLottieDynamicProperties
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.impl.Stats
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shapes keep their paths between frames. These tests check that a painter that drew
 * other frames before draws each frame exactly like a new painter, and that a still
 * animation makes no new Skia paths.
 * */
class PathCacheTest {

    @Test
    fun cachedPathsDrawLikeNewPainters() {
        for ((name, json) in ANIMATIONS) {
            val composition = composition(json)
            val frames = composition.durationFrames.toInt()
            val order = (0 until frames step 3) + (frames - 1 downTo 0 step 7) + listOf(5, 5, 40, 2)
            val reused = Painter(composition)
            var drewSomething = false
            for (frame in order) {
                val expected = Painter(composition(json)).render(frame, frames)
                drewSomething = drewSomething || expected.any { it != 0 }
                assertContentEquals(expected, reused.render(frame, frames), "$name, frame $frame")
            }
            assertTrue(drewSomething, "$name draws nothing")
        }
    }

    @Test
    fun stillAnimationMakesNoNewPathsAfterTheFirstFrame() {
        val composition = composition(STILL)
        val painter = Painter(composition)
        painter.render(0, 60)

        val counter = CreatedObjects()
        Stats.allocated = counter
        Stats.enabled = true
        try {
            for (frame in 1 until 60) {
                painter.render(frame, 60)
            }
        } finally {
            Stats.enabled = false
        }

        assertEquals(0, counter["Path"] ?: 0, "Skia paths")
        assertEquals(0, counter["PathBuilder"] ?: 0, "Skia path builders")
    }

    /** Rep's eyes follow the finger through a layer offset that changes while the frame stays the same. */
    @Test
    fun layerOffsetThatChangesOnTheSameFrameRedrawsTheShapes() {
        var shift = 0f
        fun eyes() = createLottieDynamicProperties {
            layer("eyes") { transform { offset { source -> source + Offset(shift, shift / 2) } } }
        } as DynamicCompositionProvider

        val reused = Painter(composition(EYES), eyes())
        for (value in listOf(0f, 12f, 12f, -8f, 0f, 20f)) {
            shift = value
            val expected = Painter(composition(EYES), eyes()).render(30, 60)
            assertContentEquals(expected, reused.render(30, 60), "offset $value")
        }
        shift = 0f
        val still = Painter(composition(EYES), eyes()).render(30, 60)
        shift = 20f
        val moved = Painter(composition(EYES), eyes()).render(30, 60)
        assertTrue(!still.contentEquals(moved), "the offset moves nothing")
    }

    /**
     * Two players can draw one loaded animation at once, for example during a crossfade.
     * Tag 2.3.0 already draws the gradient case wrong with a shared composition, so it is left out.
     * */
    @Test
    fun twoPaintersOnOneCompositionDrawTheirOwnFrames() {
        for ((name, json) in ANIMATIONS - "nested groups with a gradient") {
            val shared = composition(json)
            val frames = shared.durationFrames.toInt()
            val first = Painter(shared)
            val second = Painter(shared)
            for (frame in 0 until frames step 5) {
                val other = frames - 1 - frame
                assertContentEquals(Painter(composition(json)).render(frame, frames), first.render(frame, frames), "$name, first painter, frame $frame")
                assertContentEquals(Painter(composition(json)).render(other, frames), second.render(other, frames), "$name, second painter, frame $other")
            }
        }
    }

    private fun composition(json: String): LottieComposition =
        runBlocking { LottieCompositionSpec.JsonString(json).load() }

    private class Painter(composition: LottieComposition, dynamicProperties: DynamicCompositionProvider? = null) {
        var progress by mutableFloatStateOf(0f)
        val impl = LottiePainterImpl(
            composition = composition,
            progress = { progress },
            assets = composition.animation.assets,
            fonts = emptyMap(),
            theme = null,
            dynamicProperties = dynamicProperties,
            textMeasurer = TextMeasurer(mockFontFamilyResolver(), Density(1f), LayoutDirection.Ltr),
            applyOpacityToLayers = false,
            clipTextToBoundingBoxes = false,
            enableTextGrouping = false,
            clipToCompositionBounds = true,
            enableMergePaths = false,
            enableExpressions = false,
            coroutineContext = EmptyCoroutineContext,
            expressionEngineFactory = { _, _ -> error("no expressions in these tests") },
        )
        val scope = CanvasDrawScope()

        fun render(frame: Int, frames: Int): IntArray {
            progress = frame.toFloat() / frames
            val bitmap = ImageBitmap(SIDE, SIDE)
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(SIDE.toFloat(), SIDE.toFloat())) {
                with(impl) { draw(size) }
            }
            return bitmap.toPixelMap().buffer
        }
    }

    /** Counts only creations: the default map also counts closes as -1. */
    private class CreatedObjects : ConcurrentHashMap<String, Int>() {
        override fun merge(
            key: String,
            value: Int,
            remappingFunction: BiFunction<in Int, in Int, out Int?>
        ): Int? = if (value > 0) super.merge(key, value, remappingFunction) else get(key)
    }

    private companion object {
        const val SIDE = 100

        fun still(v: String) = """{"a":0,"k":$v}"""

        fun moving(from: String, to: String) =
            """{"a":1,"k":[{"t":0,"s":$from,"i":{"x":[0.5],"y":[0.5]},"o":{"x":[0.5],"y":[0.5]}},{"t":59,"s":$to}]}"""

        val STILL_TRANSFORM = """{"ty":"tr","a":${still("[0,0]")},"p":${still("[0,0]")},"s":${still("[100,100]")},"r":${still("0")},"o":${still("100")}}"""

        val TURNING_TRANSFORM = """{"ty":"tr","a":${still("[0,0]")},"p":${still("[50,50]")},"s":${still("[100,100]")},"r":${moving("[0]", "[180]")},"o":${still("100")}}"""

        val SQUARE_PATH = """{"ty":"sh","ks":{"a":0,"k":{"c":true,"v":[[-20,-20],[20,-20],[20,20],[-20,20]],"i":[[0,0],[0,0],[0,0],[0,0]],"o":[[0,0],[0,0],[0,0],[0,0]]}}}"""

        val MOVING_PATH = """{"ty":"sh","ks":{"a":1,"k":[{"t":0,"s":[{"c":true,"v":[[-20,-20],[20,-20],[20,20],[-20,20]],"i":[[0,0],[0,0],[0,0],[0,0]],"o":[[0,0],[0,0],[0,0],[0,0]]}],"i":{"x":0.5,"y":0.5},"o":{"x":0.5,"y":0.5}},{"t":59,"s":[{"c":true,"v":[[-30,-10],[10,-30],[30,10],[-10,30]],"i":[[0,0],[0,0],[0,0],[0,0]],"o":[[0,0],[0,0],[0,0],[0,0]]}]}]}}"""

        val STILL_ELLIPSE = """{"ty":"el","p":${still("[10,5]")},"s":${still("[30,20]")}}"""

        val GROWING_ELLIPSE = """{"ty":"el","p":${still("[0,0]")},"s":${moving("[10,10]", "[60,40]")}}"""

        val STILL_RECT = """{"ty":"rc","p":${still("[-5,-5]")},"s":${still("[30,30]")},"r":${still("4")}}"""

        val ROUNDING_RECT = """{"ty":"rc","p":${still("[0,0]")},"s":${still("[50,40]")},"r":${moving("[0]", "[18]")}}"""

        val FILL = """{"ty":"fl","c":${still("[0.9,0.2,0.1,1]")},"o":${still("100")},"r":1}"""

        val STROKE = """{"ty":"st","c":${still("[0.1,0.2,0.9,1]")},"o":${still("100")},"w":${still("3")},"lc":2,"lj":2,"ml":4}"""

        val GRADIENT_FILL = """{"ty":"gf","o":${still("100")},"r":1,"s":${still("[-20,0]")},"e":${still("[20,0]")},"t":2,"g":{"p":2,"k":${still("[0,1,0,0,1,0,0,1]")}}}"""

        val TRIM = """{"ty":"tm","s":${still("0")},"e":${moving("[10]", "[100]")},"o":${still("0")},"m":1}"""

        fun group(vararg items: String) = """{"ty":"gr","it":[${items.joinToString(",")}]}"""

        fun layer(index: Int, vararg shapes: String, extra: String = "") =
            """{"ty":4,"ind":$index,"ip":0,"op":60,"st":0,"sr":1,"ks":{"a":${still("[0,0]")},"p":${still("[0,0]")},"s":${still("[100,100]")},"r":${still("0")},"o":${still("100")}},"shapes":[${shapes.joinToString(",")}]$extra}"""

        fun animation(vararg layers: String) =
            """{"v":"5.7.0","fr":60,"ip":0,"op":60,"w":$SIDE,"h":$SIDE,"layers":[${layers.joinToString(",")}]}"""

        val STILL = animation(
            layer(
                1,
                group(group(SQUARE_PATH, STILL_ELLIPSE, STILL_TRANSFORM), STILL_RECT, FILL, STROKE, GRADIENT_FILL, TURNING_TRANSFORM.replace(moving("[0]", "[180]"), still("30")))
            )
        )

        val MASK_PATH = """{"a":1,"k":[{"t":0,"s":[{"c":true,"v":[[20,20],[80,20],[80,80],[20,80]],"i":[[0,0],[0,0],[0,0],[0,0]],"o":[[0,0],[0,0],[0,0],[0,0]]}],"i":{"x":0.5,"y":0.5},"o":{"x":0.5,"y":0.5}},{"t":59,"s":[{"c":true,"v":[[40,30],[70,40],[60,70],[30,60]],"i":[[0,0],[0,0],[0,0],[0,0]],"o":[[0,0],[0,0],[0,0],[0,0]]}]}]}"""

        val MASK = """,
            "hasMask":true,
            "masksProperties":[{"mode":"a","inv":false,"o":${still("100")},"pt":$MASK_PATH}]
        """.trimIndent()

        val EYES = animation(
            layer(1, group(STILL_ELLIPSE, STILL_RECT, FILL, STROKE, GRADIENT_FILL, STILL_TRANSFORM), extra = ",\"nm\":\"eyes\""),
            layer(2, group(SQUARE_PATH, FILL, TURNING_TRANSFORM)),
        )

        val ANIMATIONS = mapOf(
            "still shapes in a turning group" to animation(
                layer(1, group(SQUARE_PATH, STILL_ELLIPSE, STILL_RECT, FILL, STROKE, TURNING_TRANSFORM))
            ),
            "moving shapes" to animation(
                layer(1, group(MOVING_PATH, GROWING_ELLIPSE, FILL, STROKE, TURNING_TRANSFORM))
            ),
            "nested groups with a gradient" to animation(
                layer(1, group(group(SQUARE_PATH, STILL_TRANSFORM), group(GROWING_ELLIPSE, STILL_TRANSFORM), GRADIENT_FILL, STROKE, TURNING_TRANSFORM))
            ),
            "rectangle with growing corners" to animation(
                layer(1, group(ROUNDING_RECT, FILL, STROKE, TURNING_TRANSFORM))
            ),
            "trimmed stroke" to animation(
                layer(1, group(STILL_ELLIPSE, SQUARE_PATH, TRIM, STROKE, FILL, TURNING_TRANSFORM))
            ),
            "masked layer" to animation(
                layer(1, group(STILL_ELLIPSE, STILL_RECT, FILL, TURNING_TRANSFORM), extra = MASK)
            ),
            "still scene" to STILL,
        )
    }
}
