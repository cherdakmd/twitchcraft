#!/bin/sh
# Запуск автотестов (нужны JDK 25 и собранный проект: ./gradlew build).
# 1) Логические тесты: события, конфиг и миграция, плейсхолдеры, кулдауны, повторы, модули, лут, цели, сборы средств, очередь действий, события игры, таймеры чата, клипы,
#    донаты, ценник донатов, случайные награды «Пакость»/«Подарок», разбор событий VK Video Live,
#    учёт квоты YouTube Data API, сообщения модерации, Retry-After и новые настройки YouTube (configVersion 10).
# 2) Логические тесты аддона «Артефакты» (ArtifactsHarness, 79): редкости, каталог (56 баффов, 13 проклятий, 35 именных артефактов),
#    рост проклятия и разрушение на 100 %, лимит артефактов, освобождение лимита «выгоревшими» записями,
#    выдача за битсы/донаты/подписки/рейды/награды/боссов, команды give/эффектов/удаления.
# 2а) Логические тесты хуков API аддонов (AddonHarness): переменные (хук 1, включая очистку значений и приоритет
#    системных плейсхолдеров), действия «триггер + элементы» (хук 2), привязка награды по id с вводом зрителя (хук 3),
#    кастомные триггеры v0…v3 (хук 4), лимиты и изоляция ошибок.
# 2б) Логические тесты боссов (BossHarness, 102): каталог 14 боссов, 11 навыков (команды, координаты, селекторы),
#    настройки боссов и их нормализация, следующий вызов по расписанию и лимит «вечного» боя.
# 3) Интеграционные тесты (34) против фейкового Twitch (python3 + pip install websockets): EventSub, Helix, метки и клипы.
# 4) Интеграционные тесты (58) против фейковых DonationAlerts / DonatePay (OAuth URL/invalid_client, продление токенов Centrifugo, unsub и догонка донатов).
# 5) Интеграционные тесты (36) против фейкового VK Video Live (OAuth code, DevAPI, Centrifugo v2: вход, события, награды, чат, 401→refresh, обрыв и догонка).
# 6) Интеграционные тесты YouTube Live (~40 секунд): refresh token, пропуск начальной истории,
#    pollingIntervalMillis/nextPageToken/maxResults, сообщения модерации и служебные типы, зрители эфира
#    (videos.list) и учёт квоты Data API, нарастающая задержка при 5xx и Retry-After при 429,
#    порог бюджета квоты, очередь отправки, управление трансляцией (transition/update) и модерация
#    (бан по нику, неоднозначный ник, удаление сообщения, разбан), ближайший upcoming-эфир для управления.
#
# Если harness зависнет (на медленных раннерах CI бывает), он не должен тянуть весь прогон:
# на один harness даётся JAVA_TIMEOUT секунд (по умолчанию 600; 0 — без ограничения).
set -e
cd "$(dirname "$0")"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GSON=$(find "$GRADLE_HOME/caches/modules-2" -name "gson-2.14.0.jar" | head -1)
SLF4J=$(find "$GRADLE_HOME/caches/modules-2" -name "slf4j-api-*.jar" | grep -v sources | head -1)
MC=$(find "$GRADLE_HOME/caches/fabric-loom/minecraftMaven" -name "minecraft-merged-deobf-26.3.jar" | head -1)
JARS=$(find "$GRADLE_HOME/caches/modules-2" -name "*.jar" | grep -v -- "-sources" | tr '\n' ':')
CLASSES=../build/classes/java/main
# Классы аддона «Артефакты» (собираются в отдельный jar из src/artifactAddon)
CLASSES_ADDON=../build/classes/java/artifactAddon

JAVA_TIMEOUT="${JAVA_TIMEOUT:-600}"
run_java() {
	if [ "$JAVA_TIMEOUT" != "0" ] && command -v timeout >/dev/null 2>&1; then
		timeout "$JAVA_TIMEOUT" java "$@"
	else
		java "$@"
	fi
}

now() {
	date -u +%H:%M:%S
}

mkdir -p out
echo "== LogicTest == ($(now))"
javac -encoding UTF-8 -cp "out:$CLASSES:$MC:$JARS" -d out stubs/net/minecraft/client/Minecraft.java stubs/net/minecraft/client/player/LocalPlayer.java LogicTest.java
run_java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out:$CLASSES:$MC:$JARS" LogicTest

echo "== ArtifactsHarness (аддон «Артефакты»: редкости, проклятия, выдача) == ($(now))"
javac -encoding UTF-8 -cp "out:$CLASSES:$CLASSES_ADDON:$MC:$JARS" -d out ArtifactsHarness.java
run_java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out:$CLASSES:$CLASSES_ADDON:$MC:$JARS" ArtifactsHarness

echo "== AddonHarness (хуки API аддонов: переменные, действия, награды по id, кастомные триггеры) == ($(now))"
# Класс лежит в пакете API: так проверяется тот же путь регистрации, которым пользуются аддоны
javac -encoding UTF-8 -cp "out:$CLASSES:$MC:$JARS" -d out AddonHarness.java
run_java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out:$CLASSES:$MC:$JARS" dev.dedworkshop.twitchcraft.api.AddonHarness

echo "== BossHarness (боссы аддона «Артефакты»: каталог 14 боссов, 11 навыков) == ($(now))"
# Класс лежит в пакете аддона — запускаем по полному имени
javac -encoding UTF-8 -cp "out:$CLASSES:$CLASSES_ADDON:$MC:$JARS" -d out BossHarness.java
run_java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out:$CLASSES:$CLASSES_ADDON:$MC:$JARS" dev.dedworkshop.twitchcraft.artifacts.BossHarness

echo "== EventSubHarness (фейковый Twitch на 127.0.0.1:8080/8081, ~80 секунд) == ($(now))"
javac -encoding UTF-8 -cp "out:$CLASSES:$MC:$JARS" -d out stubs/net/minecraft/client/Minecraft.java stubs/net/minecraft/client/player/LocalPlayer.java EventSubHarness.java DonationsHarness.java VkHarness.java YoutubeHarness.java
python3 mock_twitch.py > out/mock.log 2>&1 &
MOCK=$!
sleep 2
run_java -Dtwitchcraft.eventsubUrl=ws://127.0.0.1:8080/ws -Dtwitchcraft.helixUrl=http://127.0.0.1:8081 \
     -Dorg.apache.logging.log4j.level=WARN -cp "out:$CLASSES:$MC:$JARS" EventSubHarness || { kill $MOCK; exit 1; }
kill $MOCK

echo "== DonationsHarness (фейковые DonationAlerts :8082/:8083 и DonatePay :8084, ~25 секунд) == ($(now))"
python3 mock_donations.py > out/mock_donations.log 2>&1 &
MOCK=$!
sleep 2
run_java -Dtwitchcraft.daApiUrl=http://127.0.0.1:8082/api/v1 -Dtwitchcraft.daOauthUrl=http://127.0.0.1:8082/oauth/authorize \
     -Dtwitchcraft.daWsUrl=ws://127.0.0.1:8083/connection/websocket -Dtwitchcraft.donatePayUrl=http://127.0.0.1:8084/api/v1 \
     -Dtwitchcraft.donatePayMinIntervalMs=1000 -Dorg.apache.logging.log4j.level=WARN \
     -cp "out:$CLASSES:$MC:$JARS" DonationsHarness || { kill $MOCK; exit 1; }
kill $MOCK

echo "== VkHarness (фейковый VK Video Live :8085/:8086, ~20 секунд) == ($(now))"
python3 mock_vk.py > out/mock_vk.log 2>&1 &
MOCK=$!
sleep 2
run_java -Dtwitchcraft.vkApiUrl=http://127.0.0.1:8085/v1 -Dtwitchcraft.vkAuthUrl=http://127.0.0.1:8085/app/oauth2/authorize \
     -Dtwitchcraft.vkTokenUrl=http://127.0.0.1:8085/oauth/server/token -Dtwitchcraft.vkRevokeUrl=http://127.0.0.1:8085/oauth/server/revoke \
     -Dtwitchcraft.vkWsUrl=ws://127.0.0.1:8086/connection/websocket -Dorg.apache.logging.log4j.level=WARN \
     -cp "out:$CLASSES:$MC:$JARS" VkHarness || { kill $MOCK; exit 1; }
kill $MOCK

echo "== YoutubeHarness (фейковый YouTube Data API v3 :8087: polling, pageToken, maxResults, квота, backoff, зрители, управление эфиром и модерация) == ($(now))"
python3 mock_youtube.py > out/mock_youtube.log 2>&1 &
MOCK=$!
sleep 1
run_java -Dtwitchcraft.youtubeApiUrl=http://127.0.0.1:8087/youtube/v3 -Dtwitchcraft.youtubeTokenUrl=http://127.0.0.1:8087/oauth2/token \
     -Dtwitchcraft.youtubeRevokeUrl=http://127.0.0.1:8087/oauth2/revoke -Dorg.apache.logging.log4j.level=WARN \
     -cp "out:$CLASSES:$MC:$JARS" YoutubeHarness || { kill $MOCK; exit 1; }
kill $MOCK
echo
echo "ALL GREEN: LogicTest + ArtifactsHarness + BossHarness + AddonHarness + EventSubHarness + DonationsHarness + VkHarness + YoutubeHarness ($(now))"
