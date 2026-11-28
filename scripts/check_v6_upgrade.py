"""Capture or compare the installed emulator profile around an in-place upgrade."""
import argparse
from contextlib import closing
import hashlib
import json
import sqlite3
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("adb")
parser.add_argument("serial")
parser.add_argument("snapshot", type=Path)
parser.add_argument("--compare", action="store_true")
args = parser.parse_args()
adb = [args.adb, "-s", args.serial]
subprocess.run(adb + ["shell", "am", "force-stop", "org.lexora.study"], check=True)
with tempfile.TemporaryDirectory() as tmp:
    database = Path(tmp) / "profile.db"
    database.write_bytes(subprocess.check_output(adb + ["exec-out", "run-as", "org.lexora.study", "cat", "databases/lexora.db"]))
    with closing(sqlite3.connect(database)) as db:
        assert db.execute("pragma integrity_check").fetchone() == ("ok",)
        assert not db.execute("pragma foreign_key_check").fetchall()
        version = db.execute("pragma user_version").fetchone()[0]
        result = {}
        for table in ["word", "card", "event", "pref", "exploration", "content_v4", "deck", "favorite", "undo_step", "synced"]:
            rows = db.execute("select * from "+table+" order by rowid").fetchall()
            result[table] = [len(rows), hashlib.sha256(json.dumps(rows).encode()).hexdigest()]
if args.compare:
    old = json.loads(args.snapshot.read_text())
    assert old["tables"] == result, [k for k in result if result[k] != old["tables"][k]]
    assert version == 6 and old["version"] == 5
else:
    args.snapshot.write_text(json.dumps({"version": version, "tables": result}, indent=2))
print("DB", version, {k: v[0] for k, v in result.items()})
