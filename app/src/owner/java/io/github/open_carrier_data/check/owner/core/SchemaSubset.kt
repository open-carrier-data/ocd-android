package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

/**
 * The JSON Schema keywords OCD's public validators implement
 * (validate_public_carrier_data.py SCHEMA_KEYWORDS), on kotlinx JSON. A
 * schema with any other keyword is refused, as there.
 */
object SchemaSubset {
    private val ANNOTATIONS = setOf("\$schema", "\$id", "title", "description")
    private val KEYWORDS = ANNOTATIONS + setOf(
        "\$defs", "\$ref", "type", "const", "enum", "anyOf", "properties", "required", "additionalProperties", "propertyNames",
        "items", "minItems", "maxItems", "uniqueItems", "minimum", "maximum", "minLength", "maxLength", "pattern", "format",
    )

    fun problems(value: JsonElement, root: JsonObject): List<String> {
        walk(root, "#")
        return mutableListOf<String>().also { check(value, root, root, "$", it) }
    }

    private fun walk(node: JsonObject, where: String) {
        val unknown = node.keys - KEYWORDS
        require(unknown.isEmpty()) { "$where uses unsupported keywords $unknown" }
        for (keyword in listOf("\$defs", "properties")) node[keyword]?.jsonObject?.forEach { (k, v) -> walk(v.jsonObject, "$where/$keyword/$k") }
        for (keyword in listOf("items", "propertyNames")) node[keyword]?.let { walk(it.jsonObject, "$where/$keyword") }
        (node["additionalProperties"] as? JsonObject)?.let { walk(it, "$where/additionalProperties") }
        node["anyOf"]?.jsonArray?.forEachIndexed { i, v -> walk(v.jsonObject, "$where/anyOf/$i") }
        node["format"]?.let { require(it.jsonPrimitive.content == "date") { "$where uses an unsupported format" } }
    }

    private fun isType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.booleanOrNull == null && value.doubleOrNull != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "null" -> value is JsonNull
        else -> error("type $type")
    }

    private fun check(value: JsonElement, schema: JsonObject, root: JsonObject, where: String, out: MutableList<String>) {
        schema["\$ref"]?.let { ref ->
            val name = ref.jsonPrimitive.content.removePrefix("#/\$defs/")
            check(value, root.getValue("\$defs").jsonObject.getValue(name).jsonObject, root, where, out)
        }
        schema["type"]?.let { type ->
            val names = if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
            if (names.none { isType(value, it) }) {
                out += "$where: must be of type ${names.joinToString("/")}"
                return
            }
        }
        schema["const"]?.let { if (it != value) { out += "$where: must be $it"; return } }
        schema["enum"]?.let { values -> if (value !in values.jsonArray) { out += "$where: must be one of ${values.jsonArray}"; return } }
        schema["anyOf"]?.let { options ->
            if (options.jsonArray.none { option -> mutableListOf<String>().also { check(value, option.jsonObject, root, where, it) }.isEmpty() }) {
                out += "$where: matches none of anyOf"
                return
            }
        }
        if (value is JsonPrimitive && value.isString) {
            val text = value.content
            schema["minLength"]?.let { if (text.length < it.jsonPrimitive.longOrNull!!) out += "$where: is shorter than $it" }
            schema["maxLength"]?.let { if (text.length > it.jsonPrimitive.longOrNull!!) out += "$where: is longer than $it" }
            schema["pattern"]?.let { if (!Regex(it.jsonPrimitive.content).containsMatchIn(text)) out += "$where: does not have the allowed form" }
            if (schema["format"]?.jsonPrimitive?.content == "date" && runCatching { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text)); LocalDate.parse(text) }.isFailure) {
                out += "$where: is not a date"
            }
        }
        if (value is JsonPrimitive && !value.isString && value.booleanOrNull == null && value.doubleOrNull != null) {
            val number = value.doubleOrNull!!
            schema["minimum"]?.let { if (number < it.jsonPrimitive.doubleOrNull!!) out += "$where: is below $it" }
            schema["maximum"]?.let { if (number > it.jsonPrimitive.doubleOrNull!!) out += "$where: is above $it" }
        }
        if (value is JsonArray) {
            schema["minItems"]?.let { if (value.size < it.jsonPrimitive.longOrNull!!) out += "$where: has fewer than $it items" }
            schema["maxItems"]?.let { if (value.size > it.jsonPrimitive.longOrNull!!) out += "$where: has more than $it items" }
            if (schema["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true && value.toSet().size != value.size) out += "$where: has repeated items"
            schema["items"]?.let { items -> value.forEachIndexed { i, item -> check(item, items.jsonObject, root, "$where[$i]", out) } }
        }
        if (value is JsonObject) {
            schema["required"]?.jsonArray?.forEach { key -> if (key.jsonPrimitive.content !in value) out += "$where: lacks ${key.jsonPrimitive.content}" }
            val properties = schema["properties"]?.jsonObject.orEmpty()
            for ((key, item) in value) {
                schema["propertyNames"]?.let { check(JsonPrimitive(key), it.jsonObject, root, "$where key $key", out) }
                val child = properties[key]
                val extra = schema["additionalProperties"]
                when {
                    child != null -> check(item, child.jsonObject, root, "$where.$key", out)
                    extra is JsonPrimitive && extra.booleanOrNull == false -> out += "$where: has the unknown key $key"
                    extra is JsonObject -> check(item, extra, root, "$where.$key", out)
                }
            }
        }
    }
}
