package app.rawline.mobile.ktx

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FixedLinearLayoutManager(val recyclerView: RecyclerView) :
    LinearLayoutManager(recyclerView.context, RecyclerView.VERTICAL, false) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

    override fun supportsPredictiveItemAnimations(): Boolean {
        return false
    }

}

class FixedGridLayoutManager(val recyclerView: RecyclerView, spanCount: Int) :
    GridLayoutManager(recyclerView.context, spanCount) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

    // Rows sorted in from off screen slide into place instead of popping in.
    override fun supportsPredictiveItemAnimations(): Boolean {
        return true
    }

    fun rowIndexOf(position: Int): Int =
        position / spanCount

}
