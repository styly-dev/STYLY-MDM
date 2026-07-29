#!/usr/bin/env python3
from pathlib import Path

path = Path('.github/issue91_fix_and_verify.py')
text = path.read_text(encoding='utf-8')
old = '''    replace_between(
        path,
        "    async def clear_fence_on_process_replacement(\\n",
        "    async def active_assignment_for_device(\\n",
        clear_replacement,
    )
'''
new = '''    active_method = \'\'\'    async def active_assignment_for_device(self, device_id: str) -> dict[str, Any] | None:\\n        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:\\n            row = conn.execute(\\n                """\\n                SELECT d.*, j.artifact_id, j.mode, j.dest_path\\n                FROM push_job_devices d JOIN push_jobs j ON j.job_id=d.job_id\\n                WHERE d.device_id=? AND d.state IN (\\n                    \'waiting_transfer\',\'dispatching\',\'downloading\',\'validating\',\'applying\',\'reconciling\'\\n                )\\n                LIMIT 1\\n                """,\\n                (device_id,),\\n            ).fetchone()\\n            return dict(row) if row else None\\n\\n        return await self._call(op)\\n\\n\'\'\'
    replace_between(
        path,
        "    async def clear_fence_on_process_replacement(\\n",
        "    async def expired_reconciliations(\\n",
        clear_replacement + active_method,
    )
'''
if text.count(old) != 1:
    raise RuntimeError(f'expected one script block, found {text.count(old)}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
