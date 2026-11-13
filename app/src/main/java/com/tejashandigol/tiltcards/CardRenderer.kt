package com.tejashandigol.tiltcards

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal class CardRenderer(private val context: android.content.Context) {
    var onImageReady: () -> Unit = {}
    private val imageReady: () -> Unit = { onImageReady() }
    private var lastBitmap: Bitmap? = null
    private val preferences = context.getSharedPreferences("wallpaper", android.content.Context.MODE_PRIVATE)
    private val gesture = StepGesture()
    private val fixedMode get() = preferences.getBoolean("fixed_mode", false)

    fun syncSettings() {
        if (fixedMode) {
            selection.select(preferences.getInt("fixed_index", neutralCard(count)))
            target = selection.index.toFloat()
        }
        gesture.reset()
        tiltTarget = 0f
        settle()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val shades = intArrayOf(0xFF46553A.toInt(), 0xFF84493D.toInt(), 0xFF495777.toInt())
    private val lights = intArrayOf(0xFFE5FC87.toInt(), 0xFFFFAB8F.toInt(), 0xFFAFCAFF.toInt())
    private var cards = emptyList<java.io.File>()
    private var selection = CardSelection(3)
    private var target = 1f
    private var position = 1f
    private var lastFrame = 0L
    private var tiltTarget = 0f
    private var tiltPosition = 0f
    private val camera = Camera()
    private val depthMatrix = Matrix()

    val count get() = if (cards.isEmpty()) 3 else cards.size
    val index get() = target.roundToInt().coerceIn(0, count - 1)

    fun setCards(cards: List<java.io.File>) {
        this.cards = cards
        selection = CardSelection(count)
        target = selection.index.toFloat()
        syncSettings()
    }

    fun tilt(degrees: Float): Boolean {
        if (fixedMode) {
            val step = gesture.update(degrees)
            val previous = index
            if (step != 0) select((index + 1) % count)
            return previous != index
        }
        val oldTilt = tiltTarget
        tiltTarget = (degrees / 18f).coerceIn(-1f, 1f)
        val previous = target
        target = selection.update(degrees).toFloat()
        return target != previous || abs(oldTilt - tiltTarget) > 0.001f
    }

    fun select(index: Int) {
        selection.select(index)
        target = selection.index.toFloat()
        if (fixedMode) preferences.edit().putInt("fixed_index", selection.index).apply()
    }

    fun settle() {
        position = target
        tiltPosition = tiltTarget
        lastFrame = 0L
    }

    fun draw(canvas: Canvas, width: Float, height: Float): Boolean {
        if (fixedMode) {
            position = target
            drawCard(canvas, width, height, index)
            return false
        }
        val now = SystemClock.uptimeMillis()
        val delta = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(0, 50).toFloat()
        lastFrame = now
        position += (target - position) * (1f - exp(-delta / 125f))
        tiltPosition += (tiltTarget - tiltPosition) * (1f - exp(-delta / 110f))
        val moving = abs(target - position) > 0.0005f || abs(tiltTarget - tiltPosition) > 0.0005f
        if (!moving) position = target
        val lower = floor(position).toInt().coerceIn(0, count - 1)
        val upper = minOf(lower + 1, count - 1)
        val fraction = position - lower
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        camera.save()
        camera.setLocation(0f, 0f, -width * 3f / 72f)
        camera.rotateY(tiltPosition * 10f)
        camera.getMatrix(depthMatrix)
        camera.restore()
        depthMatrix.preTranslate(-width / 2f, -height / 2f)
        depthMatrix.postTranslate(width / 2f + tiltPosition * width * 0.008f, height / 2f)
        // Extra overscan covers perspective shortening at both extreme angles.
        canvas.scale(1.10f, 1.10f, width / 2f, height / 2f)
        canvas.concat(depthMatrix)
        if (upper == lower || fraction < 0.0005f) {
            drawCard(canvas, width, height, lower)
        } else {
            drawPageTurn(canvas, width, height, lower, upper, fraction)
        }
        canvas.restore()
        val lightX = width * (0.5f - tiltPosition * 0.9f)
        paint.shader = LinearGradient(lightX - width * 0.35f, 0f,
            lightX + width * 0.35f, height * 0.15f,
            intArrayOf(0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        return moving
    }

    private fun drawPageTurn(canvas: Canvas, width: Float, height: Float,
                             lower: Int, upper: Int, progress: Float) {
        // Reveal the incoming photo with a clean, opaque moving edge.
        drawCard(canvas, width, height, upper)
        val fold = width * (1f - progress)
        canvas.save()
        canvas.clipRect(0f, 0f, fold, height)
        drawCard(canvas, width, height, lower)
        canvas.restore()

    }

    private fun drawCard(canvas: Canvas, width: Float, height: Float, index: Int) {
        canvas.drawColor(0xFF131412.toInt())
        val file = cards.getOrNull(index)
        if (file == null) {
            drawDemo(canvas, width, height, index % 3)
            return
        }
        val loaded = PhotoStore.bitmap(context, file, imageReady)
        if (loaded != null) lastBitmap = loaded
        val bitmap = loaded ?: lastBitmap ?: return
        val scale = maxOf(width / bitmap.width, height / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        destination.set((width - imageWidth) / 2, (height - imageHeight) / 2,
            (width + imageWidth) / 2, (height + imageHeight) / 2)
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    private fun drawDemo(canvas: Canvas, width: Float, height: Float, index: Int) {
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, height, width, 0f,
            intArrayOf(0xFF141613.toInt(), shades[index]), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        paint.strokeWidth = width / 230f
        val centerX = width * (0.48f + (index - 1) * 0.08f)
        val centerY = height * 0.57f
        for (line in 1..27) {
            val radius = width * (0.13f + line * 0.016f)
            val offset = sin(line * 0.19f + index) * width * 0.13f
            paint.color = (lights[index] and 0x00FFFFFF) or ((88 + line * 5) shl 24)
            paint.style = Paint.Style.STROKE
            destination.set(centerX - radius + offset, centerY - radius * 0.8f,
                centerX + radius + offset, centerY + radius * 0.8f)
            canvas.save()
            canvas.rotate(-34f + index * 22f + line * 2.2f, centerX, centerY)
            canvas.drawOval(destination, paint)
            canvas.restore()
        }
        paint.style = Paint.Style.FILL
    }
}
