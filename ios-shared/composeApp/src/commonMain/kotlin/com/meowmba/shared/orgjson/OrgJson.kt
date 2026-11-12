package com.meowmba.shared.orgjson

import kotlinx.serialization.json.*

/**
 * The subset of Android's org.json that ported drawing-language code uses, over kotlinx JSON.
 * Values: String, Boolean, Long/Double (as Number), JSONObject, JSONArray, or null.
 */
class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(text: String) : this() {
        val e = Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: throw IllegalArgumentException("Not a JSON object")
        e.forEach { (k, v) -> map[k] = fromElement(v) }
    }

    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun has(key: String) = map.containsKey(key)
    fun opt(key: String): Any? = map[key]
    fun get(key: String): Any = map[key] ?: throw IllegalArgumentException("No value for $key")
    fun optJSONObject(key: String) = map[key] as? JSONObject
    fun optJSONArray(key: String) = map[key] as? JSONArray
    fun getJSONObject(key: String) = map[key] as? JSONObject ?: throw IllegalArgumentException("$key is not an object")
    fun getJSONArray(key: String) = map[key] as? JSONArray ?: throw IllegalArgumentException("$key is not an array")
    fun optInt(key: String, fallback: Int = 0) = (map[key] as? Number)?.toInt() ?: fallback
    fun optString(key: String, fallback: String = "") = map[key] as? String ?: fallback
    fun length() = map.size

    fun put(key: String, value: Any?): JSONObject {
        map[key] = when (value) {
            is Float -> value.toDouble()
            is Int -> value.toLong()
            else -> value
        }
        return this
    }

    internal fun element(): JsonElement = kotlinx.serialization.json.JsonObject(map.mapValues { toElement(it.value) })
    override fun toString() = element().toString()

    companion object {
        fun quote(s: String): String = JsonPrimitive(s).toString()
    }
}

class JSONArray() {
    private val list = ArrayList<Any?>()
    constructor(values: Collection<Any?>) : this() { values.forEach { put(it) } }
    fun length() = list.size
    fun opt(i: Int): Any? = list.getOrNull(i)
    fun get(i: Int): Any = list.getOrNull(i) ?: throw IllegalArgumentException("No value at $i")
    fun optJSONObject(i: Int) = list.getOrNull(i) as? JSONObject
    fun optJSONArray(i: Int) = list.getOrNull(i) as? JSONArray
    fun put(value: Any?): JSONArray { list += when (value) { is Float -> value.toDouble(); is Int -> value.toLong(); else -> value }; return this }
    internal fun raw(): List<Any?> = list
    internal fun element(): JsonElement = kotlinx.serialization.json.JsonArray(list.map(::toElement))
    override fun toString() = element().toString()
}

/** Parses one complete JSON value; trailing content is rejected by the parser itself. */
class JSONTokener(private val text: String) {
    private var consumed = false
    fun nextValue(): Any? { consumed = true; return fromElement(Json.parseToJsonElement(text)) }
    fun nextClean(): Char = if (consumed) '\u0000' else text.trimStart().firstOrNull() ?: '\u0000'
}

internal fun fromElement(e: JsonElement): Any? = when (e) {
    is JsonNull -> null
    is kotlinx.serialization.json.JsonObject -> JSONObject().also { o -> e.forEach { (k, v) -> o.put(k, fromElement(v)) } }
    is kotlinx.serialization.json.JsonArray -> JSONArray().also { a -> e.forEach { a.put(fromElement(it)) } }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.content == "true" -> true
        e.content == "false" -> false
        else -> e.content.toLongOrNull() ?: e.content.toDoubleOrNull() ?: e.content
    }
}

internal fun toElement(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JSONObject -> v.element()
    is JSONArray -> v.element()
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Collection<*> -> kotlinx.serialization.json.JsonArray(v.map(::toElement))
    else -> JsonPrimitive(v.toString())
}
