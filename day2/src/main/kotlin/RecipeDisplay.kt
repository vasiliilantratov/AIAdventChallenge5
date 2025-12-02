package org.example

/**
 * Функции для отображения рецепта пользователю
 */
object RecipeDisplay {
    /**
     * Отображает рецепт в удобном формате
     */
    fun displayRecipe(recipe: Recipe) {
        println()
        println("=".repeat(60))
        println("Блюдо: ${recipe.dish}")
        println("Порций: ${recipe.servings}")
        println("=".repeat(60))
        println()
        
        // Заголовок таблицы
        println("Ингредиент".padEnd(30) + "Количество".padEnd(15) + "Ед. изм.")
        println("-".repeat(60))
        
        // Выводим каждый ингредиент
        recipe.ingredients.forEach { ingredient ->
            val amountStr = if (ingredient.amount == ingredient.amount.toInt().toDouble()) {
                ingredient.amount.toInt().toString()
            } else {
                String.format("%.2f", ingredient.amount)
            }
            
            println(
                ingredient.name.padEnd(30) +
                amountStr.padEnd(15) +
                ingredient.unit
            )
        }
        
        println()
    }
    
    /**
     * Отображает сообщение об ошибке
     */
    fun displayError(message: String) {
        println()
        println("❌ Ошибка: $message")
        println("Не удалось получить список ингредиентов, попробуйте ещё раз.")
        println()
    }
}

