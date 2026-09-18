from http.server import HTTPServer, BaseHTTPRequestHandler
import json

class WebhookReceiver(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length).decode('utf-8') if content_length > 0 else ""
        print(f"[RECEIVER] Received webhook delivery: Event={self.headers.get('X-SentinelPulse-Event')}, DeliveryId={self.headers.get('X-SentinelPulse-Delivery')}")
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{"status":"DELIVERED","received":true}')

if __name__ == '__main__':
    server = HTTPServer(('127.0.0.1', 9999), WebhookReceiver)
    print("Mock Webhook Receiver running on http://127.0.0.1:9999")
    server.serve_forever()
