package kr.mom.probe.agent

enum class ScheduleTarget {
    CALENDAR,
    EXTERNAL_ALARM,
}

enum class ScheduleClarificationKind {
    AM_PM,
    DATE,
    TIME,
    TITLE,
    END_TIME,
    UNSUPPORTED,
}

data class SchedulePayload(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val zoneId: String,
    val explicitEnd: Boolean,
    val defaultDurationMinutes: Int,
)

sealed interface ScheduleCommand {
    val rawText: String
}

data class CalendarCreateCommand(
    override val rawText: String,
    val payload: SchedulePayload,
) : ScheduleCommand

data class AlarmRequestCommand(
    override val rawText: String,
    val payload: SchedulePayload,
) : ScheduleCommand

data class ScheduleClarification(
    override val rawText: String,
    val kind: ScheduleClarificationKind,
    val message: String,
    val amText: String? = null,
    val pmText: String? = null,
) : ScheduleCommand

data class ScheduleNoMutation(
    override val rawText: String,
    val reason: String,
) : ScheduleCommand
