"""Unauthenticated reachability check. No keys, camera images, inference or retry loop."""
import http.client
import json
import time
from concurrent.futures import ThreadPoolExecutor


def probe(method, path):
    events = []
    start = time.perf_counter()
    connection = http.client.HTTPSConnection("generativelanguage.googleapis.com", timeout=10)
    def mark(stage):
        events.append({"stage": stage, "atMs": round((time.perf_counter()-start)*1000)})
    result = {"method": method, "path": path, "authentication": "none", "device": "Windows desktop, not Vivo", "events": events}
    try:
        connection.connect(); mark("connected")
        body = json.dumps({"contents": [{"parts": [{"text": "Reply OK."}]}]}) if method == "POST" else None
        connection.request(method, path, body, {"Content-Type": "application/json"})
        mark("request_sent")
        response = connection.getresponse(); mark("response_headers")
        result["httpCode"] = response.status
        response.read(4096)
        result["status"] = "response_received"
    except Exception as error:
        result["status"] = "request_failed"
        result["errorType"] = type(error).__name__
    finally:
        connection.close()
        result["elapsedMs"] = round((time.perf_counter()-start)*1000)
    return result


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda args: probe(*args), [
            ("GET", "/v1beta/models"), ("POST", "/v1beta/models/gemini-3.8-flash:generateContent")]))
    print(json.dumps(results, indent=2))
