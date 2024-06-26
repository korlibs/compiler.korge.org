package korlibs.korge.kotlincompiler.util

import java.io.PrintStream

typealias CLIProcessorHandler = CLIProcessor.(ArrayDeque<String>) -> Unit

class CLIProcessor(
    val name: String = "CLI",
    val version: String = "unknown",
    val stdout: PrintStream = System.out,
    val stderr: PrintStream = System.err,
) {
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
        stdout.println("$name - $version")
        stdout.println("")
        stdout.println("COMMANDS:")
        for (command in scommands.values) {
            stdout.println(" - ${command.name} - ${command.desc}")
        }
    }

    fun process(args: Array<String>) = process(args.toList())
    fun process(args: List<String>) = process(ArrayDeque(args))
    fun process(args: ArrayDeque<String>) {
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