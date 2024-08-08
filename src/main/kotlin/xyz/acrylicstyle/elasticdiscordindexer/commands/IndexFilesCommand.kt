package xyz.acrylicstyle.elasticdiscordindexer.commands

import co.elastic.clients.elasticsearch.core.BulkRequest
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.edit
import dev.kord.core.entity.interaction.ApplicationCommandInteraction
import dev.kord.rest.builder.interaction.GlobalMultiApplicationCommandBuilder
import dev.kord.rest.builder.interaction.channel
import dev.kord.rest.builder.interaction.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.acrylicstyle.elasticdiscordindexer.config.BotConfig
import xyz.acrylicstyle.elasticdiscordindexer.util.Util.optString
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.isDirectory

object IndexFilesCommand : CommandHandler {
    override suspend fun canProcess(interaction: ApplicationCommandInteraction) = interaction.channel.getGuildOrNull() != null

    override suspend fun handle0(interaction: ApplicationCommandInteraction) {
        val msg = interaction.respondEphemeral {
            content = "Indexing..."
        }
        val esClient = BotConfig.config.esClient()
        val br = BulkRequest.Builder()
        withContext(Dispatchers.IO) {
            Files.walk(Paths.get(interaction.optString("root")!!)).forEach { path ->
                if (path.isDirectory()) return@forEach
                if (!path.toString().endsWith(".md")) return@forEach
                br.operations { op ->
                    op.index { idx ->
                        @Suppress("BlockingMethodInNonBlockingContext")
                        idx.index(interaction.optString("index_name"))
                            .id(path.toString().substringAfter(interaction.optString("root")!!).replace('\\', '/'))
                            .document(
                                mapOf(
                                    "content" to Files.readString(path),
                                    "url" to (interaction.optString("url_prefix") + path.toString().substringAfter(interaction.optString("root")!!).replace('\\', '/')),
                                )
                            )
                    }
                }
            }
        }
        val result = esClient.bulk(br.build())
        if (result.errors()) {
            val error = result.items().mapNotNull { item -> item.error() }.mapNotNull { error -> error.reason() }
            val errorContent = """
                            Error occurred while indexing messages:
                            ```
                            ${error.joinToString("\n")}
                            ```
                        """.trimIndent()
            throw IllegalStateException(errorContent)
        }
        msg.edit { content = "Done!" }
    }

    override fun register(builder: GlobalMultiApplicationCommandBuilder) {
        builder.input("index-files", "Index files.") {
            string("index_name", "Index name") {
                required = true
            }
            string("root", "Root path.") {
                required = true
            }
            string("url_prefix", "URL prefix") {
                required = true
            }

            dmPermission = false
        }
    }
}
