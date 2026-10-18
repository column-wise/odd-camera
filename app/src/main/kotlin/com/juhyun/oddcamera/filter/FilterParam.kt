package com.juhyun.oddcamera.filter

sealed interface FilterParam {
    val key: String; val label: String
    /** Shown in the panel only while sibling ChoiceParam (key, value) is selected; null = always. */
    val visibleWhen: Pair<String, String>? get() = null
}
data class FloatParam(override val key: String, override val label: String,
                      val min: Float, val max: Float, val default: Float, val step: Float,
                      override val visibleWhen: Pair<String, String>? = null) : FilterParam
data class IntParam(override val key: String, override val label: String,
                    val min: Int, val max: Int, val default: Int) : FilterParam
data class BoolParam(override val key: String, override val label: String, val default: Boolean) : FilterParam
data class ChoiceParam(override val key: String, override val label: String,
                       val options: List<Pair<String, String>>, val default: String) : FilterParam
data class TextParam(override val key: String, override val label: String,
                     val default: String, val maxCodePoints: Int) : FilterParam

/** Thread-safe value store; every access is synchronized (UI writes, GL thread reads snapshots). */
class ParamValues(private val defs: List<FilterParam>) {
    private val byKey = defs.associateBy { it.key }
    private val values = HashMap<String, Any>().apply {
        for (d in defs) put(d.key, when (d) {
            is FloatParam -> d.default; is IntParam -> d.default; is BoolParam -> d.default
            is ChoiceParam -> d.default; is TextParam -> d.default
        })
    }

    @Synchronized private fun get(key: String): Any = values[key] ?: throw IllegalArgumentException("unknown param $key")
    fun float(key: String): Float = get(key) as Float
    fun int(key: String): Int = get(key) as Int
    fun bool(key: String): Boolean = get(key) as Boolean
    fun string(key: String): String = get(key) as String

    @Synchronized fun set(key: String, value: Any) {
        val d = byKey[key] ?: throw IllegalArgumentException("unknown param $key")
        values[key] = when {
            d is FloatParam && value is Float -> value.coerceIn(d.min, d.max)
            d is IntParam && value is Int -> value.coerceIn(d.min, d.max)
            d is BoolParam && value is Boolean -> value
            d is ChoiceParam && value is String && d.options.any { it.first == value } -> value
            d is TextParam && value is String -> value.truncateCodePoints(d.maxCodePoints)
            else -> throw IllegalArgumentException("bad value for $key: $value")
        }
    }

    @Synchronized fun snapshot(): ParamValues = ParamValues(defs).also { it.values.putAll(values) }

    /** Fresh values at every default. */
    fun defaults() = ParamValues(defs)

    @Synchronized fun toStringMap(): Map<String, String> = values.mapValues { it.value.toString() }

    @Synchronized fun loadFrom(map: Map<String, String>) {
        for ((k, s) in map) {
            val parsed: Any? = when (byKey[k] ?: continue) {
                is FloatParam -> s.toFloatOrNull()?.takeIf { it.isFinite() }
                is IntParam -> s.toIntOrNull()
                is BoolParam -> s.toBooleanStrictOrNull()
                is ChoiceParam, is TextParam -> s
            }
            if (parsed != null) try { set(k, parsed) } catch (_: IllegalArgumentException) {}
        }
    }
}

private fun String.truncateCodePoints(max: Int): String =
    if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))
