#!/usr/bin/env python3
"""Small YouTube Data API v3 REST mock for YoutubeHarness (HTTP :8087)."""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()
STATE = {
    "token_calls": [],
    "channel_calls": 0,
    "broadcast_calls": [],
    "chat_calls": [],
    "posts": [],
    "valid_access": "yt-access-2",
    "live_chat_id": "live-chat-1",
}
SCOPES = "https://www.googleapis.com/auth/youtube.readonly https://www.googleapis.com/auth/youtube.force-ssl"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {}

    def authorized(self):
        return self.headers.get("Authorization", "") == "Bearer " + STATE["valid_access"]

    def do_GET(self):
        parsed = urlparse(self.path)
        query = {key: values[-1] for key, values in parse_qs(parsed.query).items()}
        if parsed.path == "/__state":
            with LOCK:
                return self.send_json(200, STATE)
        if not parsed.path.startswith("/youtube/v3/"):
            return self.send_json(404, {"error": "not_found", "message": parsed.path})
        if not self.authorized():
            return self.send_json(401, {"error": {"code": 401, "message": "invalid token", "errors": [{"reason": "authError"}]}})

        method = parsed.path.removeprefix("/youtube/v3/")
        if method == "channels":
            with LOCK:
                STATE["channel_calls"] += 1
            if query.get("mine") != "true":
                return self.send_json(400, {"error": {"code": 400, "message": "mine=true required", "errors": [{"reason": "invalidParameter"}]}})
            return self.send_json(200, {"items": [{"id": "UC-owner", "snippet": {"title": "Channel Owner"}}]})

        if method == "liveBroadcasts":
            with LOCK:
                STATE["broadcast_calls"].append({"time": int(time.time() * 1000), **query})
            if query.get("broadcastStatus") != "active" or "mine" in query:
                return self.send_json(400, {"error": {"code": 400, "message": "invalid liveBroadcast filter", "errors": [{"reason": "invalidFilters"}]}})
            return self.send_json(200, {"items": [{"id": "broadcast-1", "snippet": {"liveChatId": STATE["live_chat_id"]}}]})

        if method == "liveChat/messages":
            page = query.get("pageToken", "")
            with LOCK:
                call_number = len(STATE["chat_calls"]) + 1
                STATE["chat_calls"].append({"pageToken": page, "time": int(time.time() * 1000), "liveChatId": query.get("liveChatId", "")})
            if query.get("liveChatId") != STATE["live_chat_id"]:
                return self.send_json(404, {"error": {"code": 404, "message": "no live chat", "errors": [{"reason": "liveChatNotFound"}]}})
            if not page:
                # The newest available page on first connect is intentionally historical and must be skipped.
                items = [{"id": "old-message", "snippet": {"type": "textMessageEvent", "textMessageDetails": {"messageText": "!old must-not-run"}},
                          "authorDetails": {"channelId": "UC-old", "displayName": "Old Viewer"}}]
                next_token = "PAGE-1"
            elif page == "PAGE-1":
                items = [{"id": "new-message", "snippet": {"type": "textMessageEvent", "textMessageDetails": {"messageText": "!new hello"}},
                          "authorDetails": {"channelId": "UC-viewer", "displayName": "New Viewer", "isChatModerator": True}}]
                next_token = "PAGE-2"
            else:
                items = []
                next_token = "PAGE-" + str(call_number + 1)
            return self.send_json(200, {"items": items, "nextPageToken": next_token, "pollingIntervalMillis": 1100})

        return self.send_json(404, {"error": {"code": 404, "message": "unknown API method", "errors": [{"reason": "notFound"}]}})

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/oauth2/token":
            length = int(self.headers.get("Content-Length") or 0)
            form = {key: values[-1] for key, values in parse_qs(self.rfile.read(length).decode("utf-8")).items()}
            with LOCK:
                STATE["token_calls"].append(form)
            if form.get("grant_type") != "refresh_token" or form.get("refresh_token") != "yt-refresh-1" or form.get("client_id") != "desktop-client":
                return self.send_json(400, {"error": "invalid_grant", "error_description": "invalid refresh grant"})
            with LOCK:
                STATE["valid_access"] = "yt-access-2"
            return self.send_json(200, {"access_token": "yt-access-2", "refresh_token": "yt-refresh-2", "expires_in": 3600, "scope": SCOPES})

        if parsed.path == "/oauth2/revoke":
            length = int(self.headers.get("Content-Length") or 0)
            form = {key: values[-1] for key, values in parse_qs(self.rfile.read(length).decode("utf-8")).items()}
            return self.send_json(200, {"revoked": bool(form.get("token"))})

        if parsed.path == "/youtube/v3/liveChat/messages":
            if not self.authorized():
                return self.send_json(401, {"error": {"code": 401, "message": "invalid token", "errors": [{"reason": "authError"}]}})
            body = self.read_json()
            snippet = body.get("snippet", {})
            with LOCK:
                STATE["posts"].append({"time": int(time.time() * 1000), "body": body, "query": parse_qs(parsed.query)})
                post_id = "sent-" + str(len(STATE["posts"]))
            return self.send_json(200, {"id": post_id, "snippet": snippet})

        return self.send_json(404, {"error": {"code": 404, "message": "not found", "errors": [{"reason": "notFound"}]}})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8087), Handler).serve_forever()
