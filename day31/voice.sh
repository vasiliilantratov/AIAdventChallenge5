#!/bin/bash
# Скрипт для быстрого запуска голосового агента

echo "🎤 Запуск голосового агента..."
echo ""

# Проверка наличия модели Vosk
if [ ! -d "vosk-model" ]; then
    echo "⚠️  Модель Vosk не найдена!"
    echo ""
    echo "Для установки модели выполните:"
    echo ""
    echo "  wget https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    echo "  unzip vosk-model-small-ru-0.22.zip"
    echo "  mv vosk-model-small-ru-0.22 vosk-model"
    echo ""
    echo "Подробности смотрите в VOICE_SETUP.md"
    echo ""
    exit 1
fi

# Запуск приложения
./gradlew run --args="voice" --console=plain
