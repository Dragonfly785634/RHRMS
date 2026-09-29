"""Reads one value out of a JSON document on stdin.

    echo '{"a":{"b":[1,2]}}' | python3 pick.py a.b.1     ->  2

A path segment that is all digits indexes a list. A missing path prints nothing and exits 1, so
the shell can tell "absent" from "empty string".
"""
import json
import sys

try:
    # parse_float=str keeps a number exactly as the server wrote it. Without this, the API's
    # 150.00 comes back as Python's 150.0 and an assertion about money looks like a bug in the
    # server when it is really a bug in the test.
    doc = json.load(sys.stdin, parse_float=str)
except Exception:
    sys.exit(1)

path = sys.argv[1] if len(sys.argv) > 1 else ""
if path:
    for part in path.split("."):
        try:
            doc = doc[int(part)] if part.lstrip("-").isdigit() else doc[part]
        except (KeyError, IndexError, TypeError):
            sys.exit(1)

if doc is None:
    print("")
elif isinstance(doc, bool):
    print("true" if doc else "false")
elif isinstance(doc, (dict, list)):
    print(json.dumps(doc))
else:
    print(doc)
