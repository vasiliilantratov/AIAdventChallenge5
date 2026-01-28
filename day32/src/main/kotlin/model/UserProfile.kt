package org.example.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val user: UserInfo,
    val preferences: Preferences,
    val constraints: Constraints? = null,
    val goals: Goals? = null,
    @SerialName("agent_behavior")
    val agentBehavior: AgentBehavior? = null,
    val priorities: Priorities? = null
)

@Serializable
data class UserInfo(
    val name: String,
    val timezone: String? = null,
    val language: String? = null
)

@Serializable
data class Preferences(
    val communication: CommunicationPreferences,
    val learning: LearningPreferences? = null
)

@Serializable
data class CommunicationPreferences(
    val addressing: String? = null,
    val tone: String? = null,
    val verbosity: String? = null,
    @SerialName("format_preferences")
    val formatPreferences: List<String>? = null,
    val avoid: List<String>? = null
)

@Serializable
data class LearningPreferences(
    val style: List<String>? = null,
    val pace: String? = null
)

@Serializable
data class Constraints(
    @SerialName("time_per_day_minutes")
    val timePerDayMinutes: Int? = null,
    @SerialName("days_per_week")
    val daysPerWeek: Int? = null
)

@Serializable
data class Goals(
    val main: List<String>? = null
)

@Serializable
data class AgentBehavior(
    @SerialName("should_do")
    val shouldDo: List<String>? = null,
    @SerialName("should_not_do")
    val shouldNotDo: List<String>? = null
)

@Serializable
data class Priorities(
    @SerialName("current_focus")
    val currentFocus: String? = null,
    val secondary: List<String>? = null
)
