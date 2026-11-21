package io.github.alexzhirkevich.compottie.internal.content

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import io.github.alexzhirkevich.compottie.internal.AnimationState

internal interface PathContent : Content {
    fun getPath(state: AnimationState) : Path

    /**
     * Changes each time the path returned by [getPath] changes. Read it after [getPath].
     *
     * [UntrackedPathVersion] means that the path may change on every [getPath] call.
     * */
    val pathVersion: Int get() = UntrackedPathVersion
}

internal const val UntrackedPathVersion: Int = -1

internal fun nextPathVersion(version: Int): Int = (version + 1) and Int.MAX_VALUE

/**
 * Remembers the inputs of a path that is built from other paths, so that the path is built
 * again only when a source path or the matrix changed.
 * */
internal class PathSources {

    private var contents: List<PathContent>? = null
    private var versions = IntArray(0)
    private val matrixValues = FloatArray(16)
    private var hasMatrix = false
    private var isValid = false

    val paths: MutableList<Path> = ArrayList()

    /**
     * Collects the paths of [contents] into [paths].
     *
     * @return true if the paths or the [matrix] changed since the last call
     * */
    fun collect(
        contents: List<PathContent>,
        state: AnimationState,
        matrix: Matrix? = null,
        reversed: Boolean = false,
    ): Boolean {
        paths.clear()
        if (contents !== this.contents || versions.size != contents.size) {
            this.contents = contents
            versions = IntArray(contents.size)
            isValid = false
        }
        var changed = !isValid
        for (n in contents.indices) {
            val i = if (reversed) contents.lastIndex - n else n
            val content = contents[i]
            paths.add(content.getPath(state))
            val version = content.pathVersion
            if (version == UntrackedPathVersion || version != versions[i]) {
                changed = true
            }
            versions[i] = version
        }
        if (matrix == null) {
            if (hasMatrix) {
                changed = true
                hasMatrix = false
            }
        } else if (!hasMatrix || !matrix.values.contentEquals(matrixValues)) {
            changed = true
            matrix.values.copyInto(matrixValues)
            hasMatrix = true
        }
        isValid = true
        return changed
    }

    fun invalidate() {
        isValid = false
    }
}
