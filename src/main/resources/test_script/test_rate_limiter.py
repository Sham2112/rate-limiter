import sys
import time
import urllib.error
import urllib.request

BASE_URL = "http://localhost:8080"
ENDPOINT = "/api/info"

# Must match application.properties (Leaking Bucket rate limiter)
LIMIT = 10          # leakingBucket.queueSize  — bucket capacity = burst allowed before rejecting
LEAK_SIZE = 5       # leakingBucket.noOfRequests — slots freed each interval
INTERVAL_MS = 2000  # leakingBucket.interval     — how often the bucket leaks


def send_request(n, client_ip="10.0.0.1"):
    """Send a request, identifying the caller via X-Forwarded-For.

    The server's NetworkUtils.getClientIp reads X-Forwarded-For first, so setting it
    lets us simulate distinct clients from one machine — and makes the client key
    deterministic (otherwise localhost shows up as the IPv6 loopback ::1)."""
    req = urllib.request.Request(f"{BASE_URL}{ENDPOINT}")
    req.add_header("X-Forwarded-For", client_ip)
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception as e:
        print(f"  Request {n} failed: {e}")
        return None


def test_rate_limiting(client_ip="10.0.0.1"):
    total = LIMIT + 10
    print(f"[1] Rate limiting — sending {total} requests as client {client_ip}")
    print(f"    Expect first {LIMIT} to return 200 (bucket fills), remainder 429\n")

    results = {200: 0, 429: 0, "other": 0}
    first_429 = None

    for i in range(1, total + 1):
        code = send_request(i, client_ip)
        if code is None:
            print("    ERROR: Could not reach the server. Is the app running on :8080?")
            return False
        if code == 200:
            results[200] += 1
        elif code == 429:
            results[429] += 1
            if first_429 is None:
                first_429 = i
        else:
            results["other"] += 1

    print(f"    200 OK: {results[200]}   429 Too Many: {results[429]}"
          + (f"   Other: {results['other']}" if results["other"] else ""))
    if first_429 is not None:
        print(f"    First 429 at request #{first_429} (expected ~{LIMIT + 1})")

    if results[429] > 0:
        print("    PASS: rate limiter is blocking once the bucket is full\n")
        return True
    print("    FAIL: no requests were blocked\n")
    return False


def test_leak_recovery(client_ip="10.0.0.1"):
    """After the bucket is full, wait one leak interval and verify capacity returns.

    A leaking bucket frees only noOfRequests slots per interval (unlike a fixed window,
    which restores the whole allowance at once), so a follow-up burst of LEAK_SIZE should
    be admitted again."""
    wait_s = (INTERVAL_MS / 1000) + 0.5
    print(f"[2] Leak recovery — waiting {wait_s}s for the bucket to leak ~{LEAK_SIZE} slots (client {client_ip})")
    time.sleep(wait_s)

    passed = sum(1 for i in range(LEAK_SIZE) if send_request(i, client_ip) == 200)

    if passed == LEAK_SIZE:
        print(f"    PASS: all {passed}/{LEAK_SIZE} admitted after the bucket leaked\n")
    elif passed > 0:
        print(f"    PARTIAL: {passed}/{LEAK_SIZE} admitted (fewer slots leaked than expected)\n")
    else:
        print("    FAIL: nothing admitted after waiting — did the leak scheduler start?\n")


def test_client_isolation():
    """Two different client IPs must get independent limiters (the cache keys per IP).

    Exhausting client A must not affect client B."""
    a, b = "172.16.0.1", "172.16.0.2"
    print(f"[3] Client isolation — exhausting client {a}, then checking client {b} is unaffected")

    # Fill A's bucket fast (well under one leak interval) so it stays full.
    for i in range(LIMIT + 5):
        send_request(i, a)

    a_code = send_request(99, a)   # A is over its limit → should be blocked
    b_code = send_request(99, b)   # B is brand new → should be allowed

    print(f"    client A ({a}) next request: {a_code} (expected 429)")
    print(f"    client B ({b}) next request: {b_code} (expected 200)")

    if a_code == 429 and b_code == 200:
        print("    PASS: clients are rate-limited independently\n")
    else:
        print("    FAIL: clients are not isolated — check the cache key (per-IP)\n")


def test_eviction():
    """Opt-in, slow. Verifies idle limiters are evicted and their schedulers stopped.

    This is NOT a pure HTTP assertion — eviction + thread cleanup are internal, and every
    algorithm also recovers on its own over time, so you confirm it via the SERVER LOG.

    Requires application.properties: cache.expireAfter=1  (1 minute, the smallest unit).
    """
    client = "203.0.113.7"  # a fresh, unique IP
    print("[4] Eviction (slow) — requires cache.expireAfter=1 in application.properties")
    print(f"    Creating a limiter for {client} ...")
    send_request(1, client)
    print("    -> watch the server console for: [Cache] creating limiter for ip=203.0.113.7")

    wait_s = 70  # 1-minute TTL + margin; systemScheduler evicts without further traffic
    print(f"    Idling {wait_s}s so the entry passes its TTL (no traffic to this client)...")
    time.sleep(wait_s)

    print("    -> by now the server should have logged: [Cache] evicting limiter ... cause=EXPIRED")
    print(f"    Sending one more request as {client} (should log a NEW 'creating limiter' line)...")
    send_request(2, client)
    print("    If you see a second 'creating limiter' line for this IP, the old entry was")
    print("    evicted and its scheduler thread shut down. PASS is confirmed via the log.\n")


if __name__ == "__main__":
    print("=== Rate Limiter Test (Leaking Bucket) ===\n")
    if test_rate_limiting():
        test_leak_recovery()
        test_client_isolation()

    if "--evict" in sys.argv:
        test_eviction()
    else:
        print("Tip: run with --evict to also exercise cache eviction")
        print("     (set cache.expireAfter=1 first, then watch the server log).")
