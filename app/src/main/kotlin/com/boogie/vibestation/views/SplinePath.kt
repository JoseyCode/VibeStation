package com.boogie.vibestation.views

import android.graphics.Path

/** Smooth curves through a row of points, the same Catmull-Rom style the visualizer waves use. */
internal object SplinePath {

    private const val TENSION = 6f

    /**
     * Adds a smooth curve through the points to [path].
     *
     * @param path    Path to extend.
     * @param xs      Horizontal positions.
     * @param ys      Vertical positions.
     * @param count   How many leading points of [xs] and [ys] to use; at least 2.
     * @param reverse True to walk the points from the last to the first.
     * @param move    True to start a new outline at the first point, false to draw a line to it from where the path is.
     */
    fun add(path: Path, xs: FloatArray, ys: FloatArray, count: Int, reverse: Boolean = false, move: Boolean = true) {
        fun at(k: Int) = if (reverse) count - 1 - k.coerceIn(0, count - 1) else k.coerceIn(0, count - 1)
        if (move) path.moveTo(xs[at(0)], ys[at(0)]) else path.lineTo(xs[at(0)], ys[at(0)])
        for (k in 0 until count - 1) {
            val before = at(k - 1)
            val from = at(k)
            val to = at(k + 1)
            val after = at(k + 2)
            path.cubicTo(
                xs[from] + (xs[to] - xs[before]) / TENSION, ys[from] + (ys[to] - ys[before]) / TENSION,
                xs[to] - (xs[after] - xs[from]) / TENSION, ys[to] - (ys[after] - ys[from]) / TENSION,
                xs[to], ys[to]
            )
        }
    }
}
