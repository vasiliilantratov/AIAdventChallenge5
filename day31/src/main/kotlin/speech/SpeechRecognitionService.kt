package org.example.speech

import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.sound.sampled.*

/**
 * Сервис для распознавания речи с использованием Vosk.
 * Поддерживает захват аудио с микрофона и распознавание в реальном времени.
 */
class SpeechRecognitionService(
    private val modelPath: String = "./vosk-model"
) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    
    /**
     * Инициализация модели Vosk.
     * @throws IllegalStateException если модель не найдена
     */
    fun initialize() {
        val modelDir = File(modelPath)
        if (!modelDir.exists()) {
            throw IllegalStateException(
                """
                Модель Vosk не найдена в директории: $modelPath
                
                Пожалуйста, скачайте модель:
                1. Перейдите на https://alphacephei.com/vosk/models
                2. Скачайте модель для русского языка, например:
                   vosk-model-small-ru-0.22 (маленькая, ~45MB)
                   vosk-model-ru-0.42 (большая, ~1.5GB, точнее)
                3. Распакуйте архив и переименуйте папку в 'vosk-model'
                4. Поместите папку в корень проекта
                """.trimIndent()
            )
        }
        
        println("🔄 Загрузка модели Vosk из: $modelPath")
        model = Model(modelPath)
        println("✅ Модель успешно загружена")
        
        // Создаем распознаватель с частотой дискретизации 16000 Hz
        recognizer = Recognizer(model, 16000f)
    }
    
    /**
     * Проверяет доступность микрофона.
     * @return true если микрофон доступен
     */
    fun isMicrophoneAvailable(): Boolean {
        return try {
            val format = AudioFormat(16000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            AudioSystem.isLineSupported(info)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Получает список доступных микрофонов.
     * @return список названий микрофонов
     */
    fun getAvailableMicrophones(): List<String> {
        val mixers = AudioSystem.getMixerInfo()
        return mixers.mapNotNull { mixerInfo ->
            val mixer = AudioSystem.getMixer(mixerInfo)
            val format = AudioFormat(16000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            
            if (mixer.isLineSupported(info)) {
                mixerInfo.name
            } else {
                null
            }
        }
    }
    
    /**
     * Запускает распознавание речи с микрофона.
     * Слушает микрофон до тех пор, пока пользователь не нажмет Enter.
     * 
     * @param onPartialResult колбэк для частичных результатов (в процессе речи)
     * @param onFinalResult колбэк для финального результата (после паузы)
     * @return распознанный текст
     */
    fun recognizeFromMicrophone(
        onPartialResult: (String) -> Unit = {},
        onFinalResult: (String) -> Unit = {}
    ): String {
        if (model == null || recognizer == null) {
            throw IllegalStateException("Сначала вызовите initialize()")
        }
        
        if (!isMicrophoneAvailable()) {
            throw IllegalStateException("Микрофон недоступен")
        }
        
        // Настройка аудио формата (16kHz, 16 bit, mono)
        val format = AudioFormat(16000f, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        
        val microphone = AudioSystem.getLine(info) as TargetDataLine
        microphone.open(format)
        microphone.start()
        
        println("🎤 Микрофон активирован. Говорите...")
        println("   (нажмите Enter для остановки записи)")
        
        val buffer = ByteArray(4096)
        var isRecording = true
        val fullText = StringBuilder()
        
        // Поток для захвата аудио
        val recordingThread = Thread {
            try {
                while (isRecording) {
                    val bytesRead = microphone.read(buffer, 0, buffer.size)
                    if (bytesRead > 0 && recognizer!!.acceptWaveForm(buffer, bytesRead)) {
                        // Финальный результат (после паузы в речи)
                        val result = recognizer!!.result
                        val text = parseVoskResult(result)
                        
                        if (text.isNotBlank()) {
                            fullText.append(text).append(" ")
                            onFinalResult(text)
                            print("\r🎯 Распознано: ${fullText.toString().trim()}")
                            System.out.flush()
                        }
                    } else if (bytesRead > 0) {
                        // Частичный результат (в процессе речи)
                        val partial = recognizer!!.partialResult
                        val text = parseVoskPartialResult(partial)
                        
                        if (text.isNotBlank()) {
                            onPartialResult(text)
                            print("\r💭 Слышу: $text")
                            System.out.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                println("\n❌ Ошибка при записи: ${e.message}")
            }
        }
        
        recordingThread.start()
        
        // Ждем нажатия Enter для остановки
        readLine()
        isRecording = false
        
        // Получаем финальный результат
        val finalResult = recognizer!!.finalResult
        val finalText = parseVoskResult(finalResult)
        if (finalText.isNotBlank()) {
            fullText.append(finalText)
        }
        
        recordingThread.join(1000)
        microphone.stop()
        microphone.close()
        
        // Сбрасываем распознаватель для следующего использования
        recognizer!!.reset()
        
        val result = fullText.toString().trim()
        println("\n\n✅ Запись завершена")
        return result
    }
    
    /**
     * Распознавание речи из аудио файла.
     * @param audioFilePath путь к аудио файлу (WAV, 16kHz, mono)
     * @return распознанный текст
     */
    fun recognizeFromFile(audioFilePath: String): String {
        if (model == null || recognizer == null) {
            throw IllegalStateException("Сначала вызовите initialize()")
        }
        
        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) {
            throw IllegalArgumentException("Аудио файл не найден: $audioFilePath")
        }
        
        println("🔄 Распознавание из файла: $audioFilePath")
        
        val audioInputStream = AudioSystem.getAudioInputStream(audioFile)
        val buffer = ByteArray(4096)
        val fullText = StringBuilder()
        
        var bytesRead: Int
        while (audioInputStream.read(buffer).also { bytesRead = it } >= 0) {
            if (recognizer!!.acceptWaveForm(buffer, bytesRead)) {
                val result = recognizer!!.result
                val text = parseVoskResult(result)
                if (text.isNotBlank()) {
                    fullText.append(text).append(" ")
                }
            }
        }
        
        // Финальный результат
        val finalResult = recognizer!!.finalResult
        val finalText = parseVoskResult(finalResult)
        if (finalText.isNotBlank()) {
            fullText.append(finalText)
        }
        
        audioInputStream.close()
        recognizer!!.reset()
        
        println("✅ Распознавание завершено")
        return fullText.toString().trim()
    }
    
    /**
     * Парсит JSON результат от Vosk и извлекает распознанный текст.
     */
    private fun parseVoskResult(jsonResult: String): String {
        return try {
            val json = Json.parseToJsonElement(jsonResult).jsonObject
            json["text"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Парсит частичный JSON результат от Vosk.
     */
    private fun parseVoskPartialResult(jsonPartial: String): String {
        return try {
            val json = Json.parseToJsonElement(jsonPartial).jsonObject
            json["partial"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Освобождает ресурсы.
     */
    fun close() {
        recognizer?.close()
        model?.close()
    }
}
