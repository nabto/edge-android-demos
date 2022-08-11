package com.nabto.edge.nabtoheatpumpdemo

import androidx.lifecycle.*

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
