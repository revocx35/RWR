"""Recovery CLI, run inside the container:

    docker compose exec rwr python -m rwr_server.admin reset-password

Sets a new random admin password (must be changed at next login), turns off 2FA, signs out all sessions
and clears login lockouts.
"""
from __future__ import annotations

import secrets
import sys
import time

from . import security
from .config import Config
from .db import DB


def main(argv: list[str]) -> int:
    if argv[:1] != ["reset-password"]:
        print(__doc__)
        return 2
    db = DB(Config().db_path)
    password = secrets.token_urlsafe(18)
    db.execute(
        "UPDATE admin SET password_hash = ?, must_change = 1, totp_secret = NULL, totp_enabled = 0, "
        "password_changed_at = ? WHERE id = 1",
        (security.hash_password(password), time.time()),
    )
    security.delete_all_sessions(db)
    db.execute("DELETE FROM throttle")
    db.audit("cli", "password_reset")
    print(f"New admin password: {password}\n2FA is off. Change the password after signing in.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
