#!/usr/bin/env python3
"""Small YouTube Data API v3 REST mock for YoutubeHarness (HTTP :8087).

Покрывает чтение live chat (polling, pageToken, maxResults), данные эфира (videos.list),
OAuth refresh/revoke, очередь отправки, управление трансляцией (transition/update),
модерацию (liveChatBans, удаление сообщений) и инъекцию сбоев для проверки
нарастающей задержки, Retry-After и исчерпания квоты.
"""
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
    "video_calls": [],
    "transitions": [],
    "broadcast_updates": [],
    "bans": [],
    "unbans": [],
    "deletes": [],
    "valid_access": "yt-access-2",
    "live_chat_id": "live-chat-1",
    "viewers": 7,
    # Инъекция сбоев для liveChat/messages.list: mode = 500 | 429 | quota | ended
    "fail_mode": "",
    "fail_times": 0,
    "retry_after": 0,
}
SCOPES = "https://www.googleapis.com/auth/youtube.readonly https://www.googleapis.com/auth/youtube.force-ssl"
START_TIME = "2026-10-07T18:00:00Z"


def error(status, reason, message):
    return status, {"error": {"code": status, "message": message, "errors": [{"reason": reason}]}}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send_json(self, status, payload, extra_headers=None):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (extra_headers or {}).items():
            self.send_header(key, str(value))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        if not raw:
            return {}
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {}

    def authorized(self):
        return self.headers.get("Authorization", "") == "Bearer " + STATE["valid_access"]

    def query(self):
        parsed = urlparse(self.path)
        return parsed.path, {key: values[-1] for key, values in parse_qs(parsed.query).items()}

    def broadcast_resource(self, broadcast_id, title):
        return {
            "id": broadcast_id,
            "snippet": {"title": title, "liveChatId": STATE["live_chat_id"], "scheduledStartTime": START_TIME},
            "status": {"privacyStatus": "public", "liveBroadcastStatus": "live"},
            "contentDetails": {"monitorStream": {"enableMonitorStream": True}, "recordFromStart": True},
        }

    def chat_page(self, page, call_number):
        """Страницы live chat: история → новое сообщение → модерация → служебное → пусто."""
        if not page:
            items = [{
                "id": "old-message",
                "snippet": {"type": "textMessageEvent", "textMessageDetails": {"messageText": "!old must-not-run"}},
                "authorDetails": {"channelId": "UC-old", "displayName": "Old Viewer"},
            }]
            return items, "PAGE-1"
        if page == "PAGE-1":
            items = [{
                "id": "new-message",
                "snippet": {"type": "textMessageEvent", "textMessageDetails": {"messageText": "!new hello"}},
                "authorDetails": {"channelId": "UC-viewer", "displayName": "New Viewer", "isChatModerator": True},
            }]
            return items, "PAGE-2"
        if page == "PAGE-2":
            items = [{
                "id": "ban-message",
                "snippet": {"type": "userBannedEvent", "userBannedEventDetails": {
                    "banType": "temporary", "banDurationSeconds": 300,
                    "bannedUserDetails": {"channelId": "UC-troll", "displayName": "Troll Viewer"}}},
                "authorDetails": {"channelId": "UC-mod", "displayName": "Moderator", "isChatModerator": True},
            }]
            return items, "PAGE-3"
        if page == "PAGE-3":
            items = [{"id": "service-message", "snippet": {"type": "placeholderMessageEvent"},
                      "authorDetails": {"channelId": "", "displayName": ""}}]
            return items, "PAGE-" + str(call_number + 1)
        return [], "PAGE-" + str(call_number + 1)

    def maybe_fail(self):
        """Инъекция сбоя для опроса чата. Возвращает True, если ответ уже отправлен."""
        with LOCK:
            if STATE["fail_times"] <= 0 or not STATE["fail_mode"]:
                return False
            STATE["fail_times"] -= 1
            mode = STATE["fail_mode"]
            retry_after = STATE["retry_after"]
            if STATE["fail_times"] <= 0:
                STATE["fail_mode"] = ""
        if mode == "429":
            status, payload = error(429, "rateLimitExceeded", "too many requests")
            self.send_json(status, payload, {"Retry-After": retry_after or 3})
        elif mode == "quota":
            self.send_json(*error(403, "quotaExceeded", "daily quota exceeded"))
        elif mode == "ended":
            self.send_json(*error(404, "liveChatNotFound", "chat gone"))
        else:
            self.send_json(*error(500, "backendError", "internal error"))
        return True

    def do_GET(self):
        path, query = self.query()
        if path == "/__state":
            with LOCK:
                return self.send_json(200, STATE)
        if path == "/__fail":
            with LOCK:
                STATE["fail_mode"] = query.get("mode", "")
                STATE["fail_times"] = int(query.get("times", "0"))
                STATE["retry_after"] = int(query.get("retry_after", "0"))
            return self.send_json(200, {"ok": True, "mode": STATE["fail_mode"], "times": STATE["fail_times"]})
        if not path.startswith("/youtube/v3/"):
            return self.send_json(404, {"error": "not_found", "message": path})
        if not self.authorized():
            return self.send_json(*error(401, "authError", "invalid token"))

        method = path.removeprefix("/youtube/v3/")

        if method == "channels":
            with LOCK:
                STATE["channel_calls"] += 1
            if query.get("mine") != "true":
                return self.send_json(*error(400, "invalidParameter", "mine=true required"))
            return self.send_json(200, {"items": [{"id": "UC-owner", "snippet": {"title": "Channel Owner"}}]})

        if method == "liveBroadcasts":
            with LOCK:
                STATE["broadcast_calls"].append({"time": int(time.time() * 1000), **query})
            if "mine" in query:
                return self.send_json(*error(400, "invalidFilters", "invalid liveBroadcast filter"))
            if query.get("id"):
                return self.send_json(200, {"items": [self.broadcast_resource(query["id"], "Старый заголовок")]})
            status = query.get("broadcastStatus", "")
            if status == "active":
                return self.send_json(200, {"items": [{
                    "id": "broadcast-1",
                    "snippet": {"liveChatId": STATE["live_chat_id"], "title": "Тестовый эфир"},
                    "status": {"liveBroadcastStatus": "live"},
                }]})
            if status == "upcoming":
                return self.send_json(200, {"items": [{
                    "id": "broadcast-upcoming",
                    "snippet": {"scheduledStartTime": START_TIME},
                    "status": {"liveBroadcastStatus": "ready"},
                }]})
            return self.send_json(*error(400, "invalidFilters", "broadcastStatus required"))

        if method == "videos":
            with LOCK:
                STATE["viewers"] += 1
                viewers = STATE["viewers"]
                STATE["video_calls"].append({"time": int(time.time() * 1000), **query})
            if query.get("id") != "broadcast-1":
                return self.send_json(200, {"items": []})
            return self.send_json(200, {"items": [{
                "id": "broadcast-1",
                "snippet": {"title": "Тестовый эфир"},
                "liveStreamingDetails": {
                    "activeLiveChatId": STATE["live_chat_id"],
                    "concurrentViewers": str(viewers),
                    "actualStartTime": START_TIME,
                },
            }]})

        if method == "liveChat/messages":
            page = query.get("pageToken", "")
            with LOCK:
                call_number = len(STATE["chat_calls"]) + 1
                STATE["chat_calls"].append({
                    "pageToken": page,
                    "time": int(time.time() * 1000),
                    "liveChatId": query.get("liveChatId", ""),
                    "maxResults": query.get("maxResults", ""),
                })
            if self.maybe_fail():
                return None
            if query.get("liveChatId") != STATE["live_chat_id"]:
                return self.send_json(*error(404, "liveChatNotFound", "no live chat"))
            items, next_token = self.chat_page(page, call_number)
            return self.send_json(200, {"items": items, "nextPageToken": next_token, "pollingIntervalMillis": 1100})

        return self.send_json(*error(404, "notFound", "unknown API method"))

    def do_POST(self):
        path, query = self.query()
        if path == "/oauth2/token":
            length = int(self.headers.get("Content-Length") or 0)
            form = {key: values[-1] for key, values in parse_qs(self.rfile.read(length).decode("utf-8")).items()}
            with LOCK:
                STATE["token_calls"].append(form)
            if form.get("grant_type") != "refresh_token" or form.get("refresh_token") != "yt-refresh-1" or form.get("client_id") != "desktop-client":
                return self.send_json(400, {"error": "invalid_grant", "error_description": "invalid refresh grant"})
            with LOCK:
                STATE["valid_access"] = "yt-access-2"
            return self.send_json(200, {"access_token": "yt-access-2", "refresh_token": "yt-refresh-2", "expires_in": 3600, "scope": SCOPES})

        if path == "/oauth2/revoke":
            length = int(self.headers.get("Content-Length") or 0)
            form = {key: values[-1] for key, values in parse_qs(self.rfile.read(length).decode("utf-8")).items()}
            return self.send_json(200, {"revoked": bool(form.get("token"))})

        if not self.authorized():
            return self.send_json(*error(401, "authError", "invalid token"))

        if path == "/youtube/v3/liveBroadcasts/transition":
            body = self.read_json()
            wanted = query.get("broadcastStatus", "")
            with LOCK:
                STATE["transitions"].append({"time": int(time.time() * 1000), "id": query.get("id", ""),
                                             "broadcastStatus": wanted, "body": body, "query": dict(query)})
            if wanted not in ("live", "testing", "complete"):
                return self.send_json(*error(400, "invalidValue", "broadcastStatus must be live/testing/complete"))
            if wanted == "testing":
                # Проверяем, что мод понимает «трансляция уже в этом состоянии»
                return self.send_json(*error(403, "redundantTransition", "already in this status"))
            return self.send_json(200, {"id": query.get("id", ""), "status": {"liveBroadcastStatus": wanted}})

        if path == "/youtube/v3/liveChat/messages":
            body = self.read_json()
            snippet = body.get("snippet", {})
            with LOCK:
                STATE["posts"].append({"time": int(time.time() * 1000), "body": body, "query": dict(query)})
                post_id = "sent-" + str(len(STATE["posts"]))
            return self.send_json(200, {"id": post_id, "snippet": snippet})

        if path == "/youtube/v3/liveChat/bans":
            body = self.read_json()
            snippet = body.get("snippet", {})
            with LOCK:
                STATE["bans"].append({"time": int(time.time() * 1000), "body": body})
                ban_id = "ban-" + str(len(STATE["bans"]))
            if not snippet.get("bannedUserDetails", {}).get("channelId"):
                return self.send_json(*error(400, "bannedUserChannelIdRequired", "channelId required"))
            return self.send_json(200, {"id": ban_id, "snippet": snippet})

        return self.send_json(*error(404, "notFound", "not found"))

    def do_PUT(self):
        path, query = self.query()
        if not path.startswith("/youtube/v3/"):
            return self.send_json(404, {"error": "not_found", "message": path})
        if not self.authorized():
            return self.send_json(*error(401, "authError", "invalid token"))
        if path == "/youtube/v3/liveBroadcasts":
            body = self.read_json()
            with LOCK:
                STATE["broadcast_updates"].append({"time": int(time.time() * 1000), "body": body, "query": dict(query)})
            title = body.get("snippet", {}).get("title", "")
            if not title:
                return self.send_json(*error(400, "required", "snippet.title required"))
            # liveBroadcasts.update требует полный ресурс: проверяем обязательные части
            for part in ("status", "contentDetails"):
                if part not in body:
                    return self.send_json(*error(400, "required", part + " required"))
            if "enableMonitorStream" not in body.get("contentDetails", {}).get("monitorStream", {}):
                return self.send_json(*error(400, "required", "contentDetails.monitorStream.enableMonitorStream required"))
            resource = self.broadcast_resource(body.get("id", "broadcast-1"), title)
            return self.send_json(200, resource)
        return self.send_json(*error(404, "notFound", "not found"))

    def do_DELETE(self):
        path, query = self.query()
        if not path.startswith("/youtube/v3/"):
            return self.send_json(404, {"error": "not_found", "message": path})
        if not self.authorized():
            return self.send_json(*error(401, "authError", "invalid token"))
        if path == "/youtube/v3/liveChat/bans":
            with LOCK:
                STATE["unbans"].append({"time": int(time.time() * 1000), "id": query.get("id", "")})
            if not query.get("id"):
                return self.send_json(*error(400, "idRequired", "id required"))
            return self.send_json(200, {})
        if path == "/youtube/v3/liveChat/messages":
            with LOCK:
                STATE["deletes"].append({"time": int(time.time() * 1000), "id": query.get("id", "")})
            if not query.get("id"):
                return self.send_json(*error(400, "idRequired", "id required"))
            return self.send_json(200, {})
        return self.send_json(*error(404, "notFound", "not found"))


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8087), Handler).serve_forever()
