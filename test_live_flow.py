import urllib.request
import json
import time

url = "http://localhost:8080/api/v1/events"
headers = {
    "Content-Type": "application/json",
    "X-API-Key": "sentinel-dev-key-001"
}

payload = {
    "event_type": "order.completed",
    "source": "checkout-microservice",
    "data": {
        "order_id": f"ORD-LIVE-{int(time.time())}",
        "customer": "Alex Mercer",
        "amount": 1250.00
    }
}

req = urllib.request.Request(url, data=json.dumps(payload).encode('utf-8'), headers=headers, method="POST")

try:
    with urllib.request.urlopen(req) as resp:
        print(f"Status Code: {resp.status}")
        print("Response:", resp.read().decode('utf-8'))
except urllib.error.HTTPError as e:
    print(f"HTTP Error {e.code}:", e.read().decode('utf-8'))
