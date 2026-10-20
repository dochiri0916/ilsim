package com.dochiri.ilsim.runner.profile

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

internal class Catalog(
    val libraries: Map<String, Pair<String, String?>>,
    val plugins: Map<String, Pair<String, String?>>,
) {
    companion object {
        val EMPTY = Catalog(emptyMap(), emptyMap())

        fun load(root: Path): Catalog {
            val file = root.resolve("gradle/libs.versions.toml")
            if (!file.exists()) return EMPTY
            val versions = mutableMapOf<String, String>()
            val libs = mutableMapOf<String, Pair<String, String?>>()
            val plugins = mutableMapOf<String, Pair<String, String?>>()
            var section = ""
            for (raw in file.readText().lines()) {
                val line = raw.substringBefore(" #").trim()
                if (line.startsWith("[")) { section = line.trim('[', ']'); continue }
                val key = line.substringBefore('=', "").trim()
                val value = line.substringAfter('=', "").trim()
                if (key.isEmpty() || value.isEmpty()) continue
                when (section) {
                    "versions" -> unquote(value)?.let { versions[key] = it }
                    "libraries" -> parseLibrary(value, versions)?.let { libs[normalize(key)] = it }
                    "plugins" -> parsePlugin(value, versions)?.let { plugins[normalize(key)] = it }
                }
            }
            return Catalog(libs, plugins)
        }

        fun normalize(alias: String) = alias.replace('-', '.').replace('_', '.')

        private fun unquote(v: String) = Regex("""^["']([^"']*)["']$""").find(v)?.groupValues?.get(1)

        private fun version(v: String, versions: Map<String, String>): String? =
            Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(v)?.groupValues?.get(1)?.let(versions::get)
                ?: Regex("""version\s*=\s*["']([^"']+)["']""").find(v)?.groupValues?.get(1)

        private fun parseLibrary(v: String, versions: Map<String, String>): Pair<String, String?>? {
            unquote(v)?.let { s ->
                val p = s.split(':')
                return if (p.size >= 2) "${p[0]}:${p[1]}" to p.getOrNull(2) else null
            }
            val module = Regex("""module\s*=\s*["']([^"']+)["']""").find(v)?.groupValues?.get(1)
            val group = Regex("""group\s*=\s*["']([^"']+)["']""").find(v)?.groupValues?.get(1)
            val name = Regex("""name\s*=\s*["']([^"']+)["']""").find(v)?.groupValues?.get(1)
            val coordinate = module ?: if (group != null && name != null) "$group:$name" else return null
            return coordinate to version(v, versions)
        }

        private fun parsePlugin(v: String, versions: Map<String, String>): Pair<String, String?>? {
            unquote(v)?.let { return it.substringBefore(':') to it.substringAfter(':', "").ifEmpty { null } }
            val id = Regex("""id\s*=\s*["']([^"']+)["']""").find(v)?.groupValues?.get(1) ?: return null
            return id to version(v, versions)
        }
    }

    fun <T> resolve(table: Map<String, T>, reference: String): T? {
        val parts = reference.split('.')
        for (n in parts.size downTo 1) table[parts.take(n).joinToString(".")]?.let { return it }
        return null
    }
}
