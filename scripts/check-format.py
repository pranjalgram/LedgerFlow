"""Check repository text hygiene without rewriting source or platform line endings."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
extensions = {".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".mjs", ".py", ".json", ".md", ".yaml", ".yml", ".sql", ".properties"}
errors = []
for name in filter(None, paths):
    path = root / name
    if path.suffix not in extensions or not path.is_file():
        continue
    value = path.read_text(encoding="utf-8")
    if value and not value.endswith("\n"):
        errors.append(f"{name}: missing final newline")
    for number, line in enumerate(value.splitlines(), 1):
        if line.rstrip() != line:
            errors.append(f"{name}:{number}: trailing whitespace")
        if "\t" in line:
            errors.append(f"{name}:{number}: tab indentation")
if errors:
    print("\n".join(errors))
    sys.exit(1)
print("Tracked source/document text hygiene passed.")
