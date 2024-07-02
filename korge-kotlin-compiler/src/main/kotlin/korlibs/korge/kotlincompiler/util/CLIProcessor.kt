package korlibs.korge.kotlincompiler.util

typealias CLIProcessorHandler = suspend CLIProcessor.(ArrayDeque<String>) -> Unit

class CLIProcessor(
    val name: String = "CLI",
    val version: String = "unknown",
    val pipes: StdPipes = StdPipes,
) {
    val out = pipes.out
    val err = pipes.err

    data class Command(val name: String, val names: List<String>, val desc: String, val handler: CLIProcessorHandler)

    val scommands = LinkedHashMap<String, Command>()
    val commands = LinkedHashMap<String, Command>()

    fun registerCommand(name: String, vararg extraNames: String, desc: String = "Performs $name", processor: CLIProcessorHandler): CLIProcessor {
        val cmd = Command(name, listOf(name) + extraNames.toList(), desc, processor)
        scommands[name] = cmd
        for (key in cmd.names) commands[key] = cmd
        return this
    }

    init {
        registerCommand("help", "-help", "--help", "-h", "/?", "-?") { printHelp() }
    }

    fun printHelp() {
        out.println("$name - $version")
        out.println("")
        out.println("COMMANDS:")
        for (command in scommands.values) {
            out.println(" - ${command.name} - ${command.desc}")
        }
    }

    suspend fun process(args: Array<String>) = process(args.toList())
    suspend fun process(args: List<String>) = process(ArrayDeque(args))
    suspend fun process(args: ArrayDeque<String>) {
        if (args.isEmpty()) {
            printHelp()
        } else {
            while (args.isNotEmpty()) {
                val cmdStr = args.removeFirst()
                val cmd = commands[cmdStr] ?: error("Unknown command '$cmdStr'")
                cmd.handler(this, args)
            }
        }
    }
}