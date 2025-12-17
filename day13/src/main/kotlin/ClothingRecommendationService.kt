package org.example

/**
 * Сервис для генерации рекомендаций по одежде на основе погодных условий
 */
class ClothingRecommendationService {
    
    /**
     * Генерирует рекомендации по одежде на основе погодных данных
     */
    fun generateRecommendation(
        tempC: Double,
        condition: String,
        windKph: Double,
        uv: Double,
        precipMm: Double
    ): String {
        val conditionLower = condition.lowercase()
        
        val recommendations = mutableListOf<String>()
        
        // Рекомендации по температуре
        when {
            tempC < -10 -> {
                recommendations.add("Очень холодно. Рекомендуется: теплая зимняя куртка, шапка, перчатки, шарф, теплая обувь")
            }
            tempC < 0 -> {
                recommendations.add("Холодно. Рекомендуется: теплая куртка, шапка, перчатки, теплая обувь")
            }
            tempC < 10 -> {
                recommendations.add("Прохладно. Рекомендуется: куртка или пальто, теплая одежда")
            }
            tempC < 20 -> {
                recommendations.add("Прохладно. Рекомендуется: легкая куртка или свитер")
            }
            tempC < 25 -> {
                recommendations.add("Комфортно. Рекомендуется: легкая одежда, можно без куртки")
            }
            else -> {
                recommendations.add("Тепло. Рекомендуется: легкая одежда, головной убор от солнца")
            }
        }
        
        // Рекомендации по осадкам
        if (precipMm > 0 || conditionLower.contains("дождь") || conditionLower.contains("rain") || 
            conditionLower.contains("снег") || conditionLower.contains("snow")) {
            if (conditionLower.contains("снег") || conditionLower.contains("snow")) {
                recommendations.add("Идет снег: наденьте непромокаемую обувь и верхнюю одежду")
            } else {
                recommendations.add("Ожидаются осадки: возьмите зонт или дождевик")
            }
        }
        
        // Рекомендации по ветру
        if (windKph > 20) {
            recommendations.add("Сильный ветер: рекомендуется ветровка или куртка с капюшоном")
        } else if (windKph > 10) {
            recommendations.add("Ветрено: можно добавить легкую ветровку")
        }
        
        // Рекомендации по UV индексу
        when {
            uv >= 8 -> {
                recommendations.add("Очень высокий UV индекс: обязательно используйте солнцезащитный крем SPF 50+, солнцезащитные очки и головной убор")
            }
            uv >= 6 -> {
                recommendations.add("Высокий UV индекс: используйте солнцезащитный крем и солнцезащитные очки")
            }
            uv >= 3 -> {
                recommendations.add("Умеренный UV индекс: рекомендуется солнцезащитный крем при длительном пребывании на солнце")
            }
        }
        
        return if (recommendations.isNotEmpty()) {
            recommendations.joinToString("\n   ")
        } else {
            "Одевайтесь по погоде"
        }
    }
}

