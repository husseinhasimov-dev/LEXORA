package org.lexora.study

import android.content.Context
import org.json.JSONObject

internal data class GrammarGuide(
    val id: String,
    val isNew: Boolean,
    val category: String,
    val whyItWorks: String,
    val positiveExamples: List<String>,
    val negativeExamples: List<String>,
    val commonMistakes: String,
    val context: String,
    val exceptions: String,
    val comparison: String,
    val relatedVocabTopics: List<String>,
    val extraExercises: List<AcademyQuestion>,
)

internal data class GrammarCatalog(
    val lessons: List<Lesson>,
    val guides: Map<String, GrammarGuide>,
) {
    val totalTopics: Int get() = lessons.size
    val totalExercises: Int get() = lessons.sumOf { it.tasks.size } + guides.values.sumOf { g ->
        val base = lessons.find { it.id == g.id }
        if (g.isNew) (g.extraExercises.size - (base?.tasks?.size ?: 0)).coerceAtLeast(0)
        else g.extraExercises.size
    }
    val newTopicCount: Int get() = guides.values.count { it.isNew }
    val newExerciseCount: Int get() = guides.values.sumOf { it.extraExercises.size }
    val categories: List<String> get() = guides.values.map { it.category }.distinct().sorted()
}

internal fun loadGrammarCatalog(context: Context): GrammarCatalog {
    val baseLessons = baseTsvLessons(context)
    val raw = context.assets.open("academy-grammar-v61.json").bufferedReader().use { it.readText() }
    val root = JSONObject(raw)
    val arr = root.getJSONArray("topics")
    val guides = linkedMapOf<String, GrammarGuide>()
    val addedLessons = mutableListOf<Lesson>()
    for (i in 0 until arr.length()) {
        val t = arr.getJSONObject(i)
        val id = t.getString("id")
        val isNew = t.optBoolean("isNew", false)
        val extraArr = t.getJSONArray("extraExercises")
        val extra = List(extraArr.length()) { idx ->
            val q = extraArr.getJSONObject(idx)
            AcademyQuestion(
                type = q.getString("type"),
                text = q.getString("prompt"),
                key = q.getString("key"),
                distractors = q.getJSONArray("choices").strings(),
                why = q.getString("why"),
            )
        }
        val guide = GrammarGuide(
            id = id,
            isNew = isNew,
            category = t.getString("category"),
            whyItWorks = t.getString("whyItWorks"),
            positiveExamples = t.getJSONArray("positiveExamples").strings(),
            negativeExamples = t.getJSONArray("negativeExamples").strings(),
            commonMistakes = t.getString("commonMistakes"),
            context = t.getString("context"),
            exceptions = t.getString("exceptions"),
            comparison = t.getString("comparison"),
            relatedVocabTopics = t.getJSONArray("relatedVocabTopics").strings(),
            extraExercises = extra,
        )
        guides[id] = guide
        if (isNew) {
            val tasks = extra.take(4)
            addedLessons += Lesson(
                id = id,
                level = t.getString("level"),
                title = t.getString("title"),
                prerequisite = t.optString("prerequisite", ""),
                explanation = t.getString("explanation"),
                example = guide.positiveExamples.firstOrNull().orEmpty(),
                mistake = guide.commonMistakes,
                tasks = tasks,
            )
        }
    }
    return GrammarCatalog(baseLessons + addedLessons, guides)
}

internal fun baseTsvLessons(context: Context): List<Lesson> =
    context.assets.open("academy-grammar.tsv").bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val f = line.split('|')
            require(f.size == 15) { "Invalid grammar lesson: " + f.first() }
            Lesson(
                f[0], f[1], f[2], f[3], f[4], f[5], f[6],
                (7..13 step 2).map { i ->
                    val prompt = f[i]
                    val choices = Regex("\\(([^() ]+)/([^() ]+)\\)")
                        .find(prompt)?.groupValues?.drop(1).orEmpty()
                        .filterNot { it.equals(f[i + 1], true) }
                    AcademyQuestion(
                        when {
                            prompt.startsWith("Order") -> "Word order"
                            prompt.startsWith("Correct") -> "Sentence correction"
                            choices.isNotEmpty() -> "Multiple choice"
                            else -> "Fill in the blank"
                        },
                        prompt,
                        f[i + 1],
                        choices,
                        f[4],
                    )
                },
            )
        }.toList()
    }

internal fun Lesson.allQuestions(guide: GrammarGuide?): List<AcademyQuestion> {
    if (guide == null) return tasks
    val seen = LinkedHashMap<String, AcademyQuestion>()
    tasks.forEach { seen[it.text] = it }
    guide.extraExercises.forEach { seen.putIfAbsent(it.text, it) }
    return seen.values.toList()
}

internal fun Lesson.expandedActivity(mode: String, guide: GrammarGuide?, variationSeed: Int = 0): AcademyActivity {
    val pool = allQuestions(guide)
    val extra = guide?.extraExercises.orEmpty()
    val questions = when (mode) {
        "Guided" -> listOfNotNull(tasks.getOrNull(0), extra.firstOrNull()).distinctBy { it.text }.take(2)
        "Practice" -> {
            val candidates = (listOfNotNull(tasks.getOrNull(1)) + extra).distinctBy { it.text }
            val rotated = if (candidates.size <= 3) candidates else {
                val shift = ((variationSeed % candidates.size) + candidates.size) % candidates.size
                (candidates.drop(shift) + candidates.take(shift)).take(4)
            }
            rotated.ifEmpty { listOf(tasks[1]) }
        }
        "Extended Drill" -> pool
        "Assessment" -> (listOfNotNull(tasks.getOrNull(2), tasks.getOrNull(1)) + extra.takeLast(2))
            .distinctBy { it.text }.take(4)
        "Delayed review" -> (listOfNotNull(tasks.getOrNull(3)) + extra.take(1))
            .distinctBy { it.text }.take(2)
        else -> error("Unknown exercise: $mode")
    }
    val base = when (mode) {
        "Assessment" -> 30
        "Delayed review" -> 50
        "Extended Drill" -> 22
        "Practice" -> 14
        else -> 6
    }
    return AcademyActivity("Grammar", mode, id, level, questions, base)
}

internal data class GrammarRevisionItem(
    val lesson: Lesson,
    val priority: Int,
    val reason: String,
    val suggestedMode: String,
)

internal fun computePersonalizedGrammarRevisions(
    lessons: List<Lesson>,
    progress: Map<String, TopicProgress>,
    journal: List<JournalRow>,
    readings: List<Reading>,
): List<GrammarRevisionItem> {
    val readingErrorsByGrammar = mutableMapOf<String, Int>()
    val passageGrammar = readings.filter { it.grammar.isNotBlank() }.associate { it.id to it.grammar }
    for (row in journal) {
        if (row.skill == "Reading" && row.attempted != null && row.correct != null && row.correct < row.attempted) {
            val gId = passageGrammar[row.content] ?: continue
            readingErrorsByGrammar[gId] = (readingErrorsByGrammar[gId] ?: 0) + (row.attempted - row.correct)
        }
    }
    val items = mutableListOf<GrammarRevisionItem>()
    for (lesson in lessons) {
        val p = progress[lesson.id] ?: continue
        val readingMisses = readingErrorsByGrammar[lesson.id] ?: 0
        when {
            p.status == "Needs Review" -> items += GrammarRevisionItem(
                lesson, 100 + p.errors,
                "Delayed review is due to lock in long-term mastery.",
                "Delayed review",
            )
            p.status == "Practice Needed" || p.errors >= 2 -> items += GrammarRevisionItem(
                lesson, 85 + p.errors * 3,
                "${p.errors} recorded grammar error(s) — targeted drill recommended.",
                "Practice",
            )
            readingMisses > 0 -> items += GrammarRevisionItem(
                lesson, 75 + readingMisses * 4,
                "Linked to $readingMisses recent Reading Academy comprehension miss(es).",
                "Extended Drill",
            )
            p.status == "Assessment Ready" -> items += GrammarRevisionItem(
                lesson, 65,
                "Practice accuracy is strong — ready for the topic assessment.",
                "Assessment",
            )
            p.status == "In Progress" && !p.lesson -> items += GrammarRevisionItem(
                lesson, 55,
                "Lesson guide started but not yet marked complete.",
                "Guided",
            )
            p.status == "In Progress" -> items += GrammarRevisionItem(
                lesson, 50,
                "Continue practice to reach assessment readiness (80%+).",
                "Practice",
            )
        }
    }
    if (items.size < 4) {
        val mastered = progress.filterValues { it.status == "Mastered" }.keys
        lessons.filter { (progress[it.id]?.status ?: "Not Started") == "Not Started" }
            .filter { it.prerequisite.isBlank() || it.prerequisite in mastered || progress[it.prerequisite]?.lesson == true }
            .take(4 - items.size)
            .forEachIndexed { idx, l ->
                items += GrammarRevisionItem(
                    l, 30 - idx,
                    "Next recommended topic in the ${l.level} progression.",
                    "Guided",
                )
            }
    }
    return items.sortedByDescending { it.priority }.take(8)
}

data class ReferenceTable(
    val id: String,
    val title: String,
    val levelRange: String,
    val summary: String,
    val headers: List<String>,
    val rows: List<List<String>>,
)

object GrammarReferenceTables {
    val all: List<ReferenceTable> = listOf(
        ReferenceTable(
            id = "articles",
            title = "Definite & Indefinite Articles (4 Cases)",
            levelRange = "A1–B1",
            summary = "Memorize masculine accusative (den/einen), dative (dem/der/dem/den +n), and genitive (des +s/der).",
            headers = listOf("Case", "Masc (der)", "Fem (die)", "Neut (das)", "Plural (die)"),
            rows = listOf(
                listOf("Nominativ", "der / ein", "die / eine", "das / ein", "die / keine"),
                listOf("Akkusativ", "den / einen", "die / eine", "das / ein", "die / keine"),
                listOf("Dativ", "dem / einem", "der / einer", "dem / einem", "den +n / keinen"),
                listOf("Genitiv", "des +s / eines", "der / einer", "des +s / eines", "der / keiner"),
            ),
        ),
        ReferenceTable(
            id = "pronouns",
            title = "Personal, Reflexive & Relative Pronouns",
            levelRange = "A1–B1",
            summary = "Only ich/du separate accusative and dative reflexive forms; relative pronouns match der/die/das except denen, dessen, deren.",
            headers = listOf("Nom", "Akk", "Dat", "Reflexive (A/D)"),
            rows = listOf(
                listOf("ich", "mich", "mir", "mich / mir"),
                listOf("du", "dich", "dir", "dich / dir"),
                listOf("er / sie / es", "ihn / sie / es", "ihm / ihr / ihm", "sich"),
                listOf("wir", "uns", "uns", "uns"),
                listOf("ihr", "euch", "euch", "euch"),
                listOf("sie / Sie", "sie / Sie", "ihnen / Ihnen", "sich"),
            ),
        ),
        ReferenceTable(
            id = "adjectives",
            title = "Adjective Endings (Weak / Mixed / Strong)",
            levelRange = "A2–B2",
            summary = "After der/die/das use -e in nom sg + fem/neut acc sg, and -en everywhere else.",
            headers = listOf("Case", "After der (M/F/N/Pl)", "After ein (M/F/N)", "No Article (M/F/N/Pl)"),
            rows = listOf(
                listOf("Nominativ", "-e / -e / -e / -en", "-er / -e / -es", "-er / -e / -es / -e"),
                listOf("Akkusativ", "-en / -e / -e / -en", "-en / -e / -es", "-en / -e / -es / -e"),
                listOf("Dativ", "-en / -en / -en / -en", "-en / -en / -en", "-em / -er / -em / -en"),
                listOf("Genitiv", "-en / -en / -en / -en", "-en / -en / -en", "-en / -er / -en / -er"),
            ),
        ),
        ReferenceTable(
            id = "verbs",
            title = "Core Verb & Modal Conjugation",
            levelRange = "A1–B2",
            summary = "Present regular (-e, -st, -t, -en, -t, -en), auxiliaries, modal present/Präteritum, and Konjunktiv I/II.",
            headers = listOf("Person", "sein / haben", "können (Pres/Past)", "Konj. II / Konj. I"),
            rows = listOf(
                listOf("ich", "bin / habe", "kann / konnte", "wäre / sei"),
                listOf("du", "bist / hast", "kannst / konntest", "wär(e)st / sei(e)st"),
                listOf("er/sie/es", "ist / hat", "kann / konnte", "wäre / sei"),
                listOf("wir", "sind / haben", "können / konnten", "wären / seien"),
                listOf("ihr", "seid / habt", "könnt / konntet", "wär(e)t / seiet"),
                listOf("sie/Sie", "sind / haben", "können / konnten", "wären / seien"),
            ),
        ),
        ReferenceTable(
            id = "prepositions",
            title = "Prepositions by Case",
            levelRange = "A1–B2",
            summary = "Two-way prepositions take Dative for location (Wo?) and Accusative for direction (Wohin?).",
            headers = listOf("Case", "Key Prepositions", "Example"),
            rows = listOf(
                listOf("Akkusativ", "durch, für, gegen, ohne, um, bis", "für den Test"),
                listOf("Dativ", "aus, bei, mit, nach, seit, von, zu", "mit dem Zug"),
                listOf("Wechsel (D/A)", "an, auf, hinter, in, neben, über, unter, vor, zwischen", "im Park / in den Park"),
                listOf("Genitiv", "wegen, trotz, während, aufgrund, statt, innerhalb", "trotz des Regens"),
            ),
        ),
        ReferenceTable(
            id = "connectors",
            title = "Connectors & Clause Word Order",
            levelRange = "A2–B2",
            summary = "Watch verb placement: Position 0 keeps V2, Position 1 adverbs invert subject+verb, subordinators send the verb to the end.",
            headers = listOf("Group", "Connectors", "Verb Position"),
            rows = listOf(
                listOf("Pos 0 (Coord.)", "und, aber, oder, denn, sondern", "Normal V2 (..., denn ich bin müde)"),
                listOf("Pos 1 (Adverb)", "deshalb, daher, trotzdem, dennoch, jedoch", "Inversion (Trotzdem gehen wir)"),
                listOf("Subordinating", "weil, dass, obwohl, wenn, als, ob, damit", "Verb final (..., weil es regnet)"),
                listOf("Paired", "zwar...aber, einerseits...andererseits, je...desto", "je (end), desto + comp + verb"),
            ),
        ),
    )
}
