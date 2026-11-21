package io.github.alexzhirkevich.compottie.internal.content

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.LottiePainterImpl
import io.github.alexzhirkevich.compottie.internal.AnimationState
import io.github.alexzhirkevich.compottie.mockFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PathSourcesTest {

    private class FakeContent(var version: Int) : PathContent {
        val path = Path()
        override val name: String? = null
        override fun setContents(contentsBefore: List<Content>, contentsAfter: List<Content>) {}
        override fun getPath(state: AnimationState): Path = path
        override val pathVersion: Int get() = version
    }

    private fun withState(block: (AnimationState) -> Unit) {
        val composition = runBlocking {
            LottieCompositionSpec.JsonString(
                """{"v":"5.7.0","fr":60,"ip":0,"op":60,"w":10,"h":10,"layers":[]}"""
            ).load()
        }
        LottiePainterImpl(
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
        ).withState(block)
    }

    @Test
    fun firstCollectIsAChange() = withState { state ->
        assertTrue(PathSources().collect(listOf(FakeContent(1)), state))
    }

    @Test
    fun sameVersionsAndMatrixAreNoChange() = withState { state ->
        val sources = PathSources()
        val contents = listOf(FakeContent(1), FakeContent(7))
        val matrix = Matrix().apply { translate(3f, 4f) }
        sources.collect(contents, state, matrix)

        assertFalse(sources.collect(contents, state, Matrix().apply { translate(3f, 4f) }))
    }

    @Test
    fun newVersionOfOneSourceIsAChange() = withState { state ->
        val sources = PathSources()
        val second = FakeContent(7)
        val contents = listOf(FakeContent(1), second)
        sources.collect(contents, state)
        second.version = 8

        assertTrue(sources.collect(contents, state))
        assertFalse(sources.collect(contents, state))
    }

    @Test
    fun untrackedSourceIsAlwaysAChange() = withState { state ->
        val sources = PathSources()
        val contents = listOf(FakeContent(1), FakeContent(UntrackedPathVersion))
        sources.collect(contents, state)

        assertTrue(sources.collect(contents, state))
        assertTrue(sources.collect(contents, state))
    }

    @Test
    fun otherMatrixIsAChange() = withState { state ->
        val sources = PathSources()
        val contents = listOf(FakeContent(1))
        sources.collect(contents, state, Matrix())

        assertTrue(sources.collect(contents, state, Matrix().apply { rotateZ(10f) }))
        assertTrue(sources.collect(contents, state, null))
        assertTrue(sources.collect(contents, state, Matrix()))
    }

    @Test
    fun otherSourceListIsAChange() = withState { state ->
        val sources = PathSources()
        sources.collect(listOf(FakeContent(1)), state)

        assertTrue(sources.collect(listOf(FakeContent(1), FakeContent(1)), state))
    }

    @Test
    fun otherSourcesWithTheSameVersionsAreAChange() = withState { state ->
        val sources = PathSources()
        sources.collect(listOf(FakeContent(1), FakeContent(2)), state)

        assertTrue(sources.collect(listOf(FakeContent(1), FakeContent(2)), state))
    }

    @Test
    fun invalidateMakesTheNextCollectAChange() = withState { state ->
        val sources = PathSources()
        val contents = listOf(FakeContent(1))
        sources.collect(contents, state)
        sources.invalidate()

        assertTrue(sources.collect(contents, state))
    }

    @Test
    fun reversedCollectListsPathsInReverse() = withState { state ->
        val sources = PathSources()
        val first = FakeContent(1)
        val second = FakeContent(2)
        sources.collect(listOf(first, second), state, reversed = true)

        assertEquals(2, sources.paths.size)
        assertSame(second.path, sources.paths[0])
        assertSame(first.path, sources.paths[1])
    }
}
