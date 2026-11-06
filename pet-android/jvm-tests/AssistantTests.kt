import com.corepack.pet.agent.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

val ZONE: ZoneId = ZoneId.of("Africa/Addis_Ababa")
fun at(h: Int, m: Int = 0, day: Int = 12): ZonedDateTime = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, ZONE)
fun tmp(): File = File.createTempFile("store", ".tsv").also { it.delete() }

class FakeScheduler : ReminderScheduler {
    val scheduled = ArrayList<Reminder>(); val cancelled = ArrayList<Long>()
    override fun schedule(r: Reminder) { scheduled.add(r) }
    override fun cancel(id: Long) { cancelled.add(id) }
}

class FakeHttp(private val routes: Map<String, String>, private val fail: String? = null) : Http {
    val urls = ArrayList<String>()
    override fun get(url: String): String {
        urls.add(url)
        if (fail != null && url.contains(fail)) throw IOException("HTTP 503")
        return routes.entries.firstOrNull { url.contains(it.key) }?.value ?: throw IOException("HTTP 404")
    }
}

const val GEO = """{"results":[{"id":344979,"name":"Addis Ababa","latitude":9.02497,"longitude":38.74689,"country_code":"ET","timezone":"Africa/Addis_Ababa","country":"Ethiopia"}],"generationtime_ms":0.5}"""
const val FORECAST = """{"latitude":9.0,"longitude":38.75,"timezone":"Africa/Addis_Ababa","current":{"time":"2026-10-14T13:00","interval":900,"temperature_2m":21.4,"apparent_temperature":20.1,"relative_humidity_2m":55,"weather_code":2,"wind_speed_10m":9.3},"daily":{"time":["2026-10-14","2026-10-15","2026-10-16"],"weather_code":[2,61,3],"temperature_2m_max":[23.1,21.0,22.4],"temperature_2m_min":[11.2,12.5,11.9],"precipitation_probability_max":[40,80,20]}}"""

fun main() {
    // ================= stores
    run {
        val f = tmp(); val s = NoteStore(f)
        val nasty = "line one\n\ttabbed \\ backslash ሰላም, ok"
        s.add("T\tab", nasty, 1000)
        val back = NoteStore(f).recent(5)[0]
        check("notes survive tabs, newlines, backslashes and Amharic through the file", back.text == nasty && back.title == "T\tab", back.text)
        s.add("Shopping", "milk, eggs, injera", 2000); s.add("Idea", "pet should wave at sunrise", 3000)
        check("search ranks by matching words, newest first on ties", s.search("injera milk").first().title == "Shopping" && s.search("wave sunrise").first().title == "Idea")
        check("search with no match is empty; blank returns newest", s.search("zzzz").isEmpty() && s.search("").first().title == "Idea")
        val capped = NoteStore(tmp(), maxNotes = 3); (1..5).forEach { capped.add("n$it", "x", it.toLong()) }
        check("note cap drops the oldest", capped.recent(10).map { it.title } == listOf("n5", "n4", "n3"))
    }
    run {
        val f = tmp(); val r = ReminderStore(f, maxActive = 3)
        val a = r.add(5000, "call mum")!!; val b = r.add(2000, "stretch")!!; r.add(9000, "send report")
        check("active reminders come back soonest first", r.active().map { it.text } == listOf("stretch", "call mum", "send report"))
        check("cap refuses a fourth waiting reminder", r.add(1, "one too many") == null)
        check("persisted and reloaded", ReminderStore(f).active().size == 3 && ReminderStore(f).get(a.id)?.text == "call mum")
        check("cancel by #id", r.cancel("#${b.id}").map { it.text } == listOf("stretch") && r.active().size == 2)
        check("cancel by part of the text", r.cancel("report").size == 1 && r.cancel("mum").size == 1 && r.active().isEmpty())
        check("cancel needs 3+ characters of text", ReminderStore(tmp()).also { it.add(1, "abc") }.cancel("ab").isEmpty())
        check("done reminders do not count toward the cap", r.add(1, "fits again") != null)
        check("markDone twice is false the second time", r.markDone(a.id).not())
    }
    run {
        val f = tmp(); val t = TaskStore(f)
        t.add("Buy milk", "tomorrow"); t.add("Buy bread", ""); t.add("Call the lab", "Friday")
        check("find by unique text", (t.find("lab") as Match.One).item.text == "Call the lab")
        check("find by number and #number", (t.find("2") as Match.One).item.text == "Buy bread" && (t.find("#1") as Match.One).item.text == "Buy milk")
        check("ambiguous text returns the candidates", (t.find("buy") as Match.Many).items.size == 2)
        check("short or unknown queries match nothing", t.find("by") is Match.None && t.find("zzz") is Match.None)
        t.complete(2)
        check("completed tasks leave the open list but stay in the full list", t.list(false).size == 2 && t.list(true).size == 3)
        check("tasks persist", TaskStore(f).list(true).size == 3 && TaskStore(f).list(true).first { it.id == 2L }.done)
    }

    // ================= reminder / task / note tools
    run {
        val st = ReminderStore(tmp()); val sch = FakeScheduler(); val now = at(15)
        val tool = SetReminderTool(st, sch) { now }
        val out = tool.run(mapOf("text" to "stretch", "in_minutes" to 20))
        check("relative reminder is stored, scheduled and confirmed", out == "Reminder #1 set for Mon 12 Oct 15:20: stretch" && sch.scheduled.single().dueMs == now.plusMinutes(20).toInstant().toEpochMilli(), out)
        check("minutes may arrive as a string", tool.run(mapOf("text" to "x", "in_minutes" to "5")).startsWith("Reminder #2"))
        check("absolute time in the pet's zone", tool.run(mapOf("text" to "call", "at" to "2026-10-12T18:30")).endsWith("18:30: call"))
        check("a space instead of T is fine", tool.run(mapOf("text" to "call", "at" to "2026-10-13 07:15")).contains("Tue 13 Oct 07:15"))
        check("an explicit offset is respected", st.get(4)!!.dueMs == java.time.OffsetDateTime.parse("2026-10-13T07:15:00+03:00").toInstant().toEpochMilli())
        check("a time in the past is refused", tool.run(mapOf("text" to "x", "at" to "2026-10-12T09:00")).contains("already passed"))
        check("more than a year ahead is refused", tool.run(mapOf("text" to "x", "at" to "2028-01-01T09:00")).contains("too far"))
        check("nonsense times are refused", tool.run(mapOf("text" to "x", "at" to "next tuesday-ish")).startsWith("error: couldn't read"))
        check("zero and huge minute counts are refused", tool.run(mapOf("text" to "x", "in_minutes" to 0)).startsWith("error") && tool.run(mapOf("text" to "x", "in_minutes" to 9_999_999)).startsWith("error"))
        check("missing text or time is refused", tool.run(mapOf("in_minutes" to 5)).startsWith("error: missing") && tool.run(mapOf("text" to "x")).startsWith("error: give"))
        check("refused reminders are never scheduled", sch.scheduled.size == 4, "${sch.scheduled.size}")
        val full = SetReminderTool(ReminderStore(tmp(), maxActive = 1), FakeScheduler()) { now }
        full.run(mapOf("text" to "a", "in_minutes" to 5))
        check("a full list says so", full.run(mapOf("text" to "b", "in_minutes" to 5)).contains("too many"))
        check("list shows what is waiting", ListRemindersTool(st) { ZONE }.run(emptyMap()).lines().size == 4 && ListRemindersTool(ReminderStore(tmp())).run(emptyMap()) == "No reminders waiting.")
        val cancel = CancelReminderTool(st, sch)
        check("cancel tells the scheduler too", cancel.run(mapOf("which" to "#1")).startsWith("Cancelled: #1") && sch.cancelled == listOf(1L))
        check("cancel with no match", cancel.run(mapOf("which" to "unicorn")) == "No waiting reminder matched.")
    }
    run {
        val ts = TaskStore(tmp())
        check("add_task and list_task", AddTaskTool(ts).run(mapOf("text" to "Buy milk", "due" to "tomorrow")) == "Task #1 added." && ListTasksTool(ts).run(emptyMap()) == "#1 [ ] Buy milk (due: tomorrow)")
        AddTaskTool(ts).run(mapOf("text" to "Buy bread"))
        check("complete_task asks when two match", CompleteTaskTool(ts).run(mapOf("which" to "buy")).startsWith("More than one task matches"))
        check("complete_task by number", CompleteTaskTool(ts).run(mapOf("which" to "2")) == "Done: #2 Buy bread")
        check("include_done accepts a text boolean", ListTasksTool(ts).run(mapOf("include_done" to "true")).contains("[x] Buy bread"))
        check("empty list message", ListTasksTool(TaskStore(tmp())).run(emptyMap()) == "The to-do list is empty.")
        val ns = NoteStore(tmp())
        check("save_note takes its title from the first line", SaveNoteTool(ns) { 5 }.run(mapOf("text" to "Idea: wave at sunrise\nmore")) == "Note #1 saved: Idea: wave at sunrise")
        check("search_notes finds it", SearchNotesTool(ns) { ZONE }.run(mapOf("query" to "sunrise")).contains("wave at sunrise") && SearchNotesTool(ns).run(mapOf("query" to "zzz")) == "No notes matched.")
    }

    // ================= phone argument checks
    run {
        check("alarm time validation", PhoneArgs.hourMinute(7, 30) == (7 to 30) && PhoneArgs.hourMinute("23", "59") == (23 to 59) && PhoneArgs.hourMinute(24, 0) == null && PhoneArgs.hourMinute(7, 60) == null && PhoneArgs.hourMinute(null, 5) == null)
        check("minute defaults to 0", PhoneArgs.hourMinute(6, null) == (6 to 0))
        check("timer seconds validation", PhoneArgs.seconds(90) == 90 && PhoneArgs.seconds(0) == null && PhoneArgs.seconds(100_000) == null && PhoneArgs.seconds("300") == 300)
        check("only web links are opened", PhoneArgs.httpUrl("https://example.com/a?b=1") != null && PhoneArgs.httpUrl("javascript:alert(1)") == null && PhoneArgs.httpUrl("file:///sdcard/x") == null && PhoneArgs.httpUrl("intent://x#Intent;end") == null && PhoneArgs.httpUrl("https://a b.com") == null)
        check("phone numbers are cleaned and checked", PhoneArgs.phoneNumber("+251 91 123 4567") == "+251911234567" && PhoneArgs.phoneNumber("(0911) 22-33") == "09112233" && PhoneArgs.phoneNumber("call me") == null && PhoneArgs.phoneNumber("12") == null)
        val apps = listOf("Telegram" to "org.telegram", "Chrome" to "com.chrome", "Google Maps" to "com.maps", "Google Photos" to "com.photos", "Calculator" to "com.calc")
        check("app: exact", (PhoneArgs.pickApp("chrome", apps) as PhoneArgs.AppPick.One).pkg == "com.chrome")
        check("app: prefix", (PhoneArgs.pickApp("tele", apps) as PhoneArgs.AppPick.One).label == "Telegram")
        check("app: contains", (PhoneArgs.pickApp("maps", apps) as PhoneArgs.AppPick.One).pkg == "com.maps")
        check("app: ambiguous returns choices", (PhoneArgs.pickApp("google", apps) as PhoneArgs.AppPick.Many).labels.toSet() == setOf("Google Maps", "Google Photos"))
        check("app: none", PhoneArgs.pickApp("zzz", apps) is PhoneArgs.AppPick.None && PhoneArgs.pickApp("  ", apps) is PhoneArgs.AppPick.None)
        check("app: the same package listed twice is not ambiguous", PhoneArgs.pickApp("cam", listOf("Camera" to "com.cam", "Camera" to "com.cam")) is PhoneArgs.AppPick.One)
    }

    // ================= weather and Wikipedia
    run {
        val http = FakeHttp(mapOf("geocoding-api" to GEO, "api.open-meteo.com/v1/forecast" to FORECAST))
        val out = WeatherTool(http).run(mapOf("city" to "Addis Ababa"))
        val lines = out.lines()
        check("weather: headline", lines[0] == "Addis Ababa, Ethiopia: now 21°C (feels like 20°C), partly cloudy, humidity 55%, wind 9 km/h.", lines[0])
        check("weather: today and tomorrow", lines[1] == "Today: partly cloudy, 11-23°C, rain chance 40%." && lines[2] == "Tomorrow: light rain, 13-21°C, rain chance 80%.", lines.drop(1).toString())
        check("weather: the third day is labelled by date", lines[3] == "2026-10-16: overcast, 12-22°C, rain chance 20%.", lines[3])
        check("weather: the city name is URL-encoded", http.urls[0].contains("name=Addis+Ababa") && http.urls[1].contains("latitude=9.0250") && http.urls[1].contains("longitude=38.7469"), http.urls.toString())
        check("weather: unknown place", WeatherTool(FakeHttp(mapOf("geocoding-api" to """{"generationtime_ms":0.2}"""))).run(mapOf("city" to "Qwertyland")) == "No place found called 'Qwertyland'.")
        check("weather: service down", WeatherTool(FakeHttp(emptyMap(), fail = "geocoding")).run(mapOf("city" to "x")).startsWith("error: couldn't reach the weather service (HTTP 503)"))
        check("weather: garbage answer", WeatherTool(FakeHttp(mapOf("geocoding-api" to "<html>oops"))).run(mapOf("city" to "x")).contains("unexpected answer"))
        check("weather: missing city", WeatherTool(http).run(emptyMap()).startsWith("error: missing"))
        val missing = WeatherTool(FakeHttp(mapOf("geocoding-api" to GEO, "forecast" to """{"current":{"temperature_2m":5.0,"weather_code":99}}"""))).run(mapOf("city" to "x"))
        check("weather: a sparse forecast still reads fine", missing == "Addis Ababa, Ethiopia: now 5°C, thunderstorm with hail.", missing)
        check("weather codes", Wmo.describe(0) == "clear sky" && Wmo.describe(65) == "heavy rain" && Wmo.describe(1234) == "unclear weather")

        val wiki = FakeHttp(mapOf(
            "search/title" to """{"pages":[{"id":1,"key":"Ethiopian_cuisine","title":"Ethiopian cuisine","description":"Food of Ethiopia"}]}""",
            "page/summary" to """{"title":"Ethiopian cuisine","description":"Food of Ethiopia","extract":"Ethiopian cuisine characteristically consists of vegetable and often very spicy meat dishes."}""",
        ))
        val w = WikiTool(wiki).run(mapOf("topic" to "ethiopian food"))
        check("wiki: title, description, extract and source", w == "Ethiopian cuisine (Food of Ethiopia): Ethiopian cuisine characteristically consists of vegetable and often very spicy meat dishes. (source: Wikipedia)", w)
        check("wiki: URLs are encoded, titles use underscores", wiki.urls[0].contains("q=ethiopian+food") && wiki.urls[1].endsWith("/Ethiopian_cuisine"), wiki.urls.toString())
        check("wiki: accents are percent-encoded", FakeHttp(mapOf("search/title" to """{"pages":[{"title":"Café"}]}""", "page/summary" to """{"extract":"A cafe."}""")).let { h -> WikiTool(h).run(mapOf("topic" to "cafe")); h.urls[1].endsWith("/Caf%C3%A9") }, "")
        check("wiki: nothing found", WikiTool(FakeHttp(mapOf("search/title" to """{"pages":[]}"""))).run(mapOf("topic" to "qqq")).startsWith("Nothing found"))
        check("wiki: long extracts are cut", WikiTool(FakeHttp(mapOf("search/title" to """{"pages":[{"title":"A"}]}""", "page/summary" to JSONObject().put("extract", "x".repeat(3000)).toString()))).run(mapOf("topic" to "a")).length < 800)
        check("wiki: service down", WikiTool(FakeHttp(emptyMap(), fail = "search")).run(mapOf("topic" to "a")).startsWith("error: couldn't reach Wikipedia"))
    }

    // ================= nudges
    run {
        var now = at(14)
        val e = ProactiveEngine({ now })
        check("low battery speaks up once", e.onBattery(14, false)?.kind == "battery_low" && e.onBattery(13, false) == null)
        now = at(16, 5)
        check("...and again after the cooldown", e.onBattery(13, false)?.kind == "battery_low")
        check("charging stays quiet", ProactiveEngine({ at(14) }).onBattery(4, true) == null && ProactiveEngine({ at(14) }).onBattery(80, false) == null)
        check("critical battery speaks up even soon after a low warning", ProactiveEngine({ now }).let { it.onBattery(14, false); now = now.plusMinutes(1); it.onBattery(4, false)?.kind == "battery_critical" })
        check("quiet at night", ProactiveEngine({ at(23, 30) }).onBattery(3, false) == null && ProactiveEngine({ at(6, 30) }).onBattery(3, false) == null)
        var t2 = at(10)
        val capped = ProactiveEngine({ t2 }, maxPerDay = 1)
        capped.onBattery(14, false); t2 = t2.plusMinutes(5)
        check("daily cap holds", capped.onBattery(4, false) == null)
        t2 = at(10, 0, day = 13)
        check("a new day resets the cap", capped.onBattery(4, false)?.kind == "battery_critical")
        check("nudge wording includes the level", ProactiveEngine({ at(12) }).onBattery(9, false)!!.text.contains("9%"))
    }

    // ================= capabilities and prompt
    run {
        check("no stored setting means everything on", Capability.parse(null) == Capability.defaults && Capability.defaults.size == 5)
        check("round trip and unknown names ignored", Capability.parse(Capability.serialize(setOf(Capability.REMINDERS, Capability.NUDGES))) == setOf(Capability.REMINDERS, Capability.NUDGES) && Capability.parse("REMINDERS,FLYING") == setOf(Capability.REMINDERS))
        check("an empty setting means everything off", Capability.parse("").isEmpty())
        val full = Prompt.system(listOf("Likes tea"), at(15), Capability.defaults)
        check("prompt carries the date and time", full.contains("Current date and time: Monday, 12 October 2026, 15:00 (Africa/Addis_Ababa)."), full.lines().take(6).toString())
        check("prompt has no stray indentation", full.lines().none { it.startsWith("  ") && !it.startsWith("  Only") && !it.startsWith("  conv") && !it.startsWith("  current") && !it.startsWith("  must") && !it.startsWith("  If the user") })
        check("prompt lists facts", full.contains("- Likes tea"))
        check("prompt describes every enabled group", listOf("set_reminder", "add_task", "save_note", "set_alarm", "get_weather").all { full.contains(it) })
        val off = Prompt.system(emptyList(), at(15), setOf(Capability.TASKS_NOTES))
        check("prompt leaves out disabled groups", !off.contains("set_reminder") && !off.contains("set_alarm") && !off.contains("get_weather") && off.contains("add_task") && off.contains("Nothing saved yet."))
        check("prompt never lets the model claim it sent a message", full.contains("Never say you sent a message or made a call"))
        check("prompt without a clock still works", !Prompt.system(emptyList()).contains("Current date and time"))
    }

    println(if (failures == 0) "\nALL ASSISTANT TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
