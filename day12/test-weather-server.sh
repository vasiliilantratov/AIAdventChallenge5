#!/bin/bash

# Тестовый скрипт для проверки работы Weather MCP Server

echo "Building weather server..."
./gradlew :weather-server:installDist --quiet

echo ""
echo "Testing weather server..."
echo ""

# Test 1: Initialize
echo "Test 1: Initialize"
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"test","version":"1.0"}}}' | \
timeout 2 weather-server/build/install/weather-server/bin/weather-server 2>/dev/null | grep -v "Weather MCP Server started" | grep -v "Received:" | grep -v "Sent:"

echo ""
echo "---"
echo ""

# Test 2: List tools
echo "Test 2: List tools"
(
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"test","version":"1.0"}}}'
  sleep 0.1
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  sleep 0.1
) | timeout 3 weather-server/build/install/weather-server/bin/weather-server 2>/dev/null | grep '"method":"tools/list"' -A 5

echo ""
echo "---"
echo ""

echo "Test 3: Get weather for Moscow"
(
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"test","version":"1.0"}}}'
  sleep 0.1
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  sleep 0.1
  echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_current_weather","arguments":{"city":"Moscow"}}}'
  sleep 2
) | timeout 5 weather-server/build/install/weather-server/bin/weather-server 2>/dev/null | tail -1 | jq '.result.content[0].text' | head -c 200

echo ""
echo ""
echo "Tests completed!"

