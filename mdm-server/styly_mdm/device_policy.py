"""Command policy shared by target selection and final socket dispatch."""


class CommandNotAllowedError(ConnectionResetError):
    """The socket is connected but no longer eligible for this command."""


PROVISIONAL_POWER_CAPABILITY = "provisional_power_control_v1"
PROVISIONAL_POWER_COMMANDS = frozenset({
    "EXECUTE_REBOOT",
    "EXECUTE_POWER_OFF",
})


def command_allowed(entry: dict | None, command: str | None = None) -> bool:
    """Missing registration fields never grant access; provisional owners get power only."""
    if entry is None or entry.get("registration_ready") is not True:
        return False

    identity_kind = entry.get("identity_kind")
    if identity_kind == "canonical":
        return True
    if identity_kind == "legacy":
        return command == "EXECUTE_INSTALL"
    if identity_kind == "provisional":
        capabilities = entry.get("capabilities")
        return (
            command in PROVISIONAL_POWER_COMMANDS
            and isinstance(capabilities, (list, tuple, set, frozenset))
            and PROVISIONAL_POWER_CAPABILITY in capabilities
        )
    return False
