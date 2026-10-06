"""
Фейковый Twitch для проверки EventSubClient без настоящего аккаунта.

  ws://127.0.0.1:8080/ws   — имитация EventSub WebSocket
  http://127.0.0.1:8081    — имитация Helix API (/users, /eventsub/subscriptions, /chat/messages,
                             /channel_points/custom_rewards[/redemptions], /streams, /streams/markers, /clips)

Сценарий:
  соединение #1: welcome(S1) → notification cheer(m1) → дубликат m1 → chat message → keepalive → session_reconnect
  соединение #2 (reconnect=1): welcome(S2) → закрываем #1 → notification reward(m2) → аварийное закрытие
  соединение #3: welcome(S3) → клиент должен пересоздать подписки → notification follow(m3) → тишина > 45 c
  соединение #4: welcome(S4) → клиент снова пересоздаёт подписки (сработал keepalive-watchdog)
"""
import asyncio
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import websockets

LOG = []
SUBSCRIPTIONS = []          # (session_id, type)
CHAT_MESSAGES = []          # отправленные ботом сообщения
REDEMPTIONS = []            # (reward_id, redemption_id, status)
REWARDS = [{"id": "r1", "title": "Зомби", "cost": 300}]
MARKERS = []                # метки стрима (1.7.0)
CLIPS = []                  # созданные клипы (1.7.0)
STREAM = {"live": True}     # GET /streams → в эфире (POST /__stream {"live": false} переключает)
connections = {"count": 0}
second_connected = asyncio.Event()


def log(msg):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    LOG.append(line)


# ---------------- Helix mock ----------------
class HelixHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive, как у настоящего Twitch

    def log_message(self, *args):
        pass

    def _send(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/users"):
            if self.headers.get("Authorization") != "Bearer test-token":
                return self._send(401, {"status": 401, "message": "invalid token"})
            return self._send(200, {"data": [{"id": "42", "login": "dedworkshop", "display_name": "DedWorkshop"}]})
        if self.path == "/__subs":
            return self._send(200, {"subs": SUBSCRIPTIONS})
        if self.path == "/__state":
            return self._send(200, {"subs": SUBSCRIPTIONS, "chat": CHAT_MESSAGES, "redemptions": REDEMPTIONS, "rewards": REWARDS,
                                    "markers": MARKERS, "clips": CLIPS, "stream": STREAM})
        if self.path.startswith("/streams?"):
            if self.headers.get("Authorization") != "Bearer test-token":
                return self._send(401, {"status": 401, "message": "invalid token"})
            if "user_id=42" not in self.path:
                return self._send(400, {"status": 400, "message": "user_id required"})
            if not STREAM["live"]:
                return self._send(200, {"data": []})
            return self._send(200, {"data": [{"id": "s-1", "user_id": "42", "type": "live", "title": "Тестовый стрим",
                                              "game_name": "Minecraft", "viewer_count": 17, "started_at": "2026-10-06T10:00:00Z"}]})
        if self.path.startswith("/clips?"):
            if self.headers.get("Authorization") != "Bearer test-token":
                return self._send(401, {"status": 401, "message": "invalid token"})
            q = dict(part.split("=", 1) for part in self.path.split("?", 1)[1].split("&"))
            found = [c for c in CLIPS if c["id"] == q.get("id")]
            # первый опрос — клип ещё «не готов» (пустой data), как у настоящего Twitch
            for c in found:
                c["polls"] = c.get("polls", 0) + 1
            ready = [{"id": c["id"], "url": f"https://clips.twitch.tv/{c['id']}", "title": "Тестовый стрим"} for c in found if c.get("polls", 0) >= 2]
            return self._send(200, {"data": ready})
        if self.path.startswith("/channel_points/custom_rewards"):
            if "broadcaster_id=42" not in self.path:
                return self._send(400, {"status": 400, "message": "broadcaster_id required"})
            return self._send(200, {"data": REWARDS})
        self._send(404, {"message": "not found"})

    def do_PATCH(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path.startswith("/channel_points/custom_rewards/redemptions"):
            q = dict(part.split("=", 1) for part in self.path.split("?", 1)[1].split("&"))
            if q.get("reward_id") == "foreign":
                log("HELIX  403 PATCH redemption (чужая награда)")
                return self._send(403, {"status": 403, "message": "The reward was created by a different client_id"})
            REDEMPTIONS.append((q.get("reward_id"), q.get("id"), body.get("status")))
            log(f"HELIX  200 PATCH redemption {q.get('id')} -> {body.get('status')}")
            return self._send(200, {"data": [{"id": q.get("id"), "status": body.get("status")}]})
        self._send(404, {"message": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path.startswith("/eventsub/subscriptions"):
            if self.headers.get("Authorization") != "Bearer test-token" or self.headers.get("Client-Id") != "test-client":
                return self._send(401, {"status": 401, "message": "invalid token"})
            sub_type = body.get("type")
            session = body.get("transport", {}).get("session_id")
            cond = body.get("condition", {})
            if sub_type == "channel.raid":
                log(f"HELIX  403 for {sub_type} (намеренно)")
                return self._send(403, {"status": 403, "message": "subscription missing proper authorization"})
            if sub_type == "channel.follow" and cond.get("moderator_user_id") != "42":
                return self._send(400, {"status": 400, "message": "moderator_user_id required"})
            if sub_type == "channel.chat.message" and (cond.get("user_id") != "42" or cond.get("broadcaster_user_id") != "42"):
                return self._send(400, {"status": 400, "message": "user_id and broadcaster_user_id required"})
            SUBSCRIPTIONS.append((session, sub_type))
            log(f"HELIX  202 {sub_type} session={session} cond={cond}")
            return self._send(202, {"data": [{"id": f"sub-{len(SUBSCRIPTIONS)}", "status": "enabled", "type": sub_type}],
                                    "total": len(SUBSCRIPTIONS), "total_cost": 0, "max_total_cost": 10})
        if self.path == "/__stream":
            STREAM["live"] = bool(body.get("live", True))
            log(f"MOCK   stream live={STREAM['live']}")
            return self._send(200, STREAM)
        if self.path.startswith("/streams/markers"):
            if self.headers.get("Authorization") != "Bearer test-token":
                return self._send(401, {"status": 401, "message": "invalid token"})
            if body.get("user_id") != "42":
                return self._send(400, {"status": 400, "message": "user_id required"})
            if not STREAM["live"]:
                return self._send(404, {"status": 404, "message": "Stream is not live"})
            marker = {"id": f"m-{len(MARKERS) + 1}", "created_at": "2026-10-06T10:30:00Z", "description": body.get("description", ""),
                      "position_seconds": 1800}
            MARKERS.append(marker)
            log(f"HELIX  200 marker: {marker['description']}")
            return self._send(200, {"data": [marker]})
        if self.path.startswith("/clips?"):
            if self.headers.get("Authorization") != "Bearer test-token":
                return self._send(401, {"status": 401, "message": "invalid token"})
            if "broadcaster_id=42" not in self.path:
                return self._send(400, {"status": 400, "message": "broadcaster_id required"})
            if not STREAM["live"]:
                return self._send(404, {"status": 404, "message": "Clipping is not possible for an offline channel."})
            clip = {"id": f"TestClip{len(CLIPS) + 1}", "edit_url": f"https://clips.twitch.tv/TestClip{len(CLIPS) + 1}/edit", "polls": 0}
            CLIPS.append(clip)
            log(f"HELIX  202 clip {clip['id']}")
            return self._send(202, {"data": [{"id": clip["id"], "edit_url": clip["edit_url"]}]})
        if self.path.startswith("/chat/messages"):
            if self.headers.get("Authorization") != "Bearer test-token":
                return self._send(401, {"status": 401, "message": "invalid token"})
            CHAT_MESSAGES.append(body)
            log(f"HELIX  200 chat message: {body.get('message')}")
            return self._send(200, {"data": [{"message_id": f"cm-{len(CHAT_MESSAGES)}", "is_sent": True, "drop_reason": None}]})
        if self.path.startswith("/channel_points/custom_rewards"):
            if "broadcaster_id=42" not in self.path:
                return self._send(400, {"status": 400, "message": "broadcaster_id required"})
            if any(r["title"].lower() == body.get("title", "").lower() for r in REWARDS):
                return self._send(400, {"status": 400, "message": "CREATE_CUSTOM_REWARD_DUPLICATE_REWARD"})
            reward = {"id": f"r{len(REWARDS) + 1}", "title": body.get("title"), "cost": body.get("cost")}
            REWARDS.append(reward)
            log(f"HELIX  200 create reward {reward}")
            return self._send(200, {"data": [reward]})
        self._send(404, {"message": "not found"})


def run_helix():
    server = ThreadingHTTPServer(("127.0.0.1", 8081), HelixHandler)
    server.serve_forever()


# ---------------- EventSub mock ----------------
def msg(message_type, payload, message_id=None, sub_type=None):
    metadata = {"message_id": message_id or f"id-{time.time_ns()}", "message_type": message_type,
                "message_timestamp": "2026-10-05T10:00:00Z"}
    if sub_type:
        metadata["subscription_type"] = sub_type
        metadata["subscription_version"] = "1"
    return json.dumps({"metadata": metadata, "payload": payload})


def welcome(session_id, reconnect_url=None):
    return msg("session_welcome", {"session": {"id": session_id, "status": "connected", "keepalive_timeout_seconds": 30,
                                                "reconnect_url": reconnect_url, "connected_at": "2026-10-05T10:00:00Z"}})


def notification(message_id, sub_type, event):
    return msg("notification", {"subscription": {"id": "sub", "type": sub_type, "status": "enabled"}, "event": event},
               message_id=message_id, sub_type=sub_type)


async def handler(ws):
    connections["count"] += 1
    n = connections["count"]
    path = ws.request.path
    log(f"WS     соединение #{n} path={path}")
    try:
        if n == 1:
            await ws.send(welcome("S1"))
            await asyncio.sleep(2.0)  # клиент создаёт подписки
            cheer = {"is_anonymous": False, "user_name": "Rich", "user_login": "rich", "message": "Cheer1000 go", "bits": 1000,
                     "broadcaster_user_id": "42"}
            await ws.send(notification("m1", "channel.cheer", cheer))
            await asyncio.sleep(0.3)
            await ws.send(notification("m1", "channel.cheer", cheer))  # дубликат — должен быть проигнорирован
            chat = {"broadcaster_user_id": "42", "chatter_user_id": "7", "chatter_user_login": "modguy", "chatter_user_name": "ModGuy",
                    "message_id": "cm1", "message": {"text": "!zombie please", "fragments": []}, "message_type": "text",
                    "badges": [{"set_id": "moderator", "id": "1", "info": ""}], "color": "#1E90FF", "cheer": None}
            await ws.send(notification("c1", "channel.chat.message", chat))
            await ws.send(msg("session_keepalive", {}))
            await asyncio.sleep(0.5)
            await ws.send(msg("session_reconnect", {"session": {"id": "S1", "status": "reconnecting",
                                                                   "reconnect_url": "ws://127.0.0.1:8080/ws?reconnect=1"}}))
            await asyncio.wait_for(second_connected.wait(), timeout=15)
            await asyncio.sleep(0.5)
            log("WS     закрываю #1 (reconnect завершён)")
            await ws.close(code=4004, reason="reconnect grace time expired")
        elif n == 2:
            assert "reconnect=1" in path, "второе соединение должно идти по reconnect_url"
            await ws.send(welcome("S2"))
            second_connected.set()
            await asyncio.sleep(1.5)
            reward = {"user_name": "Viewer", "user_login": "viewer", "user_input": "", "status": "unfulfilled",
                      "reward": {"id": "r1", "title": "Зомби", "cost": 500, "prompt": ""}}
            await ws.send(notification("m2", "channel.channel_points_custom_reward_redemption.add", reward))
            await asyncio.sleep(1.0)
            log("WS     аварийно закрываю #2")
            await ws.close(code=1011, reason="simulated crash")
        elif n == 3:
            await ws.send(welcome("S3"))
            await asyncio.sleep(2.0)
            follow = {"user_id": "7", "user_login": "newbie", "user_name": "Newbie", "broadcaster_user_id": "42"}
            await ws.send(notification("m3", "channel.follow", follow))
            log("WS     #3: замолкаю на 60 секунд (проверка watchdog)")
            await asyncio.sleep(60)
        else:
            await ws.send(welcome(f"S{n}"))
            await asyncio.sleep(2.0)
            await ws.send(notification(f"m{n}", "channel.raid", {"from_broadcaster_user_name": "Big", "from_broadcaster_user_login": "big", "viewers": 99}))
            await asyncio.sleep(120)
    except websockets.ConnectionClosed as e:
        log(f"WS     #{n} закрыто клиентом: {e.code}")
    except Exception as e:
        log(f"WS     #{n} ошибка: {e!r}")
    log(f"WS     соединение #{n} завершено")


async def main():
    threading.Thread(target=run_helix, daemon=True).start()
    async with websockets.serve(handler, "127.0.0.1", 8080):
        log("Mock Twitch готов: ws://127.0.0.1:8080/ws, http://127.0.0.1:8081")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
