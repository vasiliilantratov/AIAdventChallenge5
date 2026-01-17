package org.example

import com.github.ajalt.clikt.core.subcommands
import org.example.cli.*

fun main(args: Array<String>) {
    MainCommand().apply {
        subcommands(
            IndexCommand(),
            SearchCommand(),
            StatsCommand(),
            ClearCommand(),
            RemoveCommand(),
            QaCommand(),
            HistoryCommand(),
            HelpCommand(),
            TeamCommand(),
            ChatCommand()
        )
    }.main(args)
}
