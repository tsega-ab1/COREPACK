import com.corepack.pet.agent.ToolConfirmText

// The words of the approval question: it must show what a tool is about to receive, and survive odd input.

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

fun main() {
    check("title names the tool", ToolConfirmText.title("delete_file") == "Let the pet run \"delete_file\"?")
    val b = ToolConfirmText.body("files", "delete_file", """{"path":"/home/me/old.txt","force":true}""")
    check("body names the tool and its server and says it is about to run", b.contains("\"delete_file\"") && b.contains("\"files\"") && b.contains("about to run"), b)
    check("body shows each argument on its own line", b.contains("\n  path: /home/me/old.txt") && b.contains("\n  force: true"), b)
    check("no arguments is said plainly", ToolConfirmText.body("s", "t", "{}").endsWith("It needs no arguments.") && ToolConfirmText.body("s", "t", "").endsWith("It needs no arguments."))
    val cut = ToolConfirmText.body("s", "t", """{"path":"/a/very/long/pa""")
    check("arguments cut short mid-way still show what there is", cut.contains("It will get: {\"path\":\"/a/very/long/pa"), cut)
    val long = ToolConfirmText.body("s", "t", """{"text":"${"x".repeat(500)}"}""")
    check("a long value is shortened with dots", long.contains("x".repeat(120) + "...") && !long.contains("x".repeat(121)), long.length.toString())
    val many = ToolConfirmText.body("s", "t", ('a'..'l').withIndex().joinToString(",", "{", "}") { (i, c) -> "\"$c\":${i + 1}" })
    check("only the first eight arguments (alphabetically) are listed, the rest counted", many.contains("\n  h: 8") && !many.contains("\n  i: 9") && many.endsWith("and 4 more"), many)
    check("arguments are listed in alphabetical order", ToolConfirmText.body("s", "t", """{"zeta":1,"alpha":2,"mid":3}""").let { it.indexOf("alpha") < it.indexOf("mid") && it.indexOf("mid") < it.indexOf("zeta") })
    check("newlines in a value cannot break the layout", !ToolConfirmText.body("s", "t", "{\"a\":\"one\\ntwo\"}").contains("one\ntwo") && ToolConfirmText.body("s", "t", "{\"a\":\"one\\ntwo\"}").contains("a: one two"))
    check("nested values are shown as JSON text", ToolConfirmText.body("s", "t", """{"opts":{"deep":[1,2]}}""").contains("opts: {\"deep\":[1,2]}"))
    println(if (failures == 0) "\nALL CONFIRM TEXT TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
