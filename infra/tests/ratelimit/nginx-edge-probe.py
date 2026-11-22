"""Exercise the real edge over HTTP inside an isolated Docker network."""
from concurrent.futures import ThreadPoolExecutor
import sys
import time
from threading import Barrier
import urllib.error
import urllib.request

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def request(path, address):
    req = urllib.request.Request(
        "http://edge:8080" + path,
        data=b"{}", headers={"X-Real-IP": address, "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with opener.open(req, timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def burst(path, addresses):
    assert len(addresses) % 64 == 0
    barrier = Barrier(64, timeout=15)
    def synchronized_request(address):
        barrier.wait()
        return request(path, address)
    with ThreadPoolExecutor(max_workers=64) as pool:
        result = list(pool.map(synchronized_request, addresses))
    assert set(result) <= {204, 429}, result
    return result.count(204), result.count(429)


mode = sys.argv[1]
if mode == "ready":
    for attempt in range(20):
        try:
            with opener.open("http://edge:8080/healthz", timeout=2) as response:
                assert response.status == 200
            sys.exit(0)
        except OSError:
            if attempt == 19:
                raise
            time.sleep(0.25)
elif mode == "untrusted":
    # Spoofed values must not change the limiter identity.
    allowed, blocked = burst("/api/auth/login", [f"198.51.100.{i}" for i in range(1, 65)])
    assert allowed > 0 and blocked > allowed, (allowed, blocked)
elif mode == "trusted":
    # Separate identities must still hit the shared edge limit.
    allowed, blocked = burst("/api/auth/login", [f"203.0.113.{i}" for i in range(1, 129)])
    assert allowed > 11 and blocked > 0, (allowed, blocked)
    allowed, blocked = burst("/api/auth/refresh", ["192.0.2.42"] * 64)
    assert allowed > 0 and blocked > allowed, (allowed, blocked)
else:
    raise ValueError(mode)
# Non-auth API traffic is not charged by either auth bucket.
assert request("/api/other", "192.0.2.42") == 204
print(f"PASS: {mode} edge limits and unrelated API")
