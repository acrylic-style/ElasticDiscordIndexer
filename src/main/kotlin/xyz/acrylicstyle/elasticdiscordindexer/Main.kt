@file:JvmName("MainKt")
package xyz.acrylicstyle.elasticdiscordindexer

import dev.kord.core.Kord
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.interaction.ApplicationCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.gateway.NON_PRIVILEGED
import dev.kord.gateway.PrivilegedIntent
import xyz.acrylicstyle.elasticdiscordindexer.commands.AskCommand
import xyz.acrylicstyle.elasticdiscordindexer.commands.IndexCommand
import xyz.acrylicstyle.elasticdiscordindexer.commands.IndexFilesCommand
import xyz.acrylicstyle.elasticdiscordindexer.config.BotConfig

@OptIn(PrivilegedIntent::class)
suspend fun main() {
    // headless mode
    System.setProperty("java.awt.headless", "true")

    // load config
    BotConfig

    val client = Kord(BotConfig.config.token)

    val commands = mapOf(
        "index" to IndexCommand,
        "index-files" to IndexFilesCommand,
        "ask" to AskCommand,
    )

    client.createGlobalApplicationCommands {
        commands.values.distinct().forEach { it.register(this) }
    }

    client.on<ApplicationCommandInteractionCreateEvent> {
        if (interaction.user.isBot) return@on
        commands[interaction.invokedCommandName]?.handle(interaction)
    }

    client.on<ReadyEvent> {
        println("Logged in as ${kord.getSelf().tag}")
    }

    client.login {
        intents {
            +Intents.NON_PRIVILEGED
            +Intent.MessageContent
        }
    }
}
