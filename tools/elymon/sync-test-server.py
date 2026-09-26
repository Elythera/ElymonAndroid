#!/usr/bin/env python3
"""Static file server for the Elymon sync host tests (tools/elymon/test-sync.sh).

python3's http.server, plus what the tests need from a CDN:
  * Range requests (bytes=N- and bytes=N-M), answered with 206;
  * an ETag (MD5 of the content) and If-None-Match, answered with 304;
  * faults injected per path through <root>/_faults.json, e.g.
      {"/files/a.txt": {"mode": "corrupt", "count": 1}}
    modes: corrupt (same length, other bytes), truncate (half the body, then the
    connection is closed), error500, html (a captive-portal page with 200),
    norange (Range ignored: the whole file with 200), slow (the body in 20
    pieces, 50 ms apart);
  * one line per request in the log file, which the tests read.

Usage: sync-test-server.py <root> <port-file> <log-file>
The port is chosen by the system and written to <port-file> once listening.
"""
import hashlib
import json
import os
import sys
import threading
import time
import urllib.parse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.abspath(sys.argv[1])
PORT_FILE = sys.argv[2]
LOG_FILE = sys.argv[3]
LOCK = threading.Lock()


def take_fault(path):
    """The fault to apply to this request, if any, decrementing its count."""
    faults_file = os.path.join(ROOT, '_faults.json')
    with LOCK:
        try:
            with open(faults_file) as f:
                faults = json.load(f)
        except (OSError, ValueError):
            return None
        fault = faults.get(path)
        if not fault or fault.get('count', 0) <= 0:
            return None
        fault['count'] -= 1
        with open(faults_file, 'w') as f:
            json.dump(faults, f)
        return fault['mode']


def log(line):
    with LOCK:
        with open(LOG_FILE, 'a') as f:
            f.write(line + '\n')


class Handler(SimpleHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        raw_path = self.path.split('?', 1)[0]
        path = urllib.parse.unquote(raw_path)
        rng = self.headers.get('Range')
        inm = self.headers.get('If-None-Match')
        status, sent = self.answer(path, rng, inm)
        log('GET %s range=%s inm=%s status=%d bytes=%d ua=%s' % (
            raw_path, rng or '-', 'yes' if inm else '-', status, sent, self.headers.get('User-Agent', '-')))

    def answer(self, path, rng, inm):
        local = os.path.normpath(os.path.join(ROOT, path.lstrip('/')))
        if not (local == ROOT or local.startswith(ROOT + os.sep)) or not os.path.isfile(local):
            return self.plain(404, b'not found')
        fault = take_fault(path)
        if fault == 'error500':
            return self.plain(500, b'boom')
        if fault == 'html':
            return self.plain(200, b'<html><body>Portail captif</body></html>', 'text/html')
        with open(local, 'rb') as f:
            body = f.read()
        etag = '"%s"' % hashlib.md5(body).hexdigest()
        if inm is not None and inm == etag:
            self.send_response(304)
            self.send_header('ETag', etag)
            self.send_header('Content-Length', '0')
            self.end_headers()
            return 304, 0
        status = 200
        start, end = 0, len(body) - 1
        if rng and rng.startswith('bytes=') and fault != 'norange':
            first, _, last = rng[6:].partition('-')
            start = int(first)
            end = int(last) if last else len(body) - 1
            if start >= len(body):
                self.send_response(416)
                self.send_header('Content-Range', 'bytes */%d' % len(body))
                self.send_header('Content-Length', '0')
                self.end_headers()
                return 416, 0
            status = 206
        chunk = body[start:end + 1]
        if fault == 'corrupt':
            chunk = bytes((b ^ 0xFF) for b in chunk)
        self.send_response(status)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(chunk)))
        self.send_header('ETag', etag)
        if status == 206:
            self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, len(body)))
        self.end_headers()
        if fault == 'truncate':
            half = chunk[:len(chunk) // 2]
            self.wfile.write(half)
            self.wfile.flush()
            self.close_connection = True
            try:
                self.connection.shutdown(2)
            except OSError:
                pass
            return status, len(half)
        if fault == 'slow':
            step = max(1, len(chunk) // 20)
            sent = 0
            try:
                for i in range(0, len(chunk), step):
                    self.wfile.write(chunk[i:i + step])
                    self.wfile.flush()
                    sent += len(chunk[i:i + step])
                    time.sleep(0.05)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True
            return status, sent
        self.wfile.write(chunk)
        return status, len(chunk)

    def plain(self, status, body, content_type='text/plain'):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        return status, len(body)


def main():
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    tmp = PORT_FILE + '.tmp'
    with open(tmp, 'w') as f:
        f.write(str(server.server_address[1]))
    os.replace(tmp, PORT_FILE)
    server.serve_forever()


if __name__ == '__main__':
    main()
