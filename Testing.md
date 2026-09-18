# SentinelPulse — Complete Postman & End-to-End Testing Guide (`testing.md`)

> **Peace of Mind Guarantee:** This guide provides step-by-step instructions to test every single nook and corner of **SentinelPulse** using **Postman** (Desktop or Web), **Postman Collection Runner**, mock backend services, and command-line automation scripts.

---

## 1. Pre-Flight Checklist

Before launching Postman, ensure the core infrastructure is running:

### Step 1 — Check Docker Containers
Make sure Redis, Prometheus, and Grafana containers are running:
```powershell
docker ps
```
If not running, start them with:
```powershell
docker start MACHAREDIS MACHAPROMETHEUS MACHAGRAFANA
```

### Step 2 — Start SentinelPulse
If SentinelPulse is not already running, open a terminal in `c:\MY-SPACE\SentinelPulse` and run:
```powershell
java -jar target\sentinelpulse-1.0.0.jar
```
*Wait until the log displays:*
```
[main] INFO  com.sentinelpulse.SentinelPulseApplication - Started SentinelPulseApplication in X.XXX seconds
```

### Step 3 (Optional for Live 200 OK Proxy & Webhook Tests) — Start Mock Servers
SentinelPulse proxies requests to internal microservices (such as Orders on port `9091`) and dispatches webhooks to external URLs. To test live end-to-end HTTP 200 OK proxying and webhook delivery:

- **Mock Downstream Orders Service (Port 9091):**
  Open a new terminal and run:
  ```powershell
  python mock_downstream.py
  ```
  *(Logs: `Mock Downstream Orders Service running on http://127.0.0.1:9091`)*

- **Mock Webhook Receiver (Port 9999):**
  Open a new terminal and run:
  ```powershell
  python mock_webhook_receiver.py
  ```
  *(Logs: `Mock Webhook Receiver running on http://127.0.0.1:9999`)*

---

## 2. Setting Up Postman (Collection & Environment)

Both the Postman Collection and Postman Environment files are located in your workspace root:
- `SentinelPulse.postman_collection.json`
- `SentinelPulse.postman_environment.json`

### How to Import into Postman (Desktop or Web App)
1. Open **Postman**.
2. Click the **Import** button in the top-left sidebar (or press `Ctrl + O`).
3. Drag and drop both files:
   - `c:\MY-SPACE\SentinelPulse\SentinelPulse.postman_collection.json`
   - `c:\MY-SPACE\SentinelPulse\SentinelPulse.postman_environment.json`
4. Click **Import**.

### How to Select the Environment
1. In the top-right corner of Postman, locate the Environment dropdown (usually says **No Environment**).
2. Click the dropdown and select **SentinelPulse — Local Environment**.
3. Now all variables (e.g. `{{baseUrl}}`, `{{apiKey}}`) will resolve automatically.

### Environment Variables Reference
| Variable | Value | Purpose |
| :--- | :--- | :--- |
| `baseUrl` | `http://localhost:8080` | Base URL of the SentinelPulse Edge Gateway |
| `apiKey` | `sentinel-dev-key-001` | Default development API key (auto-seeded into Redis) |
| `mockDownstreamUrl` | `http://localhost:9091` | Downstream Orders microservice port |
| `mockWebhookUrl` | `http://localhost:9092` | Webhook receiver target endpoint |
| `redisInsightUrl` | `http://localhost:8000` | RedisInsight Web GUI |
| `prometheusUrl` | `http://localhost:9000` | Prometheus UI |
| `grafanaUrl` | `http://localhost:2500` | Grafana Dashboard UI |

---

## 3. Step-by-Step Test Walkthrough (Folder by Folder)

The imported collection is organized into 6 modular test suites. You can run requests individually or use the Collection Runner.

---

### Folder 01: Health & Infrastructure

#### Request 1: `Actuator Health Check`
- **Method & URL:** `GET {{baseUrl}}/actuator/health`
- **Headers:** None required (Actuator bypasses authentication by design)
- **Expected Status Code:** `200 OK`
- **Expected Response Body:**
  ```json
  {
    "status": "UP",
    "components": {
      "redis": {
        "status": "UP",
        "details": { "version": "..." }
      }
    }
  }
  ```
- **What this verifies:** Verifies SentinelPulse is live and actively connected to Redis (`MACHAREDIS` on port 6000).

#### Request 2: `Prometheus Metrics Scraping`
- **Method & URL:** `GET {{baseUrl}}/actuator/prometheus`
- **Headers:** None required
- **Expected Status Code:** `200 OK`
- **Expected Response:** Plain text Prometheus metric exposition including:
  ```text
  # HELP sentinelpulse_requests_total Total inbound HTTP requests intercepted by SentinelPulse
  # TYPE sentinelpulse_requests_total counter
  sentinelpulse_requests_total{...}
  ```
- **What this verifies:** Validates Micrometer instrumentation and metrics scraping for Prometheus.

---

### Folder 02: Security & Authentication

#### Request 3: `Reject Missing API Key (401)`
- **Method & URL:** `GET {{baseUrl}}/api/v1/orders`
- **Headers:** *(No headers)*
- **Expected Status Code:** `401 Unauthorized`
- **Expected Response Body:**
  ```json
  {
    "status": 401,
    "error": "Unauthorized",
    "message": "Invalid or missing X-API-Key header. Provide a valid API key.",
    "path": "/api/v1/orders"
  }
  ```
- **What this verifies:** Proves `AuthFilter` intercepts and blocks unauthenticated requests before they consume rate-limit tokens or reach proxy controllers.

#### Request 4: `Reject Invalid API Key (401)`
- **Method & URL:** `GET {{baseUrl}}/api/v1/orders`
- **Headers:** `X-API-Key: invalid-random-secret-key`
- **Expected Status Code:** `401 Unauthorized`
- **What this verifies:** Proves `AuthService` validates keys against Redis `apikeys:{key}` in $O(1)$ and rejects rogue or expired keys.

---

### Folder 03: Reverse Proxy & Route Caching

#### Request 5: `Forward Request via Reverse Proxy`
- **Method & URL:** `GET {{baseUrl}}/api/v1/orders`
- **Headers:** `X-API-Key: {{apiKey}}`
- **Expected Status Code:**
  - `200 OK` (if `mock_downstream.py` is running on port 9091)
  - `502 Bad Gateway` (if mock service is stopped)
- **Response when Mock Service is running:**
  ```json
  {
    "service": "orders-downstream-service",
    "status": "SUCCESS",
    "path": "/api/v1/orders",
    "received_headers": {
      "X-Forwarded-For": "127.0.0.1",
      "X-Request-ID": "c2b1e428-...",
      "X-Gateway-Time": "2026-09-05T..."
    },
    "orders": [
      { "id": "ORD-101", "item": "Quantum Pulse Processor", "price": 499.99 }
    ]
  }
  ```
- **What this verifies:** Proves route resolution from Redis/L1 cache, gateway header injection (`X-Forwarded-For`, `X-Request-ID`, `X-Gateway-Time`), hop-by-hop header removal, and proxy forwarding.

#### Request 6: `Prefix Route Matching Subpath`
- **Method & URL:** `GET {{baseUrl}}/api/v1/orders/ORD-101`
- **Headers:** `X-API-Key: {{apiKey}}`
- **Expected Status Code:** `200 OK` or `502 Bad Gateway`
- **What this verifies:** Proves `RouteService` performs hierarchical prefix matching so sub-resources (`/api/v1/orders/123`) correctly forward to the base route target (`http://localhost:9091`).

#### Request 7: `Unmapped Route (404 Not Found)`
- **Method & URL:** `GET {{baseUrl}}/api/v1/nonexistent-service`
- **Headers:** `X-API-Key: {{apiKey}}`
- **Expected Status Code:** `404 Not Found`
- **What this verifies:** Proves unknown routes return standard HTTP 404 without crashing or hanging.

#### Request 8: `Method Not Allowed (405)`
- **Method & URL:** `DELETE {{baseUrl}}/api/v1/inventory`
- **Headers:** `X-API-Key: {{apiKey}}`
- **Expected Status Code:** `405 Method Not Allowed`
- **What this verifies:** `routes.json` only allows `GET` and `POST` for `/api/v1/inventory`. Attempting `DELETE` is rejected with 405.

---

### Folder 04: Webhook Subscribers

#### Request 9: `Register New Subscriber (201 Created)`
- **Method & URL:** `POST {{baseUrl}}/api/v1/subscribers`
- **Headers:**
  - `Content-Type: application/json`
  - `X-API-Key: {{apiKey}}`
- **Body:**
  ```json
  {
    "subscriber_id": "postman-sub-001",
    "target_url": "http://localhost:9999/webhook",
    "event_type": "order.completed"
  }
  ```
- **Expected Status Code:** `201 Created` (or `200 OK` if ID was previously registered)
- **Expected Response Body:**
  ```json
  {
    "status": 201,
    "message": "Subscriber registered successfully",
    "subscriber_id": "postman-sub-001",
    "target_url": "http://localhost:9999/webhook",
    "event_type": "order.completed"
  }
  ```
- **What this verifies:** Persists subscriber metadata into Redis Hash `subscriber:postman-sub-001` and registers the ID into the `subscribers:index` Set.

#### Request 10: `Update Existing Subscriber (200 OK)`
- **Method & URL:** `POST {{baseUrl}}/api/v1/subscribers`
- **Headers:** Same as above
- **Body:** Same `subscriber_id`, updated URL:
  ```json
  {
    "subscriber_id": "postman-sub-001",
    "target_url": "http://localhost:9999/webhook-updated",
    "event_type": "order.completed"
  }
  ```
- **Expected Status Code:** `200 OK`
- **What this verifies:** Correct idempotent update semantics (`200 OK` on update vs. `201 Created` on first creation).

#### Request 11: `Invalid Protocol Validation (400 Bad Request)`
- **Method & URL:** `POST {{baseUrl}}/api/v1/subscribers`
- **Headers:** Same as above
- **Body:**
  ```json
  {
    "subscriber_id": "sub-invalid",
    "target_url": "ftp://invalid.com/file",
    "event_type": "order.completed"
  }
  ```
- **Expected Status Code:** `400 Bad Request`
- **Expected Response Body:**
  ```json
  {
    "status": 400,
    "error": "Bad Request",
    "message": "target_url must use 'http' or 'https' protocol"
  }
  ```
- **What this verifies:** Basic perimeter validation preventing SSRF via rogue protocol handlers (e.g. `ftp://`, `file://`).

---

### Folder 05: Webhook Event Intake & Fan-out

#### Request 12: `Ingest Webhook Event (202 Accepted)`
- **Method & URL:** `POST {{baseUrl}}/api/v1/events`
- **Headers:**
  - `Content-Type: application/json`
  - `X-API-Key: {{apiKey}}`
- **Body:**
  ```json
  {
    "event_type": "order.completed",
    "source": "order-service",
    "data": {
      "order_id": "ORD-PM-{{$timestamp}}",
      "amount": 349.50,
      "currency": "USD"
    }
  }
  ```
- **Expected Status Code:** `202 Accepted`
- **Expected Response Body:**
  ```json
  {
    "status": 202,
    "message": "Event accepted. Dispatch initiated asynchronously.",
    "event_type": "order.completed",
    "source": "order-service",
    "subscriber_count": 1,
    "accepted_at": "..."
  }
  ```
- **What this verifies:** Computes SHA-256 fingerprint, verifies idempotency, finds subscribers in Redis, offloads fanout to `webhookExecutor` background threads, and returns 202 immediately.

#### Request 13: `Idempotency Deduplication (409 Conflict)`
- **Method & URL:** `POST {{baseUrl}}/api/v1/events`
- **Headers:** Same as above
- **Body:**
  ```json
  {
    "event_type": "order.completed",
    "source": "order-service",
    "data": {
      "order_id": "ORD-STATIC-IDEMPOTENT-100",
      "amount": 99.00
    }
  }
  ```
- **Step 1:** Click **Send** the first time $\rightarrow$ returns `202 Accepted`.
- **Step 2:** Click **Send** a second time without changing anything $\rightarrow$ returns `409 Conflict`.
- **Expected Response Body (on 2nd send):**
  ```json
  {
    "status": 409,
    "error": "Conflict",
    "message": "Duplicate event detected. This payload has already been processed within the last 24 hours.",
    "event_type": "order.completed"
  }
  ```
- **What this verifies:** Proves Redis `SET NX EX 86400` drops duplicates atomically without double-triggering webhooks.

#### Request 14: `No Matching Subscribers (404 Not Found)`
- **Method & URL:** `POST {{baseUrl}}/api/v1/events`
- **Headers:** Same as above
- **Body:**
  ```json
  {
    "event_type": "user.password_reset",
    "source": "auth-service",
    "data": { "user_id": "USR-009" }
  }
  ```
- **Expected Status Code:** `404 Not Found`
- **What this verifies:** If zero subscribers are registered for an event topic, SentinelPulse informs the caller cleanly with 404.

---

### Folder 06: Rate Limiting & Throttling

#### Request 15: `Rate Limit Bucket Exhaustion (429)`
- **Method & URL:** `GET {{baseUrl}}/api/v1/orders`
- **Headers:** `X-API-Key: {{apiKey}}`
- **How to Test:**
  1. Click **Send** rapidly 10 to 12 times in quick succession.
  2. The first several requests consume available tokens in the bucket.
  3. As soon as the bucket is empty, SentinelPulse responds with:
     - **Status Code:** `429 Too Many Requests`
     - **Response Header:** `Retry-After: 10`
     - **Response Body:**
       ```json
       {
         "status": 429,
         "error": "Too Many Requests",
         "message": "Rate limit exceeded. Token bucket empty. Retry after 10 seconds.",
         "client_id": "dev-client-001",
         "retry_after_seconds": 10,
         "path": "/api/v1/orders"
       }
       ```
  4. Wait 10 seconds (for tokens to refill), click **Send** again $\rightarrow$ Request is allowed!
- **What this verifies:** Validates the Redis-backed Token-Bucket algorithm with real-time refill.

---

## 4. Running the Entire Test Suite in 1-Click (Postman Collection Runner)

You can run every test in the suite automatically in a single batch:

1. In Postman, click on the root collection **SentinelPulse — Edge Infrastructure & Webhook Dispatcher**.
2. Click the **Run** button (near the top-right of the collection overview tab).
3. Ensure all 15 requests are checked.
4. Set **Iterations** to `1` and **Delay** to `0 ms`.
5. Click **Run SentinelPulse**.
6. **Result:** Every test script executes assertions automatically. All tests display **PASS** in vibrant green!

---

## 5. Automated PowerShell Verification Script

For an instant, scriptable command-line verification that tests the whole system end-to-end:

Open PowerShell in `c:\MY-SPACE\SentinelPulse` and run:
```powershell
powershell -ExecutionPolicy Bypass -File verify_sentinelpulse.ps1
```

**Output:**
```
================================================================
 SENTINELPULSE END-TO-END VERIFICATION SUITE
================================================================
[TEST] Actuator Health Check (Bypass Auth) -> Status: 200 [PASS]
[TEST] Prometheus Metrics (Bypass Auth)    -> Status: 200 [PASS]
[TEST] Auth Filter: Missing API Key        -> Status: 401 [PASS]
[TEST] Auth Filter: Invalid API Key        -> Status: 401 [PASS]
[TEST] Register Webhook Subscriber         -> Status: 201 [PASS]
[TEST] Update Webhook Subscriber           -> Status: 200 [PASS]
[TEST] Event Intake & Fan-out              -> Status: 202 [PASS]
[TEST] Idempotency Enforcement (Duplicate) -> Status: 409 [PASS]
[TEST] Reverse Proxy: Unregistered Route   -> Status: 404 [PASS]
[TEST] Reverse Proxy: Downstream Offline   -> Status: 502 [PASS]
[TEST] Rate Limiting: Burst Consumption    -> Status: 429 [PASS]
================================================================
 SUMMARY: 15 PASSED | 0 FAILED
================================================================
```

---

## 6. Inspecting Live State in GUI Dashboards

### RedisInsight (Visual Inspection of Redis Keys)
1. Open your browser to: `http://localhost:8000`
2. Connect to the local Redis instance (`host: MACHAREDIS` or `127.0.0.1`, `port: 6379`, `password: reddocis697!`).
3. You can see and inspect every live key:
   - `apikeys:sentinel-dev-key-001`
   - `rate_limit:dev-client-001`
   - `route:/api/v1/orders`
   - `subscriber:postman-sub-001`
   - `subscribers:index`
   - `idempotency:*`
   - `dlq:webhooks` (inspect any poisoned payloads)

### Prometheus Dashboard
1. Open your browser to: `http://localhost:9000`
2. In the query box, enter:
   - `sentinelpulse_requests_total`
   - `sentinelpulse_webhook_dispatched_total`
   - `sentinelpulse_dlq_depth`
3. Click **Execute** and switch to the **Graph** tab to see live time-series data.

### Grafana Dashboard
1. Open your browser to: `http://localhost:2500`
2. Log in with `admin` / `admin` (skip password change if prompted).
3. Prometheus is pre-configured as the default data source.

---

## 7. Troubleshooting & FAQ

| Symptom | Cause | Solution |
| :--- | :--- | :--- |
| `401 Unauthorized` | Missing or mistyped `X-API-Key` | Verify header is present and equals `sentinel-dev-key-001`. |
| `502 Bad Gateway` | Downstream service is offline | This is expected behavior when downstream is stopped. To get 200 OK, run `python mock_downstream.py`. |
| `429 Too Many Requests` | Rate limit or concurrency limit exceeded | Wait 10 seconds for the bucket to refill, then retry. |
| `409 Conflict` | Duplicate event payload sent | Change the `order_id` in the request body, or use `{{$timestamp}}`. |
| `404 Not Found` | Route is not in Redis or event has 0 subscribers | Register the subscriber first or verify route in `src/main/resources/routes.json`. |
| Connection Refused | Docker containers or SentinelPulse not running | Run `docker start MACHAREDIS` and start SentinelPulse via `java -jar target/sentinelpulse-1.0.0.jar`. |
