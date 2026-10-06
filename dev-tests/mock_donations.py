#!/usr/bin/env python3
"""Фейковые DonationAlerts (API + Centrifugo) и DonatePay (API) для DonationsHarness.

Порты: 8082 — DonationAlerts API, 8083 — Centrifugo WebSocket, 8084 — DonatePay API.
Сценарий Centrifugo (на каждое соединение):
  connect(token sock-1) -> ok; subscribe($alerts:donation_42, chan-jwt) -> ok + join push
  -> донат id 77 (500 RUB), повтор того же id 77, батч из двух сообщений ({} и донат id 78 в USD с конвертацией),
  -> первое соединение сервер закрывает (проверка переподключения); второе шлёт донат id 79 и живёт дальше.
  Со второго соединения сервер выдаёт токены с expires/ttl=2 с: клиент обязан слать refresh (10) и sub_refresh (11),
  не переподключаясь. GET /__unsub шлёт push unsub (клиент должен переподписаться на том же соединении — придёт донат 81),
  GET /__close закрывает текущее соединение (клиент переподключается и «догоняет» донат 83 через GET /alerts/donations).
Управление: GET http://127.0.0.1:8082/__reject (все дальнейшие запросы с токеном -> 401), GET /__state.
"""
import asyncio
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import websockets

STATE = {
    "reject": False,
    "ws_connections": 0,
    "subscribe_calls": 0,
    "oauth_calls": 0,
    "refresh_calls": 0,
    "sub_refresh_calls": 0,
    "resubscribes": 0,
    "donations_calls": 0,
    "last_sock": "sock-1",
    "dp_calls": [],
    "dp_tx_calls": 0,
    "dp_user_calls": 0,
}

DA_TOKEN = "da-token"
DP_KEY = "dp-key"
WS = {"current": None, "loop": None, "subscribed": False}


class DAHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth_ok(self):
        auth = self.headers.get("Authorization", "")
        return auth == "Bearer " + DA_TOKEN and not STATE["reject"]

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/__reject":
            STATE["reject"] = True
            return self._send(200, {"ok": True})
        if path == "/__state":
            return self._send(200, STATE)
        if path == "/__unsub":
            ws = WS["current"]
            if ws is not None and WS["loop"] is not None:
                WS["subscribed"] = False
                push = json.dumps({"result": {"type": 3, "channel": "$alerts:donation_42", "data": {"resubscribe": True}}})
                asyncio.run_coroutine_threadsafe(ws.send(push), WS["loop"])
            return self._send(200, {"ok": True})
        if path == "/__close":
            ws = WS["current"]
            if ws is not None and WS["loop"] is not None:
                asyncio.run_coroutine_threadsafe(ws.close(code=3005, reason="expired"), WS["loop"])
            return self._send(200, {"ok": True})
        if path == "/api/v1/user/oauth":
            STATE["oauth_calls"] += 1
            if not self._auth_ok():
                return self._send(401, {"message": "Unauthenticated."})
            STATE["last_sock"] = "sock-%d" % STATE["oauth_calls"]  # каждый запрос профиля — новый токен сокета
            return self._send(200, {"data": {"id": 42, "code": "streamer", "name": "Streamer", "avatar": "",
                                             "email": "s@example.com", "socket_connection_token": STATE["last_sock"]}})
        if path == "/api/v1/alerts/donations":
            STATE["donations_calls"] += 1
            if not self._auth_ok():
                return self._send(401, {"message": "Unauthenticated."})
            # «Пропущенный» донат 83 появляется в истории только после обрыва второго соединения
            items = [donation_obj(10, "Ancient", 5000, "RUB", "Старый донат из истории")]
            if STATE["ws_connections"] >= 3:
                items = [donation_obj(83, "Missed", 300, "RUB", "Пока сокет был мёртв"),
                         donation_obj(81, "Resub", 200, "RUB", "После переподписки")] + items
            return self._send(200, {"data": items, "links": {}, "meta": {"per_page": 30}})
        return self._send(404, {"message": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8") if length else ""
        if path == "/api/v1/centrifuge/subscribe":
            STATE["subscribe_calls"] += 1
            if not self._auth_ok():
                return self._send(401, {"message": "Unauthenticated."})
            req = json.loads(body)
            if not req.get("client"):
                return self._send(422, {"message": "client required"})
            channels = [{"channel": ch, "token": "chan-jwt"} for ch in req.get("channels", [])]
            return self._send(200, {"channels": channels})
        return self._send(404, {"message": "not found"})


class DPHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        key = (q.get("access_token") or [""])[0]
        now = time.time()
        STATE["dp_calls"].append(now)
        # лимит DonatePay: раз в 20 с; в тесте — раз в 1 с (harness задаёт twitchcraft.donatePayMinIntervalMs=1000)
        if len(STATE["dp_calls"]) >= 2 and now - STATE["dp_calls"][-2] < 0.9:
            return self._send(200, {"status": "error", "message": "Too Many Attempts."})
        if key != DP_KEY:
            return self._send(200, {"status": "error", "message": "Wrong access token"})
        if url.path == "/api/v1/user":
            STATE["dp_user_calls"] += 1
            return self._send(200, {"status": "success", "message": "", "time": int(now),
                                    "data": {"id": 777, "name": "DpStreamer", "avatar": "", "balance": 10, "cashout_sum": 0}})
        if url.path == "/api/v1/transactions":
            STATE["dp_tx_calls"] += 1
            n = STATE["dp_tx_calls"]
            order = (q.get("order") or ["ASC"])[0]
            after = int((q.get("after") or ["0"])[0])
            if order == "DESC":
                # самый свежий платёж на момент подключения ещё «в ожидании» — мод должен его дождаться (1.3.2)
                data = [tx(100, "wait", "Late", "70.00", "в ожидании при старте")]
            elif n == 2:
                data = [tx(100, "success", "Late", "70.00", "оплачен после старта"),
                        tx(101, "success", "Alice", "150.00", "первый новый"), tx(102, "wait", "Bob", "99.99", "ещё платит")]
            elif n == 3:
                data = [tx(102, "success", "Bob", "99.99", "оплатил"), tx(103, "user", "Tester", "5.00", "тест из кабинета"),
                        tx(104, "cancel", "Carl", "500.00", "отменён")]
            else:
                data = []
            data = [d for d in data if d["id"] > after] if order == "ASC" else data
            return self._send(200, {"status": "success", "message": "", "time": int(now), "sum": "0", "count": len(data), "data": data})
        return self._send(404, {"status": "error", "message": "not found"})


def tx(i, status, name, s, comment):
    return {"id": i, "what": name, "sum": s, "commission": "0", "status": status, "type": "donation",
            "vars": {"name": name, "comment": comment}, "comment": comment,
            "created_at": {"date": "2026-10-05 12:00:00.000000", "timezone_type": 3, "timezone": "Europe/Moscow"}}


def donation_obj(i, username, amount, currency, message, extra=None):
    d = {"id": i, "name": "donation", "username": username, "message_type": "text", "message": message,
         "amount": amount, "currency": currency, "is_shown": 0, "created_at": "2026-10-05 12:00:00", "shown_at": None}
    if extra:
        d.update(extra)
    return d


def donation(i, username, amount, currency, message, extra=None):
    return json.dumps({"result": {"channel": "$alerts:donation_42", "data": {"data": donation_obj(i, username, amount, currency, message, extra)}}})


async def centrifugo(ws):
    STATE["ws_connections"] += 1
    conn = STATE["ws_connections"]
    WS["current"] = ws
    WS["loop"] = asyncio.get_event_loop()
    WS["subscribed"] = False
    expiring = conn >= 2  # со второго соединения — короткоживущие токены, как у настоящего DonationAlerts
    ttl = {"expires": True, "ttl": 2} if expiring else {}
    try:
        async for raw in ws:
            for line in raw.split("\n"):
                line = line.strip()
                if not line:
                    continue
                if line == "{}":
                    continue  # ответ на наш ping-пустышку
                msg = json.loads(line)
                mid = msg.get("id")
                method = msg.get("method", 0)
                params = msg.get("params", {})
                if mid == 1 and method == 0:
                    if not str(params.get("token", "")).startswith("sock-"):
                        await ws.send(json.dumps({"id": 1, "error": {"code": 109, "message": "token expired"}}))
                        continue
                    result = {"client": "client-uuid-%d" % conn, "version": "2.2.1"}
                    result.update(ttl)
                    await ws.send(json.dumps({"id": 1, "result": result}))
                elif method == 1:
                    if params.get("token") != "chan-jwt" or params.get("channel") != "$alerts:donation_42":
                        await ws.send(json.dumps({"id": mid, "error": {"code": 103, "message": "permission denied"}}))
                        continue
                    first = not WS["subscribed"] and not WS.get("ever_subscribed_%d" % conn)
                    WS["subscribed"] = True
                    WS["ever_subscribed_%d" % conn] = True
                    await ws.send(json.dumps({"id": mid, "result": dict(ttl)}))
                    await ws.send(json.dumps({"result": {"type": 1, "channel": "$alerts:donation_42",
                                                         "data": {"info": {"user": "42", "client": "client-uuid-%d" % conn}}}}))
                    if first:
                        asyncio.get_event_loop().create_task(scenario(ws, conn))
                    else:
                        STATE["resubscribes"] += 1
                        asyncio.get_event_loop().create_task(after_resubscribe(ws))
                elif method == 7:
                    await ws.send(json.dumps({"id": mid, "result": {}}))
                elif method == 10:
                    if params.get("token") != STATE["last_sock"]:
                        await ws.send(json.dumps({"id": mid, "error": {"code": 109, "message": "token expired"}}))
                        continue
                    STATE["refresh_calls"] += 1
                    await ws.send(json.dumps({"id": mid, "result": {"expires": True, "ttl": 2}}))
                elif method == 11:
                    if params.get("token") != "chan-jwt" or params.get("channel") != "$alerts:donation_42":
                        await ws.send(json.dumps({"id": mid, "error": {"code": 103, "message": "permission denied"}}))
                        continue
                    STATE["sub_refresh_calls"] += 1
                    await ws.send(json.dumps({"id": mid, "result": {"expires": True, "ttl": 2}}))
                else:
                    await ws.send(json.dumps({"id": mid, "error": {"code": 108, "message": "method not found"}}))
    except websockets.exceptions.ConnectionClosed:
        pass


async def after_resubscribe(ws):
    await asyncio.sleep(0.2)
    await ws.send(donation(81, "Resub", 200, "RUB", "После переподписки"))


async def scenario(ws, conn):
    await asyncio.sleep(0.3)
    if conn == 1:
        await ws.send(donation(77, "Donor", 500, "RUB", "Первый донат", {"amount_in_user_currency": 500}))
        await asyncio.sleep(0.2)
        await ws.send(donation(77, "Donor", 500, "RUB", "Повтор того же доната"))
        await asyncio.sleep(0.2)
        # батч: ping-пустышка + донат в другой валюте в одном фрейме
        await ws.send("{}\n" + donation(78, "Foreigner", 10, "USD", "Из-за рубежа", {"amount_in_user_currency": 950.5}))
        await asyncio.sleep(0.5)
        await ws.close(code=1000, reason="restart")
    elif conn == 2:
        await ws.send(donation(79, "Returning", 1000, "RUB", "После переподключения"))
        await asyncio.sleep(0.3)
        await ws.send(donation(80, "Tiny", 0.5, "RUB", "меньше minAmount"))
    # conn 3+: сервер молчит — пропущенный донат 83 клиент должен забрать сам через GET /alerts/donations


def serve_http(port, handler):
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


async def main():
    serve_http(8082, DAHandler)
    serve_http(8084, DPHandler)
    async with websockets.serve(centrifugo, "127.0.0.1", 8083):
        print("mock_donations: DA API :8082, Centrifugo :8083, DonatePay :8084", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
