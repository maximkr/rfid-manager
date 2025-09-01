package com.trackstudio.rfidmanager;

import com.rscja.deviceapi.UhfBase;

public class ErrorCodeManager {
    public static String getMessage(int errorCode) {
        switch (errorCode) {
            case UhfBase.ErrorCode.ERROR_NO_TAG:
                return "No tag";
            case UhfBase.ErrorCode.ERROR_INSUFFICIENT_PRIVILEGES:
                return "Insufficient privileges";
            case UhfBase.ErrorCode.ERROR_MEMORY_OVERRUN:
                return "Memory overrun";
            case UhfBase.ErrorCode.ERROR_MEMORY_LOCK:
                return "Memory lock";
            case UhfBase.ErrorCode.ERROR_TAG_NO_REPLY:
                return "Tag no reply";
            case UhfBase.ErrorCode.ERROR_PASSWORD_IS_INCORRECT:
                return "Password is incorrect";
            case UhfBase.ErrorCode.ERROR_RESPONSE_BUFFER_OVERFLOW:
                return "Buffer overrun";
            case UhfBase.ErrorCode.ERROR_NO_ENOUGH_POWER_ON_TAG:
                return "No enough power on tag";
            case UhfBase.ErrorCode.ERROR_OPERATION_FAILED:
                return "Operation failed";
            default:
                return "Unknown error";

        }
    }
}


