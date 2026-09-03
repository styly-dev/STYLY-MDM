package com.styly.mdmclient

/** Pure state gate used by the Application-scoped coordinator. */
class PushExecutionGate {
    private var active: PushProtocol.Command? = null

    sealed class Decision {
        object Accepted : Decision()
        data class Duplicate(val command: PushProtocol.Command) : Decision()
        data class Busy(val active: PushProtocol.Command) : Decision()
        data class Conflict(val detail: String) : Decision()
    }

    fun restore(command: PushProtocol.Command?) {
        active = command
    }

    fun offer(command: PushProtocol.Command): Decision {
        val current = active
        if (current == null) {
            active = command
            return Decision.Accepted
        }
        // Legacy commands have no server-owned identity, so a second legacy command
        // is always treated as busy rather than guessed to be a duplicate.
        if (current.jobId == null || command.jobId == null) return Decision.Busy(current)
        if (current.jobId == command.jobId && current.attempt == command.attempt) {
            return if (current.sameExecution(command)) Decision.Duplicate(current)
            else Decision.Conflict("same identity carried different artifact, destination, or mode")
        }
        return Decision.Busy(current)
    }

    fun release(command: PushProtocol.Command): Boolean {
        if (active?.identity != command.identity) return false
        active = null
        return true
    }

    fun current(): PushProtocol.Command? = active
}
