"""Downloads the detection models listed in models.sha256 and refuses any file whose hash differs."""
import hashlib
import pathlib
import sys
import urllib.request

dest = pathlib.Path(sys.argv[1])
dest.mkdir(parents=True, exist_ok=True)
for line in pathlib.Path(__file__).with_name("models.sha256").read_text().splitlines():
    if not line.strip():
        continue
    sha, name, url = line.split()
    target = dest / name
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != sha:
        data = urllib.request.urlopen(url, timeout=120).read()
        got = hashlib.sha256(data).hexdigest()
        if got != sha:
            sys.exit(f"{name}: sha256 {got} != expected {sha}")
        target.write_bytes(data)
    print("ok", name)
