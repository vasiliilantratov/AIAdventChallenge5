package org.example

import kotlinx.serialization.Serializable

/**
 * Модель данных для ингредиента
 */
@Serializable
data class Ingredient(
    val name: String,
    val amount: Double,
    val unit: String
)

/**
 * Модель данных для рецепта
 */
@Serializable
data class Recipe(
    val dish: String,
    val servings: Int,
    val ingredients: List<Ingredient>
)

/**
 * Результат валидации рецепта
 */
sealed class RecipeValidationResult {
    data class Success(val recipe: Recipe) : RecipeValidationResult()
    data class Error(val message: String) : RecipeValidationResult()
}

/**
 * Валидирует распарсенный JSON и возвращает Recipe или ошибку
 */
fun validateRecipe(recipe: Recipe): RecipeValidationResult {
    // Проверка обязательных полей верхнего уровня
    if (recipe.dish.isBlank()) {
        return RecipeValidationResult.Error("Поле 'dish' отсутствует или пустое")
    }
    
    if (recipe.servings <= 0) {
        return RecipeValidationResult.Error("Поле 'servings' должно быть положительным числом")
    }
    
    if (recipe.ingredients.isEmpty()) {
        return RecipeValidationResult.Error("Список ингредиентов не может быть пустым")
    }
    
    // Проверка каждого ингредиента
    recipe.ingredients.forEachIndexed { index, ingredient ->
        if (ingredient.name.isBlank()) {
            return RecipeValidationResult.Error("Ингредиент #${index + 1}: поле 'name' отсутствует или пустое")
        }
        
        if (ingredient.amount <= 0) {
            return RecipeValidationResult.Error("Ингредиент #${index + 1} (${ingredient.name}): поле 'amount' должно быть положительным числом")
        }
        
        if (ingredient.unit.isBlank()) {
            return RecipeValidationResult.Error("Ингредиент #${index + 1} (${ingredient.name}): поле 'unit' отсутствует или пустое")
        }
    }
    
    return RecipeValidationResult.Success(recipe)
}

