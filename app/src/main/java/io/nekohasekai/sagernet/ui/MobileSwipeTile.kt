package app.qthrone.mobile.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/** Horizontal swipe reveals actions; vertical drags continue scrolling the subscription list. */
class MobileSwipeTile(context:Context, private val row:View, test:View, share:View, delete:View, private val deleteOnly:Boolean=false):FrameLayout(context) {
    private var settling: ValueAnimator? = null
    var onOpened: ((MobileSwipeTile) -> Unit)? = null
    private val primaryActions=listOf(test,share)
    private val actionWidth=64*resources.displayMetrics.density
    fun showShareChoices(vararg choices:View) {
        left.removeAllViews()
        addActions(choices.toList())
        widthPx=actionWidth*choices.size
        left.layoutParams=left.layoutParams.apply { width=widthPx.toInt() }
        settle(widthPx*direction)
    }
    private fun addActions(actions: List<View>) {
        actions.forEachIndexed { index, action ->
            if (index > 0) left.addView(View(context).apply { setBackgroundColor(android.graphics.Color.WHITE) }, LinearLayout.LayoutParams(resources.displayMetrics.density.toInt().coerceAtLeast(1), -1))
            left.addView(action, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }
    private fun restoreActions() {
        left.removeAllViews()
        addActions(primaryActions)
        widthPx=actionWidth*2
        left.layoutParams=left.layoutParams.apply { width=widthPx.toInt() }
    }
    fun closeActions() {
        left.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        right.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        settle(0f)
    }
    private var widthPx=actionWidth*2
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f; private var downY=0f; private var initial=0f
    private var dragging=false
    private var touchedRow=false
    private val direction: Float get() = if(layoutDirection==View.LAYOUT_DIRECTION_RTL) -1f else 1f
    private val left=LinearLayout(context)
    private val right=delete
    init {
        addActions(primaryActions)
        addView(left,LayoutParams(widthPx.toInt(),-1,android.view.Gravity.START))
        addView(delete,LayoutParams((widthPx/2).toInt(),-1,android.view.Gravity.END))
        addView(row,LayoutParams(-1,-2))
        clipChildren=true
        left.visibility=View.INVISIBLE
        right.visibility=View.INVISIBLE
        left.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        right.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private fun settle(target: Float) {
        settling?.cancel()
        val animation=ValueAnimator.ofFloat(row.translationX,target)
        settling=animation
        animation.duration=if(MobileStyle.new) 280 else 160
        animation.interpolator=android.view.animation.PathInterpolator(.2f,0f,.15f,1f)
        animation.addUpdateListener {
            row.translationX=it.animatedValue as Float
            val offset=row.translationX*direction
            left.visibility=if(offset>0f) View.VISIBLE else View.INVISIBLE
            right.visibility=if(offset<0f) View.VISIBLE else View.INVISIBLE
            invalidate()
        }
        animation.addListener(object: AnimatorListenerAdapter() {
            private var cancelled=false
            override fun onAnimationCancel(animation: Animator) { cancelled=true }
            override fun onAnimationEnd(animation: Animator) {
                if(cancelled) return
                if(target==0f) restoreActions()
                if(settling===animation) settling=null
                invalidate()
            }
        })
        animation.start()
    }
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if(child===row) return super.drawChild(canvas,child,drawingTime)
        val offset=row.translationX
        if((child===left && offset*direction<=0f) || (child===right && offset*direction>=0f)) return false
        val save=canvas.save()
        val revealLeft=(child===left && direction>0f) || (child===right && direction<0f)
        if(revealLeft) canvas.clipRect(0f,0f,(row.left+offset-.5f).coerceAtLeast(0f),height.toFloat())
        else canvas.clipRect((row.right+offset+.5f).coerceAtMost(width.toFloat()),0f,width.toFloat(),height.toFloat())
        val drawn=super.drawChild(canvas,child,drawingTime)
        canvas.restoreToCount(save)
        return drawn
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The profile determines the tile height; hidden action panels must not fill the scroll viewport.
        row.measure(getChildMeasureSpec(widthMeasureSpec, 0, LayoutParams.MATCH_PARENT),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(resolveSize(row.measuredWidth, widthMeasureSpec),
            resolveSize(row.measuredHeight, heightMeasureSpec))
        val heightSpec=MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        left.measure(MeasureSpec.makeMeasureSpec(widthPx.toInt(), MeasureSpec.EXACTLY),heightSpec)
        right.measure(MeasureSpec.makeMeasureSpec(actionWidth.toInt(), MeasureSpec.EXACTLY),heightSpec)
    }
    override fun onDetachedFromWindow() {
        settling?.cancel();settling=null
        super.onDetachedFromWindow()
    }
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN) { settling?.cancel(); settling=null; downX=e.x;downY=e.y;initial=row.translationX*direction;dragging=false; touchedRow=e.x>=row.translationX && e.x<row.translationX+row.width; if(initial!=0f && touchedRow) return true }
        if(e.actionMasked==MotionEvent.ACTION_MOVE && abs(e.x-downX)>slop && abs(e.x-downX)>abs(e.y-downY)) {
            dragging=true;parent.requestDisallowInterceptTouchEvent(true);return true
        }
        if(e.actionMasked==MotionEvent.ACTION_UP && initial!=0f && !dragging && touchedRow) return true
        return super.onInterceptTouchEvent(e)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if(abs(e.x-downX)>slop && abs(e.x-downX)>abs(e.y-downY)) dragging=true
                val offset=(initial+(e.x-downX)*direction).coerceIn(-actionWidth,if(deleteOnly) 0f else widthPx)
                left.visibility=if(offset>0) View.VISIBLE else View.INVISIBLE
                right.visibility=if(offset<0) View.VISIBLE else View.INVISIBLE
                row.translationX=offset*direction
                invalidate()
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                val x=row.translationX*direction
                val end=if(e.actionMasked==MotionEvent.ACTION_CANCEL || !dragging) 0f else if(x>widthPx*.35f) widthPx else if(x< -actionWidth*.4f) -actionWidth else 0f
                if(end!=0f) onOpened?.invoke(this)
                left.importantForAccessibility=if(end>0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                right.importantForAccessibility=if(end<0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                settle(end*direction)
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
