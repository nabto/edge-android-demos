package com.nabto.edge.tunnelhttpdemo

import android.content.Context
import android.util.AttributeSet
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class NabtoSwipeRefreshLayout(context: Context, attrs: AttributeSet) : SwipeRefreshLayout(context, attrs) {
    var canChildScrollUpCallback: (() -> Boolean)? = null

    override fun canChildScrollUp(): Boolean {
        return canChildScrollUpCallback?.invoke() ?: super.canChildScrollUp()
    }
}