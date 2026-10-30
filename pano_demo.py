#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地 HTTP 服务：提供 equirect.jpg 和可拖拽的全景查看器。"""
import argparse
import http.server
import os

import cv2
import stitch_air

CACHE = {}


def build_equirect(photo, width=1920, height=960):
    img = cv2.imread(photo)
    if img is None:
        raise RuntimeError("读不到图片: " + photo)
    lenses, ow, oh = stitch_air.parse_offset(stitch_air.DEFAULT_OFFSET, img.shape[1])
    p = stitch_air.auto_align(img, lenses, 10.0)
    eq = stitch_air.equirect(img, lenses, p, width, height)
    ok, buf = cv2.imencode(".jpg", eq, [cv2.IMWRITE_JPEG_QUALITY, 92])
    return buf.tobytes()


class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/equirect.jpg":
            if "eq" not in CACHE:
                CACHE["eq"] = build_equirect(self.server.photo)
            data = CACHE["eq"]
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)
            return
        if path in ("/", "/index.html"):
            here = os.path.dirname(os.path.abspath(__file__))
            with open(os.path.join(here, "pano_demo.html"), "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        super().do_GET()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--photo", default=r"C:\Users\Administrator\Pictures\Camera Roll\WIN_20261025_00_57_35_Pro.jpg")
    ap.add_argument("--port", type=int, default=8000)
    args = ap.parse_args()
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    httpd.photo = args.photo
    print("http://127.0.0.1:%d/" % args.port, flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
