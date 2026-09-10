"""Command policy shared by target selection and final socket dispatch."""


def command_allowed(entry: dict | None, command: str | None = None) -> bool:
    """Missing registration fields never grant access; legacy owners get APKs only."""
    return bool(
        entry is not None
        and entry.get("registration_ready") is True
        and (
            entry.get("identity_kind") == "canonical"
            or (entry.get("identity_kind") == "legacy" and command == "EXECUTE_INSTALL")
        )
    )
