import http.server
import os
import sys

VIDEOS_DIR = os.path.join(os.path.dirname(__file__), "docs", "videos")
PORT = 8002


class RangeHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        path = os.path.join(VIDEOS_DIR, self.path.lstrip("/"))
        if not os.path.isfile(path):
            self.send_error(404)
            return

        file_size = os.path.getsize(path)
        range_header = self.headers.get("Range")

        if range_header:
            start, end = self._parse_range(range_header, file_size)
            if start is None:
                self.send_error(416, "Range Not Satisfiable")
                return
            length = end - start + 1
            self.send_response(206)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
            self.send_header("Content-Length", str(length))
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            with open(path, "rb") as f:
                f.seek(start)
                self.wfile.write(f.read(length))
        else:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(file_size))
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            with open(path, "rb") as f:
                self.wfile.write(f.read())

    def _parse_range(self, header, file_size):
        try:
            unit, ranges = header.split("=")
            if unit != "bytes":
                return None, None
            start_str, end_str = ranges.split("-")
            start = int(start_str) if start_str else file_size - int(end_str)
            end = int(end_str) if end_str else file_size - 1
            if start > end or start >= file_size:
                return None, None
            end = min(end, file_size - 1)
            return start, end
        except Exception:
            return None, None

    def log_message(self, format, *args):
        pass  # suppress request logs


if __name__ == "__main__":
    server = http.server.HTTPServer(("0.0.0.0", PORT), RangeHandler)
    print(f"Video server running on http://127.0.0.1:{PORT}")
    server.serve_forever()
