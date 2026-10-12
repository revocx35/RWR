"""Tiny Chrome DevTools client for testing the admin web UI in the emulator's Chrome.

Setup: adb forward tcp:9222 localabstract:chrome_devtools_remote ; pip install websocket-client
Usage: python chrome_cdp.py <target-id from localhost:9222/json> <CDP method> [json params]
"""
import json, sys, urllib.request
import websocket
tid, method = sys.argv[1], sys.argv[2]
params = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
targets = json.load(urllib.request.urlopen("http://localhost:9222/json"))
ws = websocket.create_connection(next(t for t in targets if t["id"] == tid)["webSocketDebuggerUrl"], suppress_origin=True)
ws.send(json.dumps({"id": 1, "method": method, "params": params}))
while True:
    m = json.loads(ws.recv())
    if m.get("id") == 1:
        print(json.dumps(m.get("result", m), indent=1)[:3000]); break
