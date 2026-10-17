package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.convention.ConvFile
import com.dochiri.ilsim.runner.risk.Detectors.braceBlock
import com.dochiri.ilsim.runner.risk.Detectors.remoteType

/** 싱글톤·static·공유 객체에서 생기는 JVM 수준 동시성 문제를 본다. */
internal object ConcurrencyDetectors {
    private val safeShared = Regex("""\b(Atomic\w+|Concurrent\w+|ThreadLocal|CopyOnWrite\w+|\w*Lock|Semaphore|LongAdder|Mutex|Collections\.synchronized\w+|synchronized\w+)\b""")
    private val injected = setOf("Autowired", "Value", "Inject", "Resource", "PersistenceContext", "MockBean", "Mock")
    private val mutableCollection = Regex("""\b(mutable(List|Map|Set)Of|ArrayList|HashMap|HashSet|LinkedList|TreeMap|ArrayDeque|LinkedHashMap|LinkedHashSet)\b""")
    private val collectionType = Regex("""\b(Map|Set|List|Collection|Deque|Queue|mutable\w+Of|HashMap|HashSet|ArrayList|LinkedList|TreeMap)\b""")
    private val unsafeType = Regex("""\b(SimpleDateFormat|DateFormat|DecimalFormat|NumberFormat|Calendar|MessageDigest|Cipher|Mac|XPath|Matcher|DocumentBuilder|Marshaller|Unmarshaller|StringBuilder)\b""")
    private val syncBlock = Regex("""\bsynchronized\s*\(([^)]*)\)\s*\{""")
    private val dclRegex = Regex(
        """if\s*\(\s*(?:this\s*\.\s*)?(\w+)\s*==\s*null\s*\)\s*\{?\s*synchronized\s*\([^)]*\)\s*\{\s*if\s*\(\s*(?:this\s*\.\s*)?\1\s*==\s*null\s*\)""",
    )

    fun run(s: Scope) {
        val c = s.c
        val shared = c.annotations.any { it in Detectors.beanAnnotations } || c.kind == "object"
        val allFields = c.fields
        val ownFields = c.members.filter { it.kind == "field" }
        val mutableFields = allFields.filter { it.mutable }
        val remoteFields = allFields.filter { remoteType.containsMatchIn(it.type) }
        val pairs = mutableListOf<Triple<Pair<String, String>, Member, Int>>()

        // 공유 필드 선언
        for (fld in ownFields + c.ctorFields) {
            if (fld.annotations.any { it in injected } || safeShared.containsMatchIn(fld.type)) continue
            val isStatic = "static" in fld.modifiers && "final" !in fld.modifiers
            val unsafe = unsafeType.find(fld.type) ?: unsafeType.find(fld.body)
            if ((shared || isStatic || "static" in fld.modifiers) && unsafe != null && "Entity" !in c.annotations) {
                s.hit("CON-010", fld.line, "`${c.name}.${fld.name}` 은 스레드 안전하지 않은 `${unsafe.value}` 를 여러 요청이 공유합니다")
                continue
            }
            val collection = mutableCollection.containsMatchIn(fld.type) || mutableCollection.containsMatchIn(fld.body)
            if ((shared && "Entity" !in c.annotations && (fld.mutable || collection)) || isStatic) {
                val why = when {
                    isStatic -> "static 변경 가능 필드"
                    fld.mutable -> "변경 가능한 필드"
                    else -> "스레드 안전하지 않은 컬렉션"
                }
                s.hit("CON-001", fld.line, "`${c.name}.${fld.name}` 은 $why 라 요청 간에 공유됩니다")
            }
        }

        for (m in c.funcs) {
            val syncMethod = "synchronized" in m.modifiers
            val guarded = syncMethod || Regex("""synchronized|\block\b|withLock|computeIfAbsent|putIfAbsent|compute\(""").containsMatchIn(m.body)

            lockWithoutRelease(m)?.let { s.hit("CON-003", m.lineOf(it), "`${c.name}.${m.name}` 에서 lock() 후 finally 에 unlock() 이 없습니다") }
            dclRegex.find(m.body)?.let { d ->
                val declared = allFields.firstOrNull { it.name == d.groupValues[1] }
                if (declared != null && "Volatile" !in declared.annotations && "volatile" !in declared.modifiers) {
                    s.hit("CON-002", m.lineOf(d.range.first), "`${declared.name}` 은 Double-Checked Locking 인데 volatile 이 아닙니다")
                }
            }

            if (shared || ownFields.any { "static" in it.modifiers }) {
                if (!guarded) {
                    for (f in allFields.filter { collectionType.containsMatchIn(it.type) || collectionType.containsMatchIn(it.body) }) {
                        val n = Regex.escape(f.name)
                        val patterns = listOf(
                            Regex("""\b$n\s*\.\s*(containsKey|contains)\s*\([^)]*\)[\s\S]{0,200}?\b$n\s*(\.\s*(put|add)\s*\(|\[)"""),
                            Regex("""\b$n\s*(\.\s*get\s*\([^)]*\)|\[[^\]]*\])\s*==\s*null[\s\S]{0,200}?\b$n\s*(\.\s*put\s*\(|\[)"""),
                        )
                        patterns.firstNotNullOfOrNull { it.find(m.body) }?.let {
                            s.hit("CON-004", m.lineOf(it.range.first), "`${c.name}.${m.name}` 이 `${f.name}` 을 확인한 뒤 넣습니다. 그 사이에 다른 스레드가 끼어들 수 있으니 putIfAbsent/computeIfAbsent 를 쓰세요")
                        }
                    }
                    for (f in mutableFields.filter { "Entity" !in c.annotations }) {
                        val n = Regex.escape(f.name)
                        Regex("""\b$n\s*(\+\+|--|\+=|-=|\*=)|(\+\+|--)\s*$n\b|\b$n\s*=\s*$n\s*[-+*]""").find(m.body)?.let {
                            s.hit("CON-005", m.lineOf(it.range.first), "`${c.name}.${m.name}` 이 공유 필드 `${f.name}` 을 읽고 갱신합니다. 원자적이지 않아 갱신이 유실됩니다 (volatile 도 해결하지 못합니다)")
                        }
                    }
                    for (f in mutableFields.filter { "Entity" !in c.annotations }) {
                        val n = Regex.escape(f.name)
                        Regex("""if\s*\(\s*(?:this\s*\.\s*)?$n\s*==\s*null\s*\)\s*\{?\s*(?:this\s*\.\s*)?$n\s*=""").find(m.body)?.let {
                            s.hit("CON-011", m.lineOf(it.range.first), "`${c.name}.${m.name}` 의 `${f.name}` 지연 초기화에 동기화가 없어 여러 번 초기화되거나 덜 만들어진 객체가 보일 수 있습니다")
                        }
                    }
                }
                for (f in allFields.filter { safeShared.containsMatchIn(it.type) && it.type.contains("Atomic") }) {
                    val n = Regex.escape(f.name)
                    Regex("""\b$n\s*\.\s*set\s*\(\s*$n\s*\.\s*get\s*\(\s*\)""").find(m.body)?.let {
                        s.hit("CON-005", m.lineOf(it.range.first), "`${f.name}.set(${f.name}.get() ...)` 는 원자적이지 않습니다. updateAndGet/incrementAndGet 을 쓰세요")
                    }
                }
            }

            // 락 순서와 동기화 대상
            for (outer in syncBlock.findAll(m.body)) {
                val outerArg = outer.groupValues[1].trim()
                val block = braceBlock(m.body, outer.range.last)
                for (inner in syncBlock.findAll(block)) {
                    val innerArg = inner.groupValues[1].trim()
                    if (innerArg != outerArg) pairs += Triple(outerArg to innerArg, m, m.lineOf(outer.range.first))
                }
                val remote = remoteFields.firstNotNullOfOrNull { r -> Regex("""\b${Regex.escape(r.name)}\s*\.\s*\w+""").find(block) }
                val sleep = Regex("""\bThread\s*\.\s*sleep\s*\(""").find(block)
                (remote ?: sleep)?.let {
                    s.hit("CON-008", m.lineOf(outer.range.first), "`${c.name}.${m.name}` 이 synchronized 블록 안에서 `${it.value.replace(Regex("\\s+"), "")}` 를 호출해 락을 쥔 채 대기합니다")
                }
                checkSyncTarget(s, m, outerArg, outer.range.first, mutableFields)
            }
            val locks = Regex("""\b(\w+)\s*\.\s*lock\s*\(\s*\)""").findAll(m.body).map { it.groupValues[1] to it.range.first }.toList()
            for (i in locks.indices) for (j in i + 1 until locks.size) {
                if (locks[i].first != locks[j].first) pairs += Triple(locks[i].first to locks[j].first, m, m.lineOf(locks[i].second))
            }
            if (Regex("""\.\s*lock\s*\(\s*\)""").containsMatchIn(m.body) || syncBlock.containsMatchIn(m.body) || syncMethod) {
                val sleep = Regex("""\bThread\s*\.\s*sleep\s*\(""").find(m.body)
                val remote = remoteFields.firstNotNullOfOrNull { r -> Regex("""\b${Regex.escape(r.name)}\s*\.\s*\w+""").find(m.body) }
                if ((syncMethod || locks.isNotEmpty()) && (sleep != null || remote != null)) {
                    val hit = (remote ?: sleep)!!
                    s.hit("CON-008", m.lineOf(hit.range.first), "`${c.name}.${m.name}` 이 락을 쥔 채 `${hit.value.replace(Regex("\\s+"), "")}` 를 호출합니다")
                }
            }

            if (Regex("""\bwait\s*\(""").containsMatchIn(m.body) && !Regex("""\bwhile\b""").containsMatchIn(m.body)) {
                s.hit("CON-009", m.line, "`${c.name}.${m.name}` 이 while 루프 없이 wait() 를 호출해 허위 깨움(spurious wakeup)에 취약합니다")
            }
        }

        for ((i, p) in pairs.withIndex()) {
            val reversed = pairs.withIndex().firstOrNull { (j, q) -> j != i && q.first == (p.first.second to p.first.first) } ?: continue
            if (i < reversed.index) continue
            s.hit("CON-006", p.third, "`${c.name}.${p.second.name}` 의 락 순서(${p.first.first} → ${p.first.second})가 `${reversed.value.second.name}` 의 순서와 반대여서 데드락 위험이 있습니다")
        }
    }

    private fun checkSyncTarget(s: Scope, m: Member, arg: String, offset: Int, mutableFields: List<Member>) {
        val c = s.c
        when {
            arg.startsWith("\"") -> s.hit("CON-007", m.lineOf(offset), "`${c.name}.${m.name}` 이 문자열 리터럴에 synchronized 합니다. 같은 JVM의 다른 코드와 락이 공유됩니다")
            Regex("""(Integer|Long|Boolean|Short|Byte)\s*\.\s*valueOf""").containsMatchIn(arg) ->
                s.hit("CON-007", m.lineOf(offset), "`${c.name}.${m.name}` 이 박싱된 값에 synchronized 합니다. 캐시된 객체라 의도하지 않은 락 공유가 생깁니다")
            mutableFields.any { it.name == arg } ->
                s.hit("CON-007", m.lineOf(offset), "`${c.name}.${m.name}` 이 재할당 가능한 필드 `$arg` 에 synchronized 합니다. 락 객체가 바뀌면 상호 배제가 깨집니다")
        }
    }

    private fun lockWithoutRelease(m: Member): Int? {
        val lock = Regex("""\.\s*(lock|lockInterruptibly)\s*\(\s*\)""").find(m.body) ?: return null
        val finallies = Regex("""\bfinally\s*\{""").findAll(m.body).map { braceBlock(m.body, it.range.last) }
        return if (finallies.any { Regex("""\bunlock\s*\(""").containsMatchIn(it) }) null else lock.range.first
    }

    /** Kotlin `companion object` 안의 변경 가능한 상태(= Java static). */
    fun companions(f: ConvFile, out: MutableList<Hit>) {
        if (f.lang != "kotlin") return
        val text = stripLiterals(f.lines.joinToString("\n"))
        for (m in Regex("""\bcompanion\s+object\b[^{]*\{""").findAll(text)) {
            val block = braceBlock(text, m.range.last)
            val base = m.range.last + 1
            for (v in Regex("""\b(var|val)\s+(\w+)\s*(?::\s*([\w.<>?, ]+))?\s*(?:=\s*([^\n]*))?""").findAll(block)) {
                if (v.range.first > 0 && block.take(v.range.first).count { it == '{' } > block.take(v.range.first).count { it == '}' }) continue
                val type = v.groupValues[3] + " " + v.groupValues[4]
                if (safeShared.containsMatchIn(type)) continue
                val mutable = v.groupValues[1] == "var"
                val unsafe = mutableCollection.containsMatchIn(type) || unsafeType.containsMatchIn(type)
                if (!mutable && !unsafe) continue
                val line = text.take(base + v.range.first).count { it == '\n' } + 1
                out += Hit("CON-001", f.path, line, "companion object 의 `${v.groupValues[2]}` 은 ${if (mutable) "변경 가능한 필드" else "스레드 안전하지 않은 객체"}라 (= static) 모든 요청이 공유합니다")
            }
        }
    }
}
