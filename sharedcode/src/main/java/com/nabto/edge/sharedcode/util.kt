package com.nabto.edge.sharedcode

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.navigation.NavController
import com.google.android.material.snackbar.Snackbar

/**
 * LiveEvent is similar to LiveData except it wont resend the latest event to new observers.
 */
interface LiveEvent<T> {
    fun observe(owner: LifecycleOwner, obs: Observer<T>)
}

class MutableLiveEvent<T> : LiveEvent<T> {
    private val observers = mutableListOf<Observer<T>>()

    override fun observe(owner: LifecycleOwner, obs: Observer<T>) {
        observers.add(obs)
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                observers.remove(obs)
            }
        })
    }

    fun postEvent(event: T) {
        observers.forEach { obs ->
            obs.onChanged(event)
        }
    }
}

/**
 * Convenience function for making snackbars.
 * @param[msg] the message to display in the snackbar.
 * @param[duration] the duration that the snackbar will be visible for.
 */
fun View.snack(
    msg: String,
    duration: Int = Snackbar.LENGTH_LONG
): Snackbar {
    val snack = Snackbar.make(this, msg, duration)
    snack.show()
    return snack
}

/**
 * Convenience function for having a fragment clear its current focus and hiding the keyboard
 * if it is visible.
 */
fun Fragment.clearFocusAndHideKeyboard() {
    val activity = requireActivity()
    val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
    val view = activity.currentFocus
    if (view != null) {
        view.clearFocus()
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}

/**
 * This function is meant to achieve the same as calling navigate with inclusive popUpTo
 * Using navigate in that way seems to be currently bugged, so we have our own
 * slightly hacky alternative here.
 */
fun NavController.navigateAndPopUpToRoute(route: String, inclusive: Boolean = false) {
    val entry = backQueue.indexOfLast { it.destination.route == route }
    if (entry != -1) {
        val n = backQueue.size - entry - (if (inclusive) 0 else 1)
        repeat(n) {
            popBackStack()
        }
    } else {
        navigate(route = route)
    }
}
