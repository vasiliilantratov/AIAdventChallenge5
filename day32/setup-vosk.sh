#!/bin/bash
# Скрипт для автоматической установки модели Vosk

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║          Установка модели Vosk для голосового агента         ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Проверяем, установлена ли уже модель
if [ -d "vosk-model" ]; then
    echo "✅ Модель Vosk уже установлена в: ./vosk-model"
    echo ""
    echo "Хотите переустановить модель? (y/n)"
    read -r response
    if [ "$response" != "y" ] && [ "$response" != "Y" ]; then
        echo "Установка отменена."
        exit 0
    fi
    echo "Удаляем старую модель..."
    rm -rf vosk-model vosk-model-*
fi

echo "📥 Выберите модель для установки:"
echo ""
echo "  1. Маленькая модель (vosk-model-small-ru-0.22) - ~45 MB"
echo "     Быстрая, средняя точность, рекомендуется для тестирования"
echo ""
echo "  2. Большая модель (vosk-model-ru-0.42) - ~1.5 GB"
echo "     Медленнее, высокая точность, рекомендуется для продакшена"
echo ""
echo "  3. Английская маленькая модель (vosk-model-small-en-us-0.15) - ~40 MB"
echo "     Для английского языка"
echo ""
read -p "Ваш выбор (1-3): " choice

case $choice in
    1)
        MODEL_NAME="vosk-model-small-ru-0.22"
        MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        ;;
    2)
        MODEL_NAME="vosk-model-ru-0.42"
        MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip"
        ;;
    3)
        MODEL_NAME="vosk-model-small-en-us-0.15"
        MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        ;;
    *)
        echo "❌ Неверный выбор"
        exit 1
        ;;
esac

echo ""
echo "🔄 Скачивание модели: $MODEL_NAME"
echo "   URL: $MODEL_URL"
echo ""

# Проверяем наличие wget
if ! command -v wget &> /dev/null; then
    echo "❌ wget не установлен. Установите wget:"
    echo "   Ubuntu/Debian: sudo apt-get install wget"
    echo "   Fedora: sudo dnf install wget"
    exit 1
fi

# Скачиваем модель
wget "$MODEL_URL" -O "${MODEL_NAME}.zip"

if [ $? -ne 0 ]; then
    echo "❌ Ошибка при скачивании модели"
    exit 1
fi

echo ""
echo "📦 Распаковка модели..."

# Проверяем наличие unzip
if ! command -v unzip &> /dev/null; then
    echo "❌ unzip не установлен. Установите unzip:"
    echo "   Ubuntu/Debian: sudo apt-get install unzip"
    echo "   Fedora: sudo dnf install unzip"
    rm "${MODEL_NAME}.zip"
    exit 1
fi

unzip -q "${MODEL_NAME}.zip"

if [ $? -ne 0 ]; then
    echo "❌ Ошибка при распаковке модели"
    rm "${MODEL_NAME}.zip"
    exit 1
fi

echo ""
echo "🔄 Переименование модели в vosk-model..."

if [ -d "$MODEL_NAME" ]; then
    mv "$MODEL_NAME" vosk-model
else
    echo "❌ Ошибка: распакованная папка не найдена"
    rm "${MODEL_NAME}.zip"
    exit 1
fi

echo ""
echo "🧹 Очистка архива..."
rm "${MODEL_NAME}.zip"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "✅ Модель Vosk успешно установлена!"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "📂 Путь к модели: ./vosk-model"
echo "📏 Размер: $(du -sh vosk-model | cut -f1)"
echo ""
echo "Следующие шаги:"
echo "  1. Запустите голосовой агент:"
echo "     ./voice.sh"
echo ""
echo "  2. Или через Gradle:"
echo "     ./gradlew run --args='voice'"
echo ""
echo "Подробная документация: VOICE_SETUP.md"
echo ""
