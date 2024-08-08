package xyz.acrylicstyle.elasticdiscordindexer.commands

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.core.entity.channel.thread.ThreadChannel
import dev.kord.core.entity.interaction.ApplicationCommandInteraction
import dev.kord.rest.builder.interaction.GlobalMultiApplicationCommandBuilder
import dev.kord.rest.builder.interaction.channel
import dev.kord.rest.builder.interaction.number
import kotlinx.coroutines.flow.take
import org.apache.http.HttpHost
import org.apache.http.message.BasicHeader
import org.elasticsearch.client.RestClient
import xyz.acrylicstyle.elasticdiscordindexer.config.BotConfig
import xyz.acrylicstyle.elasticdiscordindexer.util.Util.optLong
import xyz.acrylicstyle.elasticdiscordindexer.util.Util.optSnowflake
import java.time.Instant

object IndexCommand : CommandHandler {
    override suspend fun canProcess(interaction: ApplicationCommandInteraction) = interaction.channel.getGuildOrNull() != null

    override suspend fun handle0(interaction: ApplicationCommandInteraction) {
        val limit = interaction.optLong("limit")?.toInt() ?: Int.MAX_VALUE
        val channelId = interaction.optSnowflake("channel")!!
        val channel = interaction.channel.getGuildOrNull()!!.getChannel(channelId)
        if (channel !is TopGuildMessageChannel && channel !is ThreadChannel) {
            error("unsupported channel type: ${channel.type}")
        }
        channel as GuildMessageChannel
        val topGuildChannel = if (channel is ThreadChannel) channel.getParent() else channel as TopGuildMessageChannel
        if (!topGuildChannel.getEffectivePermissions(interaction.user.id)
                .contains(Permissions(Permission.ViewChannel, Permission.ReadMessageHistory))) {
            interaction.respondEphemeral {
                content = "You don't have permission to read message history in that channel."
            }
            return
        }
        interaction.respondEphemeral {
            content = "Indexing channel..."
        }
        val msg = interaction.channel.createMessage { content = "Indexing channel..." }
        val startedAt = Instant.now().epochSecond
        var lastEditMessageAttempt = 0L
        var collectedMessagesCount = 0L
        val esClient = BotConfig.config.esClient()
        val messages = mutableListOf<Message>()
        val doIndex = {
            val br = BulkRequest.Builder()
            messages.forEach { msg ->
                br.operations { op ->
                    op.index { idx ->
                        idx.index("discord_${channel.guildId}_${channel.id}")
                            .id(msg.id.toString())
                            .document(msg.toMap(channel.guildId))
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
            messages.clear()
        }
        channel.getMessagesBefore(channel.lastMessageId ?: Snowflake.max)
            .let { if (limit != Int.MAX_VALUE) it.take(limit) else it }
            .collect { message ->
                collectedMessagesCount++
                if (System.currentTimeMillis() - lastEditMessageAttempt > 5000) {
                    val content = """
                        Indexing channel...
                        Elapsed time: ${Instant.now().epochSecond - startedAt} seconds
                        Indexed messages: $collectedMessagesCount
                    """.trimIndent()
                    msg.edit { this.content = content }
                    lastEditMessageAttempt = System.currentTimeMillis()
                }
                messages += message
                if (messages.size >= 100) {
                    doIndex()
                }
            }
        if (messages.isNotEmpty()) {
            doIndex()
        }
        msg.edit { content = "Indexed $collectedMessagesCount messages in <#$channelId> in ${Instant.now().epochSecond - startedAt} seconds." }
    }

    override fun register(builder: GlobalMultiApplicationCommandBuilder) {
        builder.input("index", "Indexes the channel.") {
            channel("channel", "The channel to index.") {
                required = true
            }
            number("limit", "The limit of messages to index.") {
                required = false
                minValue = 1.0
            }

            dmPermission = false
        }
    }
}

private fun Message.toMap(guildId: Snowflake): Map<String, Any> =
    mapOf(
        "id" to id.toString(),
        "content" to content,
        "author" to (author?.tag ?: "unknown"),
        "timestamp" to timestamp.toEpochMilliseconds(),
        "channel_id" to channelId.toString(),
        "guild_id" to guildId.toString(),
        "url" to "https://discord.com/channels/$guildId/$channelId/$id",
    )
