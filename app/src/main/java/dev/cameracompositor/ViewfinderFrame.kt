package dev.cameracompositor

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/** Match the portrait 4:3 photo instead of cropping it to the remaining screen shape. */
class ViewfinderFrame(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val width = min(availableWidth, availableHeight * 3 / 4)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY))
    }
}
