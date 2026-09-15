package com.styly.mdmclient

/** Atomically binds registration flags to the socket that sent REGISTER. */
internal class ConnectionRegistration<T : Any> {
    private enum class Acknowledgement {
        NONE,
        PROVISIONAL,
        CANONICAL,
    }

    private var socket: T? = null
    private var sent = false
    private var acknowledgement = Acknowledgement.NONE
    private var provisionalConnectionId: String? = null

    @Synchronized fun open(token: T) {
        socket = token
        sent = false
        acknowledgement = Acknowledgement.NONE
        provisionalConnectionId = null
    }

    @Synchronized fun clear() {
        socket = null
        sent = false
        acknowledgement = Acknowledgement.NONE
        provisionalConnectionId = null
    }

    @Synchronized fun markSent(token: T) {
        if (socket === token) sent = true
    }

    @Synchronized fun clearSent(token: T) {
        if (socket === token) {
            sent = false
            acknowledgement = Acknowledgement.NONE
            provisionalConnectionId = null
        }
    }

    @Synchronized fun isSent(token: T): Boolean = socket === token && sent

    @Synchronized fun acknowledge(token: T): Boolean {
        if (socket !== token || !sent) return false
        acknowledgement = Acknowledgement.CANONICAL
        provisionalConnectionId = null
        return true
    }

    @Synchronized fun acknowledgeProvisional(token: T, connectionId: String): Boolean {
        if (socket !== token || connectionId.isBlank() ||
            acknowledgement == Acknowledgement.CANONICAL
        ) return false
        acknowledgement = Acknowledgement.PROVISIONAL
        provisionalConnectionId = connectionId
        return true
    }

    @Synchronized fun isAcknowledged(token: T?): Boolean =
        token != null && socket === token && acknowledgement != Acknowledgement.NONE

    @Synchronized fun isCanonicalAcknowledged(token: T?): Boolean =
        token != null && socket === token && acknowledgement == Acknowledgement.CANONICAL

    @Synchronized fun isProvisional(token: T?): Boolean =
        token != null && socket === token && acknowledgement == Acknowledgement.PROVISIONAL

    @Synchronized fun provisionalConnectionId(token: T?): String? =
        if (token != null && socket === token && acknowledgement == Acknowledgement.PROVISIONAL) {
            provisionalConnectionId
        } else {
            null
        }
}
