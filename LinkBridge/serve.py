#!/usr/bin/env python3
"""
serve.py   -   Development helper. written by ChatGPT originally.

Usage
-----
    # simplest
    python serve.py 192.168.1.100

    # custom assets folder and port
    python serve.py http://192.168.1.100 -a path/to/assets -p 5000

Features
--------
* Serves   /assets/<file>  →  <assets-folder>/<file>
* Serves   /               →  <assets-folder>/index.html
* Proxies  /api/<path>     →  <api-url>/api/<path>   (all HTTP methods)
* Live-reloads the browser on any *.html, *.js, *.css change inside <assets-folder>
"""

from __future__ import annotations

import argparse
import os
import pathlib
from urllib.parse import urljoin

import requests
from requests.adapters import HTTPAdapter

from flask import Flask, Response, request, send_from_directory, stream_with_context
from livereload import Server
from urllib3 import Retry

# --------------------------------------------------------------------------- #
# Command-line arguments
# --------------------------------------------------------------------------- #
default_assets = os.path.join("app","src","main","assets")

parser = argparse.ArgumentParser(description="Serve static assets with live-reload and proxy /api/*.")
parser.add_argument("api_url", help="Base API endpoint (e.g. http://192.168.1.100)")
parser.add_argument("-a", "--assets", default=default_assets, help=f"Folder with static files (default: ./{default_assets})")
parser.add_argument("-p", "--port", type=int, default=4444, help="Port to listen on (default: 4444)")
args = parser.parse_args()

ASSETS: str = os.path.abspath(args.assets)
API_BASE: str = args.api_url.rstrip("/")
if not API_BASE.startswith(("http://", "https://")):
    API_BASE = f"http://{API_BASE}"

PORT: int = args.port

adapter = HTTPAdapter(
    pool_connections=20,
    pool_maxsize=100,
    max_retries=Retry(total=0, redirect=0),  # no automatic retries
)

SESSION = requests.Session()

SESSION.mount("http://", adapter)


if not pathlib.Path(ASSETS).exists():
    raise SystemExit(f"[serve.py] Assets folder not found: {ASSETS}")

try:
    print(f"[serve.py] Checking API at {API_BASE}/api/ping ...")
    print("[serve.py] click 'allow' on the emulator ")
    ping_response = requests.get(f"{API_BASE}/api/ping", timeout=10)
    if ping_response.text.strip().lower() != "pong":
        raise SystemExit("[serve.py] Unexpected ping response",ping_response.text.strip())
except Exception as e:
    raise SystemExit(f"[serve.py] ERROR: Could not reach API at {API_BASE}/ping\n{e}")


# --------------------------------------------------------------------------- #
# Flask app
# --------------------------------------------------------------------------- #
app = Flask(__name__, static_folder=ASSETS, static_url_path="/assets")


@app.route("/", methods=["GET"])
def root() -> Response:
    """Serve index.html."""
    return send_from_directory(ASSETS, "index.html")


@app.route("/assets/<path:filename>", methods=["GET"])
def assets(filename: str) -> Response:
    """Serve anything under the assets folder."""
    return send_from_directory(ASSETS, filename)


@app.route(
    "/api/<path:path>",
    methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"],
)
def api_proxy(path: str):
    target_url = f"{API_BASE}/api/{path}"

    # Copy request headers except problematic ones
    fwd_headers = {
        k: v for k, v in request.headers if k.lower() not in ["host", "content-length", "transfer-encoding"]
    }

    # Read entire body if applicable
    body = None
    if request.method in ("POST", "PUT", "PATCH"):
        body = request.get_data()  # Fully reads the request body
        fwd_headers["Content-Length"] = str(len(body))

    # Forward the request
    upstream = SESSION.request(
        method=request.method,
        url=target_url,
        headers=fwd_headers,
        params=request.args,
        data=body,
        cookies=request.cookies,
        stream=True,  # Stream the RESPONSE, not the request
        timeout=(60, None),
        allow_redirects=False,
    )

    # Remove hop-by-hop headers from response
    hop_by_hop_res = {
        "connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailers",
        "transfer-encoding", "upgrade"
    }
    response_headers = [
        (k, v) for k, v in upstream.raw.headers.items()
        if k.lower() not in hop_by_hop_res
    ]

    def response_stream():
        for chunk in upstream.iter_content(chunk_size=64 * 1024):
            if chunk:
                yield chunk

    return Response(
        stream_with_context(response_stream()),
        status=upstream.status_code,
        headers=response_headers,
    )


# --------------------------------------------------------------------------- #
# Live-reload server
# --------------------------------------------------------------------------- #
def main() -> None:
    server = Server(app.wsgi_app)

    # Watch all *.html, *.js, *.css recursively under ASSETS
    patterns = ["*.html", "*.js", "*.css"]
    for pattern in patterns:
        server.watch(os.path.join(ASSETS, pattern))

    print(f"\n • Serving   : http://localhost:{PORT}")
    print(f" • Assets    : {ASSETS}")
    print(f" • API Proxy : {API_BASE}/api/*  ⟵  /api/*\n")

    server.serve(host="localhost", port=PORT, restart_delay=0)   # livereload injects script automatically


if __name__ == "__main__":
    main()
