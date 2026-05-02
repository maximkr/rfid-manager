package com.trackstudio.rfidmanager

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class RadarGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Int>()
    private val maxPoints = 50
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_primary)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_primary)
        alpha = 80 // Darker area under the line
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
        alpha = 60
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
        textSize = 24f
        alpha = 150
    }

    private val path = Path()
    private val areaPath = Path()

    fun addValue(value: Int) {
        // Ensure value is within 0-100 range
        val clampedValue = value.coerceIn(0, 100)
        dataPoints.add(clampedValue)
        if (dataPoints.size > maxPoints) {
            dataPoints.removeAt(0)
        }
        invalidate()
    }

    fun clear() {
        dataPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Reserve space for labels on the left
        val labelWidth = 60f
        val contentW = (width - paddingLeft - paddingRight - labelWidth).toFloat()
        val contentH = (height - paddingTop - paddingBottom).toFloat()
        val startX = paddingLeft.toFloat() + labelWidth
        val startY = paddingTop.toFloat()
        
        drawGrid(canvas, startX, startY, contentW, contentH)
        
        if (dataPoints.isEmpty()) return

        val stepX = contentW / (maxPoints - 1)
        val maxValue = 100f

        path.reset()
        areaPath.reset()

        // Calculate offset to start drawing from the right if points < maxPoints
        val dataOffset = (maxPoints - dataPoints.size) * stepX

        dataPoints.forEachIndexed { index, value ->
            val x = startX + dataOffset + (index * stepX)
            // Use 95% of height for the graph to keep the 0-line visible
            val y = startY + contentH - (value.toFloat() / maxValue * (contentH * 0.9f))
            
            if (index == 0) {
                path.moveTo(x, y)
                areaPath.moveTo(x, startY + contentH)
                areaPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                areaPath.lineTo(x, y)
            }
            
            if (index == dataPoints.lastIndex) {
                areaPath.lineTo(x, startY + contentH)
                areaPath.close()
            }
        }

        canvas.drawPath(areaPath, areaPaint)
        canvas.drawPath(path, linePaint)
    }

    private fun drawGrid(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        // Horizontal lines & labels
        val levels = arrayOf(0, 50, 100)
        levels.forEach { level ->
            val ly = y + h - (level / 100f * (h * 0.9f))
            canvas.drawLine(x, ly, x + w, ly, gridPaint)
            // Draw text to the left of the grid
            canvas.drawText("$level%", x - 55, ly + 8, textPaint)
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
