"""Disposable loopback TLS adapter for the Android emulator integration test."""
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ssl
import sys

cert, key, frontend, backend = sys.argv[1:]


class Proxy(BaseHTTPRequestHandler):
    def forward(self):
        if not self.path.startswith(("/v1/", "/ready")):
            self.send_error(404)
            return
        size = int(self.headers.get("Content-Length", "0"))
        archive_upload = self.command == "PUT" and self.path.startswith("/v1/editable/staged/")
        if not 0 <= size <= (16 * 1024 * 1024 if archive_upload else 16_384):
            self.send_error(413)
            return
        content = self.rfile.read(size) if size else None
        upstream = HTTPConnection("127.0.0.1", int(backend), timeout=20)
        forwarded = ("Authorization", "Content-Type", "Accept", "X-LifeApp-Dataset", "X-LifeApp-Epoch", "X-Content-SHA256")
        headers = {name: self.headers[name] for name in forwarded if name in self.headers}
        try:
            upstream.request(self.command, self.path, content, headers)
            result = upstream.getresponse()
            binary = result.getheader("Content-Type") == "application/octet-stream"
            limit = (16 if binary else 8) * 1024 * 1024
            response = result.read(limit + 1)
            if len(response) > limit:
                self.send_error(502)
                return
            self.send_response(result.status)
            for name in ("Content-Type", "Retry-After", "X-Content-SHA256"):
                if value := result.getheader(name):
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)
        finally:
            upstream.close()

    do_GET = forward
    do_POST = forward
    do_PUT = forward
    do_DELETE = forward

    def log_message(self, *_args):
        pass


context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert, key)
with ThreadingHTTPServer(("127.0.0.1", int(frontend)), Proxy) as server:
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()
