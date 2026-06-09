import urllib.request
import urllib.error
import time

BASE_URL = "http://localhost:8080"
ENDPOINT = "/api/info"

# Must match application.properties (Leaking Bucket rate limiter)
LIMIT = 10          # leakingBucket.queueSize  — bucket capacity = burst allowed before rejecting
LEAK_SIZE = 5       # leakingBucket.noOfRequests — slots freed each interval
INTERVAL_MS = 2000  # leakingBucket.interval     — how often the bucket leaks


def send_request(n):
    try:
        with urllib.request.urlopen(f"{BASE_URL}{ENDPOINT}", timeout=3) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception as e:
        print(f"  Request {n} failed: {e}")
        return None


def test_rate_limiting():
    total = LIMIT + 10
    print(f"Sending {total} requests to {BASE_URL}{ENDPOINT}")
    print(f"Expect first {LIMIT} to return 200 (bucket fills), remainder to return 429\n")

    results = {200: 0, 429: 0, "other": 0}
    first_429 = None

    for i in range(1, total + 1):
        code = send_request(i)
        if code is None:
            print("\nERROR: Could not reach the server. Is the app running on :8080?")
            return False
        if code == 200:
            results[200] += 1
        elif code == 429:
            results[429] += 1
            if first_429 is None:
                first_429 = i
        else:
            results["other"] += 1

    print("Results:")
    print(f"  200 OK      : {results[200]}")
    print(f"  429 Too Many: {results[429]}")
    if results["other"]:
        print(f"  Other       : {results['other']}")
    print()

    if first_429 is not None:
        print(f"First 429 at request #{first_429} (expected ~{LIMIT + 1})")
    else:
        print("No 429s received — rate limiter is NOT triggering")

    if results[429] > 0:
        print("\nPASS: Rate limiter is working")
        return True
    else:
        print("\nFAIL: Rate limiter did not block any requests")
        return False


def test_leak_recovery():
    """After the bucket is full, wait one leak interval and verify capacity comes back.

    Unlike a fixed window (whole allowance resets at once), a leaking bucket frees only
    noOfRequests slots per interval. After one interval ~LEAK_SIZE slots open up, so a
    follow-up burst of LEAK_SIZE requests should be admitted again.
    """
    wait_s = (INTERVAL_MS / 1000) + 0.5  # one leak interval plus a small margin
    print(f"\n--- Leak recovery test: waiting {wait_s}s for the bucket to leak ~{LEAK_SIZE} slots ---")
    time.sleep(wait_s)

    passed = 0
    for i in range(1, LEAK_SIZE + 1):
        if send_request(i) == 200:
            passed += 1

    if passed == LEAK_SIZE:
        print(f"PASS: all {passed}/{LEAK_SIZE} requests admitted after the bucket leaked")
    elif passed > 0:
        print(f"PARTIAL: {passed}/{LEAK_SIZE} admitted (fewer slots leaked than expected)")
    else:
        print("FAIL: no requests admitted after waiting — did the leak scheduler start?")


if __name__ == "__main__":
    print("=== Rate Limiter Test (Leaking Bucket) ===\n")
    if test_rate_limiting():
        test_leak_recovery()
