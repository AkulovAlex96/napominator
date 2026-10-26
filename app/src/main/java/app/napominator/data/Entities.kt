package app.napominator.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable enum class Kind { TASK, MEASURE }
@Serializable enum class Status { ACTIVE, PAUSED, ARCHIVED }
@Serializable enum class OccState { PENDING, DONE, SKIPPED, MISSED, SHIFTED }

/** Как срабатывает напоминание: по времени, по условию или по условию внутри временного окна. */
@Serializable enum class Trigger { TIME, CONDITION, BOTH }
@Serializable enum class CondKind { PLACE, WIFI, BLUETOOTH }

/**
 * Условие срабатывания. [enter]: true — вход в зону / подключение, false — выход / отключение.
 * Несколько условий у одного напоминания объединяются через «или».
 */
@Serializable
data class Condition(
    val kind: CondKind,
    val enter: Boolean = true,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusM: Int = 150,
    val ssid: String = "",
    val btAddress: String = "",
    val btName: String = "",
    /** Не срабатывать чаще, чем раз в N минут. */
    val minIntervalMin: Int = 60,
    /** Сработать только один раз. */
    val once: Boolean = false,
) {
    fun describe(): String {
        val verb = when (kind) {
            CondKind.PLACE -> if (enter) "прихожу в зону" else "ухожу из зоны"
            CondKind.WIFI -> if (enter) "подключаюсь к Wi-Fi" else "отключаюсь от Wi-Fi"
            CondKind.BLUETOOTH -> if (enter) "подключаюсь к" else "отключаюсь от"
        }
        val what = when (kind) {
            CondKind.PLACE -> "(%.4f, %.4f · %d м)".format(lat, lon, radiusM)
            CondKind.WIFI -> "«$ssid»"
            CondKind.BLUETOOTH -> "«${btName.ifBlank { btAddress }}»"
        }
        return "$verb $what"
    }
}

/** Одно числовое поле измерения (например «Верхнее», мм рт. ст.). */
@Serializable
data class MeasureField(
    val name: String,
    val unit: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val step: Double = 1.0,
)

@Serializable
data class MeasureConfig(
    val fields: List<MeasureField> = listOf(MeasureField("Значение")),
    /** Накопительная величина: за день значения суммируются (вода). */
    val accumulative: Boolean = false,
    val goal: Double? = null,
)

@Serializable
@Entity
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    val category: String = "",
    /** 0 — обычный, 1 — важный, 2 — критичный. */
    val priority: Int = 0,
    val kind: Kind = Kind.TASK,
    /** [Schedule] в JSON. */
    val schedule: String,
    /** [MeasureConfig] в JSON, только для Kind.MEASURE. */
    val measure: String? = null,
    /** Настойчивый режим: повторять каждые N минут, не более repeatMax раз. 0 — выключен. */
    val repeatEveryMin: Int = 0,
    val repeatMax: Int = 0,
    val status: Status = Status.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    /** Теги через запятую. */
    @ColumnInfo(defaultValue = "") val tags: String = "",
    /** Пункты чек-листа, по одному на строку. */
    @ColumnInfo(defaultValue = "") val checklist: String = "",
    @ColumnInfo(defaultValue = "TIME") val triggerMode: Trigger = Trigger.TIME,
    /** Список [Condition] в JSON. */
    val conditions: String? = null,
)

/** Конкретное срабатывание напоминания: история, ячейки таблицы привычек и календаря. */
@Serializable
@Entity(indices = [Index(value = ["reminderId", "dueAt"], unique = true), Index("epochDay")])
data class Occurrence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val dueAt: Long,
    val epochDay: Long,
    val state: OccState = OccState.PENDING,
    val resolvedAt: Long? = null,
    val comment: String? = null,
    /** Номера отмеченных пунктов чек-листа через запятую. */
    @ColumnInfo(defaultValue = "") val checked: String = "",
)

@Serializable
@Entity(indices = [Index("reminderId", "ts")])
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val ts: Long,
    val epochDay: Long,
    val field: String,
    val value: Double,
)

/** Набор строк для таблицы привычек («Здоровье», «Дом»…). */
@Serializable
@Entity
data class HabitSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** id напоминаний через запятую. */
    val reminderIds: String = "",
    /** Если не пусто, в набор автоматически входит вся категория. */
    val category: String = "",
)

@Serializable
data class Backup(
    val version: Int = 1,
    val exportedAt: Long = 0,
    val reminders: List<Reminder> = emptyList(),
    val occurrences: List<Occurrence> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    val sets: List<HabitSet> = emptyList(),
)
