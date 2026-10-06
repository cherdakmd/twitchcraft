#!/usr/bin/env python3
"""Фейковый VK Video Live (DevAPI + OAuth + Centrifugo v2) для VkHarness.

Порты: 8085 — HTTP (OAuth /oauth/server/*, API /v1/*), 8086 — WebSocket (Centrifugo, протокол v2 JSON).
Приложение: client_id=vkapp, secret=vksecret, redirect_uri=http://localhost:8638/vk, код авторизации good-code.
Токены: vk-access-1 / vk-refresh-1 → после refresh vk-access-2 / vk-refresh-2.
Канал dedworkshop: web_socket_channels = channel-chat:777, channel-info:777, channel-points:777,
private-channel-info:777, private-channel-points:777 (приватные требуют токен подписки sub-<канал>).

Сценарий WebSocket (первое соединение, после подписки на чат):
  сообщение чата → запрос награды 501 (pending) → его дубль через private-info → фоллов newbie ×2 (дубль)
  → запрос 503 (approved) → stream_online_status offline → stream_start stream-2 → неизвестное событие
  → пинг {} → push unsubscribe private-channel-info (клиент должен переподписаться).
connect/refresh выдают expires=true, ttl=3 — клиент обязан слать refresh с новым токеном из GET /websocket/token.
Отправка в чат: первая попытка → 421 send_too_fast (клиент повторяет через 3 с).
Управление: GET /__state, /__expire (все access-токены недействительны до refresh), /__close (закрыть WebSocket).
После второго соединения GET /channel_point/reward/demands отдаёт список для «догонки» (501 — уже видели,
504 — новый, 400 — старше 10 минут, 505 — уже approved).
"""
import asyncio
import base64
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import websockets

STATE = {
    "token_calls": [],
    "refresh_calls": 0,
    "revoke_calls": 0,
    "valid_access": ["vk-access-1"],
    "current_refresh": "vk-refresh-1",
    "user_calls": 0,
    "channel_calls": [],
    "ws_token_calls": 0,
    "last_sock": "",
    "sub_token_calls": [],
    "ws_connections": 0,
    "subscribes": {},
    "ws_refresh_calls": 0,
    "pings_answered": 0,
    "send_attempts": 0,
    "sent": [],
    "accepted": [],
    "rejected": [],
    "demands_calls": 0,
    "rewards_calls": 0,
    "manage_info_calls": 0,
    "created_rewards": [],
    "unauthorized": 0,
}

BASIC = base64.b64encode(b"vkapp:vksecret").decode()
REDIRECT = "http://localhost:8638/vk"
CHANNELS = {
    "chat": "channel-chat:777",
    "info": "channel-info:777",
    "channel_points": "channel-points:777",
    "private_chat": "private-channel-chat:777",
    "private_info": "private-channel-info:777",
    "private_channel_points": "private-channel-points:777",
    "limited_chat": "limited-channel-chat:777",
    "limited_private_chat": "limited-private-channel-chat:777",
}
WS = {"current": None, "loop": None}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive, как у настоящего сервера

    def log_message(self, fmt, *args):  # тише
        pass

    def send_json(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length).decode("utf-8") if length else ""

    def bearer_ok(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer ") and auth[7:] in STATE["valid_access"]:
            return True
        STATE["unauthorized"] += 1
        self.send_json(401, {"error": "unauthorized", "error_description": "invalid token"})
        return False

    # ---------- GET ----------
    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        path = url.path
        if path == "/__state":
            return self.send_json(200, STATE)
        if path == "/__expire":
            STATE["valid_access"] = []
            return self.send_json(200, {"ok": True})
        if path == "/__close":
            ws = WS["current"]
            if ws is not None and WS["loop"] is not None:
                asyncio.run_coroutine_threadsafe(ws.close(code=1012, reason="restart"), WS["loop"])
            return self.send_json(200, {"ok": True})
        if not path.startswith("/v1/"):
            return self.send_json(404, {"error": "unknown_api_method"})
        if not self.bearer_ok():
            return
        method = path[len("/v1/"):]
        if method == "current_user":
            STATE["user_calls"] += 1
            return self.send_json(200, {"data": {"channel": {"url": "https://live.vkvideo.ru/dedworkshop/"}, "channels": [],
                                               "user": {"id": 1, "nick": "DedWorkshop", "nick_color": 5, "is_streamer": True}}})
        if method == "channel":
            slug = q.get("channel_url", "")
            STATE["channel_calls"].append(slug)
            if slug != "dedworkshop":
                return self.send_json(404, {"error": "not_found", "error_description": "channel not found"})
            return self.send_json(200, {"data": {
                "channel": {"id": 777, "nick": "DedWorkshop", "url": "https://live.vkvideo.ru/dedworkshop", "status": "online",
                            "counters": {"subscribers": 10}, "web_socket_channels": CHANNELS},
                "owner": {"id": 1, "nick": "DedWorkshop"},
                "stream": {"id": "stream-1", "status": "online", "title": "Тест", "started_at": int(time.time()) - 60}}})
        if method == "websocket/token":
            STATE["ws_token_calls"] += 1
            STATE["last_sock"] = "sock-vk-%d" % STATE["ws_token_calls"]
            return self.send_json(200, {"data": {"token": STATE["last_sock"]}})
        if method == "websocket/subscription_token":
            channels = [c for c in q.get("channels", "").split(",") if c]
            STATE["sub_token_calls"].append(channels)
            tokens = [{"channel": c, "token": "sub-" + c} for c in channels if c.startswith("private-")]
            return self.send_json(200, {"data": {"channel_tokens": tokens}})
        if method == "channel_point/rewards":
            STATE["rewards_calls"] += 1
            return self.send_json(200, {"data": {"rewards": [
                {"id": "rw-bad", "name": "Пакость", "price": 250, "description": "", "is_disabled": False},
                {"id": "rw-good", "name": "Подарок", "price": 250, "description": "", "is_disabled": False}]}})
        if method == "channel_point/rewards/manage_info":
            STATE["manage_info_calls"] += 1
            return self.send_json(200, {"data": {"rewards": [
                {"id": "rw-good", "name": "Подарок", "price": 250, "description": "", "is_disabled": False,
                 "is_message_required": False, "max_uses_count": 0, "max_uses_count_per_user": 0, "repair_timeout": 0, "background_color": 3}]}})
        if method == "channel_point/reward/demands":
            STATE["demands_calls"] += 1
            if STATE["ws_connections"] < 2:
                return self.send_json(200, {"data": {"demands": []}})
            now = int(time.time())
            return self.send_json(200, {"data": {"demands": [
                {"id": 501, "created_at": now - 30, "status": "pending", "reward": {"id": "rw-bad"},
                 "user": {"id": 55, "nick": "vasya", "nick_color": 1}, "message_parts": []},
                {"id": 504, "created_at": now - 60, "status": "pending", "reward": {"id": "rw-good"},
                 "user": {"id": 58, "nick": "late", "nick_color": 2}, "message_parts": [{"text": {"content": "догони"}}]},
                {"id": 400, "created_at": now - 7200, "status": "pending", "reward": {"id": "rw-bad"},
                 "user": {"id": 59, "nick": "old"}, "message_parts": []},
                {"id": 505, "created_at": now - 10, "status": "approved", "reward": {"id": "rw-good"},
                 "user": {"id": 60, "nick": "done"}, "message_parts": []}]}})
        return self.send_json(404, {"error": "unknown_api_method", "error_description": method})

    # ---------- POST ----------
    def do_POST(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        path = url.path
        body = self.read_body()
        if path == "/oauth/server/token":
            if self.headers.get("Authorization", "") != "Basic " + BASIC:
                return self.send_json(401, {"error": "unauthorized", "error_description": "bad client credentials"})
            form = {k: v[0] for k, v in parse_qs(body).items()}
            STATE["token_calls"].append(form)
            if form.get("grant_type") == "authorization_code":
                if form.get("code") != "good-code" or form.get("redirect_uri") != REDIRECT:
                    return self.send_json(400, {"error": "invalid_grant", "error_description": "bad code or redirect_uri"})
                STATE["valid_access"] = ["vk-access-1"]
                STATE["current_refresh"] = "vk-refresh-1"
                return self.send_json(200, {"access_token": "vk-access-1", "refresh_token": "vk-refresh-1",
                                            "expires_in": 3600, "token_type": "Bearer"})
            if form.get("grant_type") == "refresh_token":
                if form.get("refresh_token") != STATE["current_refresh"]:
                    return self.send_json(400, {"error": "invalid_grant", "error_description": "bad refresh token"})
                STATE["refresh_calls"] += 1
                n = STATE["refresh_calls"] + 1
                STATE["valid_access"] = ["vk-access-%d" % n]
                STATE["current_refresh"] = "vk-refresh-%d" % n
                return self.send_json(200, {"access_token": "vk-access-%d" % n, "refresh_token": "vk-refresh-%d" % n,
                                            "expires_in": 3600, "token_type": "Bearer"})
            return self.send_json(400, {"error": "unsupported_grant_type"})
        if path == "/oauth/server/revoke":
            STATE["revoke_calls"] += 1
            return self.send_json(200, {})
        if not path.startswith("/v1/"):
            return self.send_json(404, {"error": "unknown_api_method"})
        if not self.bearer_ok():
            return
        method = path[len("/v1/"):]
        try:
            payload = json.loads(body) if body else {}
        except ValueError:
            payload = {}
        if method == "chat/message/send":
            STATE["send_attempts"] += 1
            if STATE["send_attempts"] == 1:
                return self.send_json(421, {"error": "send_too_fast", "error_description": "slow down"})
            text = ""
            for part in payload.get("parts", []):
                text += part.get("text", {}).get("content", "")
            STATE["sent"].append({"text": text, "channel_url": q.get("channel_url", ""), "stream_id": q.get("stream_id", ""),
                                  "token": self.headers.get("Authorization", "")[7:]})
            return self.send_json(200, {"data": {}})
        if method == "channel_point/reward/demand/accept":
            STATE["accepted"].extend(d.get("id") for d in payload.get("demands", []))
            return self.send_json(200, {"data": {}})
        if method == "channel_point/reward/demand/reject":
            STATE["rejected"].extend(d.get("id") for d in payload.get("demands", []))
            return self.send_json(200, {"data": {}})
        if method == "channel_point/reward/create":
            reward = payload.get("reward", {})
            STATE["created_rewards"].append(reward)
            return self.send_json(200, {"data": {"reward": dict(reward, id="rw-new-%d" % len(STATE["created_rewards"]))}})
        return self.send_json(404, {"error": "unknown_api_method", "error_description": method})


# ---------- Centrifugo v2 ----------

def push(channel, payload):
    return json.dumps({"push": {"channel": channel, "pub": {"data": payload, "offset": int(time.time())}}}, ensure_ascii=False)


def text_block(content):
    # как на сайте: content — JSON-строка черновика ["текст","unstyled",[]]
    return {"type": "text", "content": json.dumps([content, "unstyled", []], ensure_ascii=False), "modificator": ""}


async def scenario(ws, conn):
    await asyncio.sleep(0.3)
    if conn != 1:
        return  # после переподключения сервер молчит — клиент догоняет награды через REST
    await ws.send(push(CHANNELS["chat"], {"type": "message", "data": {
        "id": 9001, "createdAt": int(time.time()), "isPrivate": False,
        "author": {"id": 55, "nick": "vasya", "displayName": "Вася", "nickColor": 3, "isOwner": False,
                   "isChatModerator": False, "isChannelModerator": False, "roles": [], "badges": []},
        "data": [text_block("привет всем"), {"type": "smile", "name": "pepeLaugh"}]}}))
    await asyncio.sleep(0.1)
    await ws.send(push(CHANNELS["channel_points"], {"type": "cp_reward_demand", "data": {
        "demandId": 501, "status": "pending", "createdAt": int(time.time()),
        "reward": {"id": "rw-bad", "name": "Пакость", "price": 250, "isAutoapproved": False, "isTextRequired": False},
        "user": {"id": 55, "nick": "vasya", "displayName": "Вася"},
        "activationMessage": [text_block("сделай страшно")]}}))
    await asyncio.sleep(0.1)
    # тот же запрос через журнал действий (приватный канал) — дубль
    await ws.send(push(CHANNELS["private_info"], {"type": "actions_journal_new_event", "data": {
        "type": "reward_demand", "action_time": int(time.time()),
        "reward_demand": {"id": 501, "status": "pending", "reward": {"id": "rw-bad", "name": "Пакость", "price": 250},
                          "user": {"id": 55, "nick": "vasya"}, "message_parts": []}}}))
    await asyncio.sleep(0.1)
    for _ in range(2):
        await ws.send(push(CHANNELS["private_info"], {"type": "actions_journal_new_event", "data": {
            "type": "following", "action_time": int(time.time()),
            "follower": {"id": 9, "nick": "newbie", "displayName": "Новичок"}}}))
        await asyncio.sleep(0.1)
    await ws.send(push(CHANNELS["channel_points"], {"type": "cp_reward_demand", "data": {
        "demandId": 503, "status": "approved", "createdAt": int(time.time()),
        "reward": {"id": "rw-good", "name": "Подарок", "price": 250, "isAutoapproved": True, "isTextRequired": False},
        "user": {"id": 56, "nick": "petya", "displayName": "Петя"},
        "activationMessage": []}}))
    await asyncio.sleep(0.1)
    await ws.send(push(CHANNELS["info"], {"type": "stream_online_status", "data": {"isOnline": False, "viewers": 0}}))
    await asyncio.sleep(0.1)
    await ws.send(push(CHANNELS["info"], {"type": "stream_start", "data": {"streamId": "stream-2", "title": "Новый эфир"}}))
    await asyncio.sleep(0.1)
    await ws.send(push(CHANNELS["chat"], {"type": "viewers_count", "data": {"count": 5}}))
    await asyncio.sleep(0.1)
    await ws.send("{}")  # пинг сервера
    await asyncio.sleep(0.3)
    await ws.send(json.dumps({"push": {"channel": CHANNELS["private_info"], "unsubscribe": {"code": 2500, "reason": "test"}}}))


async def centrifugo(ws):
    STATE["ws_connections"] += 1
    conn = STATE["ws_connections"]
    WS["current"] = ws
    WS["loop"] = asyncio.get_event_loop()
    chat_started = False
    try:
        async for raw in ws:
            for line in raw.split("\n"):
                line = line.strip()
                if not line:
                    continue
                if line == "{}":
                    STATE["pings_answered"] += 1
                    continue
                msg = json.loads(line)
                mid = msg.get("id")
                if "connect" in msg:
                    token = msg["connect"].get("token", "")
                    if token != STATE["last_sock"]:
                        await ws.send(json.dumps({"id": mid, "error": {"code": 109, "message": "token expired"}}))
                        continue
                    await ws.send(json.dumps({"id": mid, "connect": {"client": "client-%d" % conn, "version": "5.4.0",
                                                                     "ping": 25, "pong": True, "expires": True, "ttl": 3}}))
                elif "subscribe" in msg:
                    channel = msg["subscribe"].get("channel", "")
                    token = msg["subscribe"].get("token", "")
                    if channel.startswith("private-") and token != "sub-" + channel:
                        await ws.send(json.dumps({"id": mid, "error": {"code": 103, "message": "permission denied"}}))
                        continue
                    STATE["subscribes"][channel] = STATE["subscribes"].get(channel, 0) + 1
                    await ws.send(json.dumps({"id": mid, "subscribe": {"recoverable": False, "epoch": "x", "positioned": False}}))
                    if channel == CHANNELS["chat"] and not chat_started:
                        chat_started = True
                        asyncio.get_event_loop().create_task(scenario(ws, conn))
                elif "refresh" in msg:
                    token = msg["refresh"].get("token", "")
                    if token != STATE["last_sock"]:
                        await ws.send(json.dumps({"id": mid, "error": {"code": 109, "message": "token expired"}}))
                        continue
                    STATE["ws_refresh_calls"] += 1
                    await ws.send(json.dumps({"id": mid, "refresh": {"client": "client-%d" % conn, "expires": True, "ttl": 3}}))
                elif "sub_refresh" in msg:
                    await ws.send(json.dumps({"id": mid, "sub_refresh": {"expires": True, "ttl": 3}}))
                else:
                    await ws.send(json.dumps({"id": mid, "error": {"code": 108, "message": "method not found"}}))
    except websockets.exceptions.ConnectionClosed:
        pass


def serve_http(port, handler):
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


async def main():
    serve_http(8085, Handler)
    async with websockets.serve(centrifugo, "127.0.0.1", 8086):
        print("mock_vk: HTTP :8085 (OAuth + API), Centrifugo :8086", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
