package com.dochiri.ilsim.runner.convention

internal class Sample(val file: String, val line: Int, val ok: Boolean, val message: String)

internal class Candidate(val key: String, val params: Map<String, List<String>>, val description: String, val maxSeverity: String = "BLOCK")

internal abstract class Probe(val kind: String, val minSamples: Int = 5) {
    abstract fun candidates(f: Facts): List<Candidate>
    abstract fun samples(f: Facts, params: Map<String, List<String>>): List<Sample>
    fun one(params: Map<String, List<String>>, key: String) = params.getValue(key).first()
}

internal object Probes {
    private val ignoredAnnotations = setOf(
        "Override", "Suppress", "SuppressWarnings", "Deprecated", "JvmStatic", "JvmField", "JvmOverloads", "JvmName",
        "Nullable", "NonNull", "Volatile", "Serial", "FunctionalInterface", "SafeVarargs",
    )
    private val stereotypes = setOf("Service", "Component", "RestController", "Controller", "Repository", "Configuration")
    private val roles = stereotypes + setOf("RestControllerAdvice", "ControllerAdvice")
    internal val roleAnnotations: Set<String> get() = roles
    private val camelWord = Regex("[A-Z][a-z0-9]*")

    val all: List<Probe> = listOf(
        AnnotationSuffix, TestClassSuffix, TestMethodStyle, NoFieldInjection, NoWildcardImport,
        NoDebugOutput, NoForceUnwrap, IndentStyle, MaxLineLength, FileMatchesType, AnnotationConfinement,
        StereotypePackage, PackageSuffix, AnnotationCompanion, NoComments, DomainEncapsulation, FirstClassCollection, IdentifierValueObject, MaxFunctionLines, MaxNesting, MaxFileLines,
        ClassFinal, ParameterFinal, FunctionalInterfaceDeclared, NoValueAnnotation, ValueObjectConstructor, DomainFinalFields,
        PackageMethod, ReadOnlyTransactions, IdentifierTyped,
    )

    fun byKind(kind: String): Probe? = all.firstOrNull { it.kind == kind } ?: DocProbes.byKind(kind)

    internal fun lastWord(name: String): String =
        Regex("""[A-Z][a-z0-9]*$""").find(name)?.value?.takeIf { it.length < name.length } ?: ""

    internal fun commonSuffix(names: List<String>): Pair<String, Double>? {
        val multi = names.map { n -> camelWord.findAll(n).map { it.value }.toList() }.filter { it.size >= 2 }
        if (multi.isEmpty()) return null
        if (multi.size == 1) return multi.first().last() to 1.0
        val counts = multi.flatMap { w -> (1 until w.size).map { w.takeLast(it).joinToString("") } }.groupingBy { it }.eachCount()
        return counts.filterValues { it >= 2 && it.toDouble() / multi.size >= ConventionMiner.MIN_RATIO }
            .maxWithOrNull(compareBy({ it.key.length }, { it.value }))?.let { it.key to it.value.toDouble() / multi.size }
    }

    private fun <T> dominant(items: List<T>): Pair<T, Double>? =
        items.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { it.key to it.value.toDouble() / items.size }

    object AnnotationSuffix : Probe("annotation-suffix", minSamples = 2) {
        override fun candidates(f: Facts): List<Candidate> =
            f.main.flatMap { c -> c.types.flatMap { t -> t.annotations.filter { it !in ignoredAnnotations }.map { it to t.name } } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size >= minSamples }
                .mapNotNull { (ann, names) ->
                    val d = dominant(names.map { lastWord(it) }.filter { it.isNotEmpty() }) ?: return@mapNotNull null
                    if (ann !in roles && camelWord.findAll(ann).none { it.value == d.first }) return@mapNotNull null
                    Candidate(ann, mapOf("annotation" to listOf(ann), "suffix" to listOf(d.first)), "@$ann 이 붙은 클래스 이름은 `${d.first}` 로 끝난다")
                }

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
            val ann = one(params, "annotation"); val suffix = one(params, "suffix")
            return f.main.flatMap { c -> c.types.filter { ann in it.annotations }.map {
                Sample(c.path, it.line, it.name.endsWith(suffix), "@$ann 클래스 `${it.name}` 은 `$suffix` 로 끝나야 합니다")
            } }
        }
    }

    object TestClassSuffix : Probe("test-class-suffix") {
        private fun primary(c: ConvFile) = c.path.substringAfterLast('/').substringBeforeLast('.')

        override fun candidates(f: Facts): List<Candidate> {
            val d = dominant(f.tests.map { lastWord(primary(it)) }.filter { it.isNotEmpty() }) ?: return emptyList()
            return listOf(Candidate("test", mapOf("suffix" to listOf(d.first)), "테스트 파일 이름은 `${d.first}` 로 끝난다"))
        }

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
            val suffix = one(params, "suffix")
            return f.tests.map { Sample(it.path, 1, primary(it).endsWith(suffix), "테스트 파일 이름은 `$suffix` 로 끝나야 합니다") }
        }
    }

    object TestMethodStyle : Probe("test-method-style") {
        private fun style(name: String) = when {
            " " in name || name.any { !it.isLetterOrDigit() && it != '_' } -> "backtick"
            '_' in name -> "snake"
            else -> "camel"
        }

        private fun methods(f: Facts) = f.tests.flatMap { c ->
            c.annotated.filter { it.kind == "func" && it.annotations.any { a -> a == "Test" || a == "ParameterizedTest" } }.map { c to it }
        }

        override fun candidates(f: Facts): List<Candidate> {
            val d = dominant(methods(f).map { style(it.second.name) }) ?: return emptyList()
            val label = mapOf("backtick" to "백틱 문장형", "snake" to "snake_case", "camel" to "camelCase").getValue(d.first)
            return listOf(Candidate("test", mapOf("style" to listOf(d.first)), "테스트 메서드 이름은 $label 이다"))
        }

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
            val want = one(params, "style")
            return methods(f).map { (c, m) -> Sample(c.path, m.line, style(m.name) == want, "테스트 메서드 `${m.name}` 은 $want 스타일이어야 합니다") }
        }
    }

    object NoFieldInjection : Probe("no-field-injection", minSamples = 3) {
        override fun candidates(f: Facts) = listOf(Candidate("main", emptyMap(), "필드 주입(@Autowired)을 쓰지 않고 생성자로 주입한다"))

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
            f.main.flatMap { c ->
                val beans = c.types.filter { t -> t.annotations.any { it in stereotypes } }.map { Sample(c.path, it.line, true, "") }
                val bad = c.annotated.filter { it.kind == "field" && "Autowired" in it.annotations }
                    .map { Sample(c.path, it.line, false, "필드 주입 `${it.name}`: 생성자 주입을 사용해야 합니다") }
                beans + bad
            }
    }

    object NoWildcardImport : Probe("no-wildcard-import", minSamples = 5) {
        private val wildcard = Regex("""^\s*import\s+(?:static\s+)?[\w.]+\.\*""")
        override fun candidates(f: Facts) = listOf(Candidate("main", emptyMap(), "와일드카드 import(`.*`)를 쓰지 않는다"))

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
            f.main.flatMap { c ->
                c.lines.mapIndexedNotNull { i, l ->
                    if (l.trimStart().startsWith("import ")) Sample(c.path, i + 1, !wildcard.containsMatchIn(l), "와일드카드 import 를 쓰지 않아야 합니다") else null
                }
            }
    }

    object NoDebugOutput : Probe("no-debug-output") {
        private val debug = Regex("""\bSystem\.(out|err)\.print|\.printStackTrace\s*\(|(?<![.\w])println\s*\(""")
        override fun candidates(f: Facts) = listOf(Candidate("main", emptyMap(), "System.out/err 출력, printStackTrace, println 을 쓰지 않고 로거를 쓴다"))

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
            f.main.map { c ->
                val hit = c.lines.indexOfFirst { l -> !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") && debug.containsMatchIn(l) }
                if (hit >= 0) Sample(c.path, hit + 1, false, "디버그 출력은 로거로 대체해야 합니다") else Sample(c.path, 1, true, "")
            }
    }

    object NoForceUnwrap : Probe("no-force-unwrap") {
        private val bang = Regex("""\w!!""")
        override fun candidates(f: Facts) =
            if (f.main.count { it.lang == "kotlin" } >= minSamples) listOf(Candidate("main", emptyMap(), "Kotlin 에서 `!!` 를 쓰지 않는다")) else emptyList()

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
            f.main.filter { it.lang == "kotlin" }.map { c ->
                val hit = c.lines.indexOfFirst { l -> !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") && bang.containsMatchIn(l) }
                if (hit >= 0) Sample(c.path, hit + 1, false, "`!!` 대신 안전한 호출이나 명시적 검사를 써야 합니다") else Sample(c.path, 1, true, "")
            }
    }

    object IndentStyle : Probe("indent-style") {
        internal fun styleOf(lines: List<String>): String? {
            val code = lines.filter { it.isNotBlank() && !it.trimStart().startsWith("*") }
            if (code.any { it.startsWith("\t") }) return "tabs"
            val indents = code.map { it.length - it.trimStart().length }
            val unit = code.indices.drop(1)
                .filter { code[it - 1].trimEnd().endsWith("{") && indents[it] > indents[it - 1] }
                .minOfOrNull { indents[it] - indents[it - 1] } ?: return null
            return "spaces:$unit"
        }

        override fun candidates(f: Facts): List<Candidate> {
            val d = dominant(f.main.mapNotNull { styleOf(it.lines) }) ?: return emptyList()
            val label = if (d.first == "tabs") "탭" else "공백 ${d.first.substringAfter(':')}칸"
            return listOf(Candidate("main", mapOf("style" to listOf(d.first)), "들여쓰기는 ${label}이다"))
        }

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
            val want = one(params, "style")
            return f.main.mapNotNull { c -> styleOf(c.lines)?.let { Sample(c.path, 1, it == want, "들여쓰기는 $want 여야 합니다 (현재 $it)") } }
        }
    }

    object MaxLineLength : Probe("max-line-length") {
        private fun skip(l: String) = l.trimStart().let { it.startsWith("import ") || it.startsWith("package ") }

        override fun candidates(f: Facts): List<Candidate> {
            if (f.main.size < minSamples) return emptyList()
            val limit = listOf(100, 120, 140, 160).firstOrNull { max ->
                f.main.count { c -> c.lines.none { !skip(it) && it.length > max } } >= f.main.size * 0.9
            } ?: return emptyList()
            return listOf(Candidate("main", mapOf("max" to listOf("$limit")), "한 줄은 ${limit}자를 넘지 않는다"))
        }

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
            val max = one(params, "max").toInt()
            return f.main.map { c ->
                val hit = c.lines.indexOfFirst { !skip(it) && it.length > max }
                if (hit >= 0) Sample(c.path, hit + 1, false, "줄 길이가 ${c.lines[hit].length}자로 ${max}자를 넘습니다") else Sample(c.path, 1, true, "")
            }
        }
    }

    object FileMatchesType : Probe("file-matches-type", minSamples = 10) {
        override fun candidates(f: Facts) = listOf(Candidate("main", emptyMap(), "최상위 타입이 하나인 파일은 파일 이름이 타입 이름과 같다"))

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
            f.main.filter { it.lang == "kotlin" }.mapNotNull { c ->
                val top = c.types.filter { it.topLevel }
                if (top.size != 1) return@mapNotNull null
                val file = c.path.substringAfterLast('/').substringBeforeLast('.')
                Sample(c.path, top[0].line, file == top[0].name, "파일 이름 `$file` 은 타입 이름 `${top[0].name}` 과 같아야 합니다")
            }
    }

    object StereotypePackage : Probe("stereotype-package", minSamples = 1) {
        private val placed = stereotypes + "Entity"

        private fun segment(pkg: String) = pkg.substringAfterLast('.')

        private fun typesWith(f: Facts, ann: String) =
            f.main.flatMap { c -> c.types.filter { ann in it.annotations && it.topLevel }.map { c to it } }

        override fun candidates(f: Facts): List<Candidate> =
            placed.mapNotNull { ann ->
                val d = dominant(typesWith(f, ann).map { segment(it.first.pkg) }) ?: return@mapNotNull null
                Candidate(ann, mapOf("annotation" to listOf(ann), "segment" to listOf(d.first)), "@$ann 클래스는 `${d.first}` 패키지에 둔다")
            }

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
            val ann = one(params, "annotation"); val seg = one(params, "segment")
            return typesWith(f, ann).map { (c, t) ->
                Sample(c.path, t.line, segment(c.pkg) == seg, "@$ann 클래스 `${t.name}` 은 `$seg` 패키지에 있어야 합니다 (현재 `${c.pkg}`)")
            }
        }
    }

    object AnnotationCompanion : Probe("annotation-companion", minSamples = 2) {
        private val targets = stereotypes + "Entity"

        private fun typesWith(f: Facts, ann: String) = f.main.flatMap { c -> c.types.filter { ann in it.annotations && it.topLevel }.map { c to it } }

        override fun candidates(f: Facts): List<Candidate> =
            targets.sorted().flatMap { target ->
                val types = typesWith(f, target)
                if (types.size < minSamples) return@flatMap emptyList()
                types.flatMap { it.second.annotations }.distinct()
                    .filter { it != target && it !in ignoredAnnotations && it !in stereotypes }
                    .filter { c -> types.count { c in it.second.annotations } >= types.size * ConventionMiner.MIN_RATIO }
                    .map { c -> Candidate("$target+$c", mapOf("target" to listOf(target), "companion" to listOf(c)), "@$target 클래스는 @$c 를 함께 붙인다") }
            }

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
            val target = one(params, "target"); val companion = one(params, "companion")
            return typesWith(f, target).map { (c, t) -> Sample(c.path, t.line, companion in t.annotations, "@$target 클래스 `${t.name}` 에 @$companion 이 필요합니다") }
        }
    }

    object PackageSuffix : Probe("package-suffix", minSamples = 1) {
        private fun checked(c: ConvFile) = c.types.filter { it.topLevel && "SpringBootApplication" !in it.annotations }

        private fun byPackage(f: Facts) = f.main.flatMap { c -> checked(c).map { c.pkg to it } }
            .groupBy({ it.first.substringAfterLast('.') }, { it.second })

        override fun candidates(f: Facts): List<Candidate> =
            byPackage(f).toSortedMap().mapNotNull { (seg, types) ->
                val d = commonSuffix(types.map { it.name }) ?: return@mapNotNull null
                Candidate(seg, mapOf("segment" to listOf(seg), "suffix" to listOf(d.first)), "`$seg` 패키지의 타입 이름은 `${d.first}` 로 끝난다")
            }

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
            val seg = one(params, "segment"); val suffix = one(params, "suffix")
            return f.main.flatMap { c ->
                if (c.pkg.substringAfterLast('.') != seg) emptyList()
                else checked(c).map { Sample(c.path, it.line, it.name.endsWith(suffix), "`$seg` 패키지의 타입 `${it.name}` 은 `$suffix` 로 끝나야 합니다") }
            }
        }
    }

    object AnnotationConfinement : Probe("annotation-confinement", minSamples = 3) {
        private val layers = setOf("adapter", "application", "domain")

        private fun inPackage(pkg: String, allowed: List<String>) =
            allowed.any { pkg == it || pkg.startsWith("$it.") || pkg.endsWith(".$it") || pkg.contains(".$it.") }

        private fun layerScope(pkg: String): String {
            val parts = pkg.split('.')
            val i = parts.indexOfFirst { it in layers }
            return if (i < 0) pkg else parts.drop(i).joinToString(".")
        }

        override fun candidates(f: Facts): List<Candidate> =
            f.main.flatMap { c -> c.annotated.flatMap { d -> d.annotations.filter { it !in ignoredAnnotations && it !in stereotypes && it != "Entity" }.map { it to c.pkg } } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size >= minSamples }
                .mapNotNull { (ann, pkgs) ->
                    val distinct = pkgs.map { layerScope(it) }.toSortedSet().toList()
                    if (distinct.size > 3) return@mapNotNull null
                    Candidate(ann, mapOf("annotation" to listOf(ann), "packages" to distinct),
                        "@$ann 은 ${distinct.joinToString { "`$it`" }} 안에서만 쓴다", maxSeverity = "REVIEW")
                }

        override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
            val ann = one(params, "annotation"); val allowed = params.getValue("packages")
            return f.main.flatMap { c ->
                c.annotated.filter { ann in it.annotations }.map {
                    Sample(c.path, it.line, inPackage(c.pkg, allowed), "@$ann 은 ${allowed.joinToString { a -> "`$a`" }} 밖(`${c.pkg}`)에서 쓰였습니다")
                }
            }
        }
    }
}
