package com.trackstudio.rfidmanager

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    private val _isConnected = MutableLiveData<Boolean>(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _statusText = MutableLiveData<String>("Disconnected")
    val statusText: LiveData<String> = _statusText

    fun setConnectionStatus(connected: Boolean) {
        _isConnected.postValue(connected)
        _statusText.postValue(if (connected) "Connected" else "Disconnected")
    }
}
