#!/usr/bin/env python3
from pathlib import Path


def replace_between(path: Path, start: str, end: str, replacement: str) -> None:
    text = path.read_text(encoding='utf-8')
    begin = text.find(start)
    finish = text.find(end, begin + len(start)) if begin >= 0 else -1
    if begin < 0 or finish < 0:
        raise RuntimeError(f'{path}: patch markers not found: {start!r} / {end!r}')
    path.write_text(text[:begin] + replacement + text[finish:], encoding='utf-8')


store_path = Path('mdm-server/styly_mdm/push_job_store.py')
clear_method = '''    async def clear_fence_on_process_replacement(
        self, device_id: str, new_process_instance_id: str | None, has_job_v1: bool
    ) -> list[dict[str, Any]]:
        if not has_job_v1 or not new_process_instance_id:
            return []

        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self._begin(conn)
            try:
                fence = conn.execute(
                    "SELECT * FROM push_device_fences WHERE device_id=?", (device_id,)
                ).fetchone()
                if fence is None or fence["blocking_process_instance_id"] in {None, new_process_instance_id}:
                    self._commit(conn)
                    return []
                affected = set(self._fence_visible_job_ids(conn, device_id))
                if fence["blocking_job_id"]:
                    affected.add(fence["blocking_job_id"])
                conn.execute("DELETE FROM push_device_fences WHERE device_id=?", (device_id,))
                timestamp = now_ms()
                for affected_job_id in sorted(affected):
                    self._increment_revision(conn, affected_job_id, timestamp)
                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]
                self._commit(conn)
                return snapshots
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

'''
replace_between(
    store_path,
    '    async def clear_fence_on_process_replacement(\n',
    '    async def active_assignment_for_device(\n',
    clear_method,
)

script_path = Path('.github/issue91_fix_and_verify.py')
script = script_path.read_text(encoding='utf-8')
old = '''    replace_between(
        path,
        "    async def clear_fence_on_process_replacement(\\n",
        "    async def active_assignment_for_device(\\n",
        clear_replacement,
    )
'''
if script.count(old) != 1:
    raise RuntimeError(f'expected one bulk-script clear-fence block, found {script.count(old)}')
script_path.write_text(
    script.replace(old, '    # clear_fence_on_process_replacement was patched before bulk apply.\n', 1),
    encoding='utf-8',
)
