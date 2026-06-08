import urllib.request
import urllib.error
import time

BASE_URL = "http://localhost:8080"
ENDPOINT = "/api/info"

# Must match application.properties
BUCKET_SIZE = 10
REFILL_INTERVAL_MS = 2000
REFILL_SIZE = 2


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
    total = BUCKET_SIZE + 10
    print(f"Sending {total} requests to {BASE_URL}{ENDPOINT}")
    print(f"Expect first {BUCKET_SIZE} to return 200, remainder to return 429\n")

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
        print(f"First 429 at request #{first_429} (expected ~{BUCKET_SIZE + 1})")
    else:
        print("No 429s received — rate limiter is NOT triggering")

    if results[429] > 0:
        print("\nPASS: Rate limiter is working")
        return True
    else:
        print("\nFAIL: Rate limiter did not block any requests")
        return False


def test_refill():
    """After exhausting the bucket, wait one refill interval and verify some requests go through again."""
    wait_s = (REFILL_INTERVAL_MS / 1000) + 0.5  # one interval plus a small margin
    print(f"\n--- Refill test: waiting {wait_s}s for ~{REFILL_SIZE} tokens to replenish ---")
    time.sleep(wait_s)

    passed = 0
    for i in range(1, 6):
        if send_request(i) == 200:
            passed += 1

    if passed > 0:
        print(f"PASS: {passed}/5 requests allowed after refill (expected ~{REFILL_SIZE})")
    else:
        print("FAIL: No requests allowed after refill wait")


if __name__ == "__main__":
    print("=== Rate Limiter Test ===\n")
    if test_rate_limiting():
        test_refill()
