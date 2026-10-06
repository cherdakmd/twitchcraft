#!/bin/sh
# Запуск автотестов (нужны JDK 25 и собранный проект: ./gradlew build).
# 1) Логические тесты (339): события, конфиг и миграция, плейсхолдеры, кулдауны, повторы, модули, лут, цели, сборы средств, очередь действий, события игры, таймеры чата, клипы,
#    донаты, ценник донатов, случайные награды «Пакость»/«Подарок», разбор событий VK Video Live.
# 2) Логические тесты аддона «Артефакты» (ArtifactsHarness): редкости, каталог (56 баффов, 13 проклятий, 35 именных артефактов),
#    рост проклятия и разрушение на 100 %, лимит артефактов, выдача за битсы/донаты/подписки/рейды/награды/боссов, команды give/эффектов/удаления.
# 3) Интеграционные тесты (34) против фейкового Twitch (python3 + pip install websockets): EventSub, Helix, метки и клипы.
# 3) Интеграционные тесты (58) против фейковых DonationAlerts / DonatePay (OAuth URL/invalid_client, продление токенов Centrifugo, unsub и догонка донатов).
# 4) Интеграционные тесты (36) против фейкового VK Video Live (OAuth code, DevAPI, Centrifugo v2: вход, события, награды, чат, 401→refresh, обрыв и догонка).
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

mkdir -p out
echo "== LogicTest =="
javac -encoding UTF-8 -cp "out:$CLASSES:$MC:$JARS" -d out stubs/net/minecraft/client/Minecraft.java stubs/net/minecraft/client/player/LocalPlayer.java LogicTest.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out:$CLASSES:$MC:$JARS" LogicTest

echo "== ArtifactsHarness (аддон «Артефакты»: редкости, проклятия, выдача) =="
javac -encoding UTF-8 -cp "out:$CLASSES:$CLASSES_ADDON:$MC:$JARS" -d out ArtifactsHarness.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out:$CLASSES:$CLASSES_ADDON:$MC:$JARS" ArtifactsHarness

echo "== EventSubHarness (фейковый Twitch на 127.0.0.1:8080/8081, ~80 секунд) =="
javac -encoding UTF-8 -cp "out:$CLASSES:$MC:$JARS" -d out stubs/net/minecraft/client/Minecraft.java stubs/net/minecraft/client/player/LocalPlayer.java EventSubHarness.java DonationsHarness.java VkHarness.java
python3 mock_twitch.py > out/mock.log 2>&1 &
MOCK=$!
sleep 2
java -Dtwitchcraft.eventsubUrl=ws://127.0.0.1:8080/ws -Dtwitchcraft.helixUrl=http://127.0.0.1:8081 \
     -Dorg.apache.logging.log4j.level=WARN -cp "out:$CLASSES:$MC:$JARS" EventSubHarness || { kill $MOCK; exit 1; }
kill $MOCK

echo "== DonationsHarness (фейковые DonationAlerts :8082/:8083 и DonatePay :8084, ~25 секунд) =="
python3 mock_donations.py > out/mock_donations.log 2>&1 &
MOCK=$!
sleep 2
java -Dtwitchcraft.daApiUrl=http://127.0.0.1:8082/api/v1 -Dtwitchcraft.daOauthUrl=http://127.0.0.1:8082/oauth/authorize \
     -Dtwitchcraft.daWsUrl=ws://127.0.0.1:8083/connection/websocket -Dtwitchcraft.donatePayUrl=http://127.0.0.1:8084/api/v1 \
     -Dtwitchcraft.donatePayMinIntervalMs=1000 -Dorg.apache.logging.log4j.level=WARN \
     -cp "out:$CLASSES:$MC:$JARS" DonationsHarness || { kill $MOCK; exit 1; }
kill $MOCK

echo "== VkHarness (фейковый VK Video Live :8085/:8086, ~20 секунд) =="
python3 mock_vk.py > out/mock_vk.log 2>&1 &
MOCK=$!
sleep 2
java -Dtwitchcraft.vkApiUrl=http://127.0.0.1:8085/v1 -Dtwitchcraft.vkAuthUrl=http://127.0.0.1:8085/app/oauth2/authorize \
     -Dtwitchcraft.vkTokenUrl=http://127.0.0.1:8085/oauth/server/token -Dtwitchcraft.vkRevokeUrl=http://127.0.0.1:8085/oauth/server/revoke \
     -Dtwitchcraft.vkWsUrl=ws://127.0.0.1:8086/connection/websocket -Dorg.apache.logging.log4j.level=WARN \
     -cp "out:$CLASSES:$MC:$JARS" VkHarness || { kill $MOCK; exit 1; }
kill $MOCK
echo
echo "ALL GREEN: LogicTest + EventSubHarness + DonationsHarness + VkHarness"
