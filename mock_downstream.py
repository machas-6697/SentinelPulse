from http.server import HTTPServer, BaseHTTPRequestHandler
import json

class MockHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        response = {
            "service": "orders-downstream-service",
            "status": "SUCCESS",
            "path": self.path,
            "received_headers": {
                "X-Forwarded-For": self.headers.get('X-Forwarded-For'),
                "X-Request-ID": self.headers.get('X-Request-ID'),
                "X-Gateway-Time": self.headers.get('X-Gateway-Time')
            },
            "orders": [
                {"id": "ORD-101", "item": "Quantum Pulse Processor", "price": 499.99},
                {"id": "ORD-102", "item": "Neural Gateway Adapter", "price": 129.50}
            ]
        }
        self.wfile.write(json.dumps(response).encode('utf-8'))

    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length).decode('utf-8') if content_length > 0 else ""
        self.send_response(201)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        response = {
            "service": "orders-downstream-service",
            "status": "CREATED",
            "received_body": json.loads(body) if body else None,
            "gateway_request_id": self.headers.get('X-Request-ID')
        }
        self.wfile.write(json.dumps(response).encode('utf-8'))

if __name__ == '__main__':
    server = HTTPServer(('127.0.0.1', 9091), MockHandler)
    print("Mock Downstream Orders Service running on http://127.0.0.1:9091")
    server.serve_forever()
