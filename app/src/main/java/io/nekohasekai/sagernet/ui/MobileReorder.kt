package app.qthrone.mobile.ui

import android.content.ClipData
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.doOnPreDraw

/** Reorder existing views in place. No database/render notifications until the gesture finishes. */
internal class MobileReorder(
    private val container: LinearLayout,
    private val scroll: View,
    private val active: (Boolean) -> Unit,
    private val save: (List<Long>) -> Unit,
    private val compatible: (Long, Long) -> Boolean = { _, _ -> true },
    // Lets the owner shrink tall items (expanded subscriptions) for the gesture and restore them afterwards.
    private val prepare: (View) -> Unit = {},
    private val release: (View) -> Unit = {},
) {
    private val ids=linkedMapOf<View,Long>()
    /** Profile rows: another group's [acceptFrom] zone may take the dragged item. */
    var movable=false
    private var source: View?=null
    private var original=emptyList<View>()
    private var accepted=false
    private val horizontal get() = container.orientation == LinearLayout.HORIZONTAL
    private var pointer=0f
    private var pendingMove=false
    private val scrollStep=object: Runnable {
        override fun run() {
            val dragged=source ?: return
            if(!container.isAttachedToWindow) { finish(false);return }
            val location=IntArray(2);container.getLocationOnScreen(location)
            val viewport=IntArray(2);scroll.getLocationOnScreen(viewport)
            val axis=if(horizontal) 0 else 1
            val position=location[axis]+pointer-viewport[axis]
            val edge=56*container.resources.displayMetrics.density
            val extent=if(horizontal) scroll.width else scroll.height
            val delta=when { position<edge -> -12; position>extent-edge -> 12; else -> 0 }
            if(delta!=0) {
                val before=if(horizontal) scroll.scrollX else scroll.scrollY
                val step=(delta*container.resources.displayMetrics.density).toInt()
                scroll.scrollBy(if(horizontal) step else 0,if(horizontal) 0 else step)
                pointer+=(if(horizontal) scroll.scrollX else scroll.scrollY)-before
                move(dragged,pointer)
            }
            container.postOnAnimation(this)
        }
    }

    init {
        container.setOnDragListener { _,event ->
            if(event.localState !== this) false else {
                when(event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_LOCATION -> {
                        pointer=if(horizontal) event.x else event.y
                        source?.let { move(it,pointer) }
                        true
                    }
                    DragEvent.ACTION_DROP -> { accepted=true;true }
                    DragEvent.ACTION_DRAG_ENDED -> { finish(accepted && event.result);true }
                    else -> true
                }
            }
        }
    }

    fun bind(handle: View, item: View, id: Long, shadowView: View = item) {
        ids[item]=id
        handle.setOnLongClickListener {
            if(source!=null) return@setOnLongClickListener true
            original=children()
            source=item;accepted=false
            handle.parent.requestDisallowInterceptTouchEvent(true)
            active(true)
            prepare(item)
            item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // Animate before taking the platform drag shadow; keep the original slot occupied.
            item.animate().scaleX(1.025f).scaleY(1.025f).setDuration(90).withEndAction {
                if(source!==item || !handle.isPressed) { finish(false);return@withEndAction }
                val shadow=object: View.DragShadowBuilder(shadowView) {
                    override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
                        size.set((shadowView.width*1.025f).toInt(),(shadowView.height*1.025f).toInt())
                        touch.set(size.x/2,size.y/2)
                    }
                    override fun onDrawShadow(canvas: android.graphics.Canvas) {
                        canvas.scale(1.025f,1.025f)
                        if(shadowView!==item) canvas.drawRoundRect(0f,0f,shadowView.width.toFloat(),shadowView.height.toFloat(),
                            5*container.resources.displayMetrics.density,5*container.resources.displayMetrics.density,
                            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=MobilePalette.surface })
                        super.onDrawShadow(canvas)
                    }
                }
                if(item.startDragAndDrop(ClipData.newPlainText("reorder",""),shadow,this,0)) {
                    item.visibility=View.INVISIBLE
                    item.scaleX=1f;item.scaleY=1f
                    val handleLocation=IntArray(2);handle.getLocationOnScreen(handleLocation)
                    val containerLocation=IntArray(2);container.getLocationOnScreen(containerLocation)
                    val axis=if(horizontal) 0 else 1
                    pointer=handleLocation[axis]-containerLocation[axis]+(if(horizontal) handle.width else handle.height)/2f
                    container.postOnAnimation(scrollStep)
                } else finish(false)
            }.start()
            true
        }
    }

    private fun children()=(0 until container.childCount).map { container.getChildAt(it) }

    private fun move(item: View,position: Float) {
        if(pendingMove) return
        val current=children()
        val from=current.indexOf(item)
        if(from<0) return
        val target=current.indices.lastOrNull {
            position > if(horizontal) current[it].left+current[it].width/2f else current[it].top+current[it].height/2f
        } ?: 0
        val to=target.coerceIn(0,current.lastIndex)
        if(to==from || !compatible(ids[item] ?: return,ids[current[to]] ?: return)) return
        val positions=current.associateWith { if(horizontal) it.left+it.translationX else it.top+it.translationY }
        current.forEach { it.animate().cancel() }
        container.removeView(item);container.addView(item,to)
        pendingMove=true
        container.doOnPreDraw {
            pendingMove=false
            if(source!==item) return@doOnPreDraw
            current.filter { it!==item }.forEach { view ->
                if(horizontal) {
                    view.translationX=(positions[view] ?: view.left.toFloat())-view.left
                    view.animate().translationX(0f).setDuration(160).start()
                } else {
                    view.translationY=(positions[view] ?: view.top.toFloat())-view.top
                    view.animate().translationY(0f).setDuration(160).start()
                }
            }
        }
    }

    /**
     * Lets an item dragged out of another movable list be dropped anywhere on [zone] (a whole group card,
     * so a collapsed or empty group takes it too); [receive] gets its id. The source list then restores its order.
     */
    fun acceptFrom(zone: View, canReceive: Boolean = true, onDrag: (View, DragEvent) -> Unit = { _, _ -> }, receive: (Long) -> Unit) {
        zone.setOnDragListener { _,event ->
            val state=event.localState as? MobileReorder
            if(state==null || (state===this && canReceive) || !state.movable) false else {
                onDrag(zone,event)
                when(event.action) {
                    DragEvent.ACTION_DRAG_ENTERED -> if(canReceive) zone.animate().alpha(.6f).setDuration(90).start()
                    DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DRAG_ENDED -> zone.animate().alpha(1f).setDuration(90).start()
                    DragEvent.ACTION_DRAG_LOCATION -> state.follow(zone,event.x,event.y)
                    DragEvent.ACTION_DROP -> {
                        zone.animate().alpha(1f).setDuration(90).start()
                        if(!canReceive) return@setOnDragListener false
                        state.source?.let { state.ids[it] }?.let(receive)
                    }
                }
                true
            }
        }
    }

    /** Keeps auto-scroll following the finger while it is over another list. */
    private fun follow(view: View,x: Float,y: Float) {
        val at=IntArray(2);view.getLocationOnScreen(at)
        val mine=IntArray(2);container.getLocationOnScreen(mine)
        val axis=if(horizontal) 0 else 1
        pointer=at[axis]+(if(horizontal) x else y)-mine[axis]
    }

    fun cancel() { finish(false) }
    fun prune() { ids.keys.retainAll(children().toSet()) }

    private fun finish(commit: Boolean) {
        val item=source ?: return
        source=null;container.removeCallbacks(scrollStep)
        container.parent?.requestDisallowInterceptTouchEvent(false)
        val order=children()
        val changed=order!=original
        if(!commit && order!=original) {
            original.forEachIndexed { index,view ->
                if(container.getChildAt(index)!==view && view.parent===container) {
                    container.removeView(view);container.addView(view,index)
                }
            }
        }
        children().forEach { it.animate().cancel();it.translationX=0f;it.translationY=0f }
        release(item)
        item.visibility=View.VISIBLE
        item.scaleX=1.025f;item.scaleY=1.025f
        item.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        original=emptyList()
        if(commit && changed) save(order.mapNotNull { ids[it] })
        else active(false)
    }

    companion object {
        fun cancelOnDrop(zone: View) {
            zone.setOnDragListener { _,event ->
                val state=event.localState as? MobileReorder ?: return@setOnDragListener false
                when(event.action) {
                    DragEvent.ACTION_DRAG_ENTERED -> zone.alpha=1f
                    DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DRAG_ENDED -> zone.alpha=.75f
                    DragEvent.ACTION_DROP -> state.cancel()
                }
                true
            }
        }
    }
}
