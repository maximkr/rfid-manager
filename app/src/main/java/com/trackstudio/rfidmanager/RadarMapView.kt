package com.trackstudio.rfidmanager

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.*

class RadarMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Blip(val yaw: Float, val rssi: Int, val timestamp: Long, val isTarget: Boolean)

    private val blips = mutableListOf<Blip>()
    private val maxAge = 5000L // 5 seconds history
    
    private var currentYaw = 0f
    
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.outline)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        alpha = 180
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_primary)
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        alpha = 180
    }

    fun updateOrientation(yaw: Float) {
        currentYaw = yaw
        invalidate()
    }

    fun addBlip(yaw: Float, rssi: Int, isTarget: Boolean) {
        blips.add(Blip(yaw, rssi, System.currentTimeMillis(), isTarget))
        // Limit total blips for performance
        if (blips.size > 200) blips.removeAt(0)
        invalidate()
    }

    fun clear() {
        blips.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val now = System.currentTimeMillis()
        blips.removeAll { now - it.timestamp > maxAge }

        val cx = width / 2f
        val cy = height - 50f // Origin at the bottom
        val radius = min(width.toFloat() - 100f, height.toFloat() - 100f)

        drawGrid(canvas, cx, cy, radius)
        drawCompass(canvas, cx, cy, radius)

        blips.forEach { blip ->
            // Calculate relative angle in degrees
            var relativeAngle = (blip.yaw - currentYaw)
            // Normalize to -180..180
            while (relativeAngle > 180) relativeAngle -= 360
            while (relativeAngle < -180) relativeAngle += 360

            // Only draw if in front hemisphere (-90 to +90 degrees)
            if (abs(relativeAngle) <= 95f) {
                val ageFactor = 1f - (now - blip.timestamp).toFloat() / maxAge
                // Map to radian, where 0 is UP (FRONT)
                val rad = Math.toRadians((relativeAngle - 90f).toDouble())
                
                // Distance from center is inverse to RSSI
                val distFactor = (100 - blip.rssi) / 100f
                val r = radius * distFactor
                
                val x = cx + (r * cos(rad)).toFloat()
                val y = cy + (r * sin(rad)).toFloat()

                if (blip.isTarget) {
                    targetPaint.alpha = (255 * ageFactor).toInt()
                    val pulse = (sin(now / 150.0) * 5f).toFloat()
                    canvas.drawCircle(x, y, 18f * ageFactor + 5f + pulse, targetPaint)
                } else {
                    otherPaint.alpha = (150 * ageFactor).toInt()
                    canvas.drawCircle(x, y, 10f * ageFactor, otherPaint)
                }
            }
        }
        
        // Center line (FRONT)
        val devicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            strokeWidth = 6f
            style = Paint.Style.STROKE
            alpha = 100
        }
        canvas.drawLine(cx, cy, cx, cy - radius, devicePaint)
    }

    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val directions = arrayOf("N", "E", "S", "W")
        directions.forEachIndexed { index, label ->
            // Absolute angle of the direction label
            val angle = Math.toRadians((index * 90.0) - currentYaw - 90.0)
            
            // Only draw compass labels if they are roughly in the front hemisphere view
            var relativeDeg = (index * 90f - currentYaw)
            while (relativeDeg > 180) relativeDeg -= 360
            while (relativeDeg < -180) relativeDeg += 360
            
            if (abs(relativeDeg) <= 100f) {
                val tx = cx + ((radius + 40f) * cos(angle)).toFloat()
                val ty = cy + ((radius + 40f) * sin(angle)).toFloat()
                canvas.drawText(label, tx, ty, compassPaint)
            }
        }
    }

    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        
        // Arc rings
        for (i in 1..3) {
            val r = radius * (i / 3f)
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, 180f, 180f, false, gridPaint)
        }
        
        // Degree lines
        val degreePaints = arrayOf(-90, -45, 0, 45, 90)
        degreePaints.forEach { deg ->
            val angle = Math.toRadians((deg - 90.0))
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            canvas.drawLine(cx, cy, x, y, gridPaint)
            
            val label = if (deg == 0) "FRONT" else "${if (deg > 0) "+" else ""}$deg°"
            val lx = cx + ((radius + 20f) * cos(angle)).toFloat()
            val ly = cy + ((radius + 20f) * sin(angle)).toFloat()
            
            val p = if (deg == 0) textPaint else gridPaint
            if (deg != 0) {
                // Small labels for degrees
                val oldSize = textPaint.textSize
                textPaint.textSize = 20f
                canvas.drawText(label, lx, ly, textPaint)
                textPaint.textSize = oldSize
            } else {
                canvas.drawText(label, lx, ly - 10f, textPaint)
            }
        }
    }
}
