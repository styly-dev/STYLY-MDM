package com.styly.mdmclient

/** Atomically binds registration flags to the socket that sent REGISTER. */
internal class ConnectionRegistration<T : Any> {
    private var socket: T? = null
    private var sent = false
    private var acknowledged = false

    @Synchronized fun open(token: T) {
        socket = token
        sent = false
        acknowledged = false
    }

    @Synchronized fun clear() {
        socket = null
        sent = false
        acknowledged = false
    }

    @Synchronized fun markSent(token: T) {
        if (socket === token) sent = true
    }

    @Synchronized fun clearSent(token: T) {
        if (socket === token) {
            sent = false
            acknowledged = false
        }
    }

    @Synchronized fun isSent(token: T): Boolean = socket === token && sent

    @Synchronized fun acknowledge(token: T): Boolean {
        if (socket !== token || !sent) return false
        acknowledged = true
        return true
    }

    @Synchronized fun isAcknowledged(token: T?): Boolean =
        token != null && socket === token && acknowledged
}
