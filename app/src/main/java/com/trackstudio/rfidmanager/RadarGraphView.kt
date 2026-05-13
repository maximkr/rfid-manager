package com.trackstudio.rfidmanager

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class RadarGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()
    private val emaSlowPoints = mutableListOf<Float>()
    
    private val maxPoints = 600 // 600 points @ 100ms = 60 seconds
    
    private var emaSlow = -90f
    private val alphaSlow = 0.02f // ~50 points smoothing
    
    private val fastLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_primary)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    
    private val slowLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_secondary)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    
    private val positiveDiffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_primary)
        alpha = 80
        style = Paint.Style.FILL
    }
    
    private val negativeDiffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_error) // Red
        alpha = 80
        style = Paint.Style.FILL
    }

    private val powerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_surfaceVariant)
        style = Paint.Style.FILL
    }

    private val powerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_primaryContainer)
        style = Paint.Style.FILL
    }

    private val powerMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_primary)
        style = Paint.Style.FILL
    }

    private val powerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_onSurface)
        textSize = 20f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val powerCaptionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
        textSize = 16f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
        alpha = 60
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
        textSize = 20f
        alpha = 150
    }

    private val powerCaption = context.getString(R.string.radar_power_max_label)
    private var displayPower = 30

    fun addValue(value: Float, maxPower: Int) {
        // Ensure value is within -90 to -10 range (dBm)
        val clampedValue = value.coerceIn(-90f, -10f)
        
        if (dataPoints.isEmpty() || (value == -90f && dataPoints.last() == -90f)) {
            emaSlow = clampedValue
        } else {
            emaSlow = (alphaSlow * clampedValue) + ((1 - alphaSlow) * emaSlow)
        }
        
        dataPoints.add(clampedValue)
        emaSlowPoints.add(emaSlow)
        
        displayPower = maxPower.coerceIn(0, 30)
        
        if (dataPoints.size > maxPoints) {
            dataPoints.removeAt(0)
            emaSlowPoints.removeAt(0)
        }
        invalidate()
    }

    fun clear() {
        dataPoints.clear()
        emaSlowPoints.clear()
        emaSlow = -90f
        displayPower = 30
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Reserve space for labels on the left and stable max-power indicator on the right
        val labelWidth = 70f
        val powerIndicatorWidth = 72f
        val contentW = (width - paddingLeft - paddingRight - labelWidth - powerIndicatorWidth).toFloat()
        val contentH = (height - paddingTop - paddingBottom).toFloat()
        val startX = paddingLeft.toFloat() + labelWidth
        val startY = paddingTop.toFloat()
        
        drawGrid(canvas, startX, startY, contentW, contentH)
        
        if (dataPoints.isEmpty()) return

        val stepX = contentW / (maxPoints - 1)
        val minValue = -90f
        val maxValue = -10f
        val range = maxValue - minValue

        val dataOffset = (maxPoints - dataPoints.size) * stepX

        val fastPath = Path()
        val slowPath = Path()

        // Draw Difference Area between Fast and Slow
        for (i in 0 until dataPoints.size - 1) {
            val fast1 = dataPoints[i]
            val slow1 = emaSlowPoints[i]
            val fast2 = dataPoints[i+1]
            val slow2 = emaSlowPoints[i+1]
            
            val x1 = startX + dataOffset + (i * stepX)
            val x2 = startX + dataOffset + ((i+1) * stepX)
            
            val yFast1 = startY + contentH - ((fast1 - minValue) / range * (contentH * 0.9f))
            val ySlow1 = startY + contentH - ((slow1 - minValue) / range * (contentH * 0.9f))
            val yFast2 = startY + contentH - ((fast2 - minValue) / range * (contentH * 0.9f))
            val ySlow2 = startY + contentH - ((slow2 - minValue) / range * (contentH * 0.9f))
            
            val diffPath = Path()
            diffPath.moveTo(x1, yFast1)
            diffPath.lineTo(x2, yFast2)
            diffPath.lineTo(x2, ySlow2)
            diffPath.lineTo(x1, ySlow1)
            diffPath.close()
            
            if (fast1 >= slow1) {
                canvas.drawPath(diffPath, positiveDiffPaint)
            } else {
                canvas.drawPath(diffPath, negativeDiffPaint)
            }
            
            if (i == 0) {
                fastPath.moveTo(x1, yFast1)
                slowPath.moveTo(x1, ySlow1)
            }
            fastPath.lineTo(x2, yFast2)
            slowPath.lineTo(x2, ySlow2)
        }

        // Draw for a single point edge case
        if (dataPoints.size == 1) {
            val x = startX + dataOffset
            val yFast = startY + contentH - ((dataPoints[0] - minValue) / range * (contentH * 0.9f))
            val ySlow = startY + contentH - ((emaSlowPoints[0] - minValue) / range * (contentH * 0.9f))
            fastPath.moveTo(x, yFast)
            fastPath.lineTo(x, yFast)
            slowPath.moveTo(x, ySlow)
            slowPath.lineTo(x, ySlow)
        }

        canvas.drawPath(slowPath, slowLinePaint)
        canvas.drawPath(fastPath, fastLinePaint)


        drawPowerIndicator(canvas, startX + contentW + 20f, startY, contentH)
    }

    private fun drawPowerIndicator(canvas: Canvas, x: Float, y: Float, h: Float) {
        val trackWidth = 44f
        val markerHeight = 6f
        val trackRect = RectF(x, y, x + trackWidth, y + h)
        val fillTop = y + h - (displayPower / 30f * h)
        val fillRect = RectF(x, fillTop, x + trackWidth, y + h)
        val markerRect = RectF(x, fillTop - markerHeight / 2f, x + trackWidth, fillTop + markerHeight / 2f)
        val textX = x + trackWidth / 2f
        val textY = fillTop.coerceIn(y + 34f, y + h - 16f)

        canvas.drawRoundRect(trackRect, 14f, 14f, powerTrackPaint)
        canvas.drawRoundRect(fillRect, 14f, 14f, powerFillPaint)
        canvas.drawRoundRect(markerRect, 3f, 3f, powerMarkerPaint)
        canvas.drawText(powerCaption, textX, textY - 12f, powerCaptionPaint)
        canvas.drawText(context.getString(R.string.unit_dbm, displayPower), textX, textY + 14f, powerTextPaint)
    }

    private fun drawGrid(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        // Horizontal lines & labels for dBm (-90 to -10)
        val levels = arrayOf(-90, -70, -50, -30, -10)
        val minValue = -90f
        val range = 80f
        
        levels.forEach { level ->
            val ly = y + h - ((level - minValue) / range * (h * 0.9f))
            canvas.drawLine(x, ly, x + w, ly, gridPaint)
            // Draw text to the left of the grid
            canvas.drawText("$level", x - 65, ly + 8, textPaint)
        }
        
        // Vertical lines
        val vLines = 5
        val stepX = w / vLines
        for (i in 0..vLines) {
            val vx = x + (i * stepX)
            canvas.drawLine(vx, y, vx, y + h, gridPaint)
        }
    }
}
