package com.trackstudio.rfidmanager

import com.rscja.deviceapi.UhfBase

object ErrorCodeManager {
    fun getMessage(errorCode: Int): String {
        return when (errorCode) {
            UhfBase.ErrorCode.ERROR_NO_TAG -> "No tag"
            UhfBase.ErrorCode.ERROR_INSUFFICIENT_PRIVILEGES -> "Insufficient privileges"
            UhfBase.ErrorCode.ERROR_MEMORY_OVERRUN -> "Memory overrun"
            UhfBase.ErrorCode.ERROR_MEMORY_LOCK -> "Memory lock"
            UhfBase.ErrorCode.ERROR_TAG_NO_REPLY -> "Tag no reply"
            UhfBase.ErrorCode.ERROR_PASSWORD_IS_INCORRECT -> "Password is incorrect"
            UhfBase.ErrorCode.ERROR_RESPONSE_BUFFER_OVERFLOW -> "Buffer overrun"
            UhfBase.ErrorCode.ERROR_NO_ENOUGH_POWER_ON_TAG -> "No enough power on tag"
            UhfBase.ErrorCode.ERROR_OPERATION_FAILED -> "Operation failed"
            else -> "Unknown error"
        }
    }
}
