package xyz.acrylicstyle.elasticdiscordindexer.commands

import co.elastic.clients.elasticsearch._types.SortOrder
import com.aallam.openai.api.chat.*
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.model.ModelId
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.edit
import dev.kord.core.entity.interaction.ApplicationCommandInteraction
import dev.kord.rest.builder.interaction.GlobalMultiApplicationCommandBuilder
import dev.kord.rest.builder.interaction.string
import kotlinx.serialization.json.*
import xyz.acrylicstyle.elasticdiscordindexer.config.BotConfig
import xyz.acrylicstyle.elasticdiscordindexer.util.Util.optString

object AskCommand : CommandHandler {
    override suspend fun canProcess(interaction: ApplicationCommandInteraction) = interaction.channel.getGuildOrNull() != null

    override suspend fun handle0(interaction: ApplicationCommandInteraction) {
        val input = interaction.optString("input")!!
        val modelName = interaction.optString("model") ?: "gpt-4o"
        val openAI = BotConfig.config.openAI()
        val chatMessages = mutableListOf(
            chatMessage {
                role = ChatRole.System
                content = "Return the URL returned from search function to user, and explain the content briefly."
            },
            chatMessage {
                role = ChatRole.User
                content = input
            },
        )
        val request = chatCompletionRequest {
            model = ModelId(modelName)
            messages = chatMessages
            tools {
                function(
                    name = "search",
                    description = "Search the query in the database.",
                    parameters = Parameters.buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Query to search.")
                            }
                        }
                        putJsonArray("required") {
                            add("query")
                        }
                    },
                )
            }
            toolChoice = ToolChoice.Auto
        }
        val msg = interaction.respondEphemeral { content = "Thinking..." }
        try {
            val response = openAI.chatCompletion(request)
            val message = response.choices.first().message
            chatMessages.append(message)
            for (toolCall in message.toolCalls.orEmpty()) {
                require(toolCall is ToolCall.Function) { "Tool call is not a function" }
                val functionResponse = toolCall.execute(interaction)
                chatMessages.append(toolCall, functionResponse)
            }
            var streamingMessage = ""
            var lastEdited = 0L
            openAI.chatCompletions(
                request = ChatCompletionRequest(
                    model = ModelId(modelName),
                    messages = chatMessages,
                )
            ).collect { chunk ->
                val choice = chunk.choices.getOrNull(0) ?: return@collect
                choice.delta?.content?.let { delta ->
                    if (delta.isNotEmpty()) {
                        streamingMessage += delta
                        if (System.currentTimeMillis() - lastEdited > 1000) {
                            lastEdited = System.currentTimeMillis()
                            msg.edit { content = streamingMessage }
                        }
                    }
                }
            }
            msg.edit { content = streamingMessage }
        } catch (e: Exception) {
            msg.edit { content = "Failed to generate message." }
            e.printStackTrace()
        }
    }

    private suspend fun callSearch(interaction: ApplicationCommandInteraction, args: JsonObject): String {
        val esClient = BotConfig.config.esClient()
        val query = args.getValue("query").jsonPrimitive.content
        val guildId = interaction.channel.getGuildOrNull()!!.id
        val indexName = interaction.optString("index_name") ?: "discord_${guildId}_${interaction.channelId}"
        val response = try {
            esClient.search({ s ->
                s.index(indexName)
                    .query { q ->
                        q.match { t ->
                            t.field("content")
                                .query(query)
                        }
                    }
                    .sort { sort ->
                        sort.field { field ->
                            field.field("timestamp")
                                .order(SortOrder.Desc)
                        }
                    }
                    .size(10)
            }, HashMap::class.java)
        } catch (e: Exception) {
            esClient.search({ s ->
                s.index(indexName)
                    .query { q ->
                        q.match { t ->
                            t.field("content")
                                .query(query)
                        }
                    }
                    .size(10)
            }, HashMap::class.java)
        }
        val totalHits = response.hits().total()?.value()
        if (totalHits == null || totalHits == 0L) {
            return "No results found."
        }
        val hits = response.hits().hits()
        return hits.joinToString("\n\n----- content separator -----\n\n") {
            val map = it.source().orEmpty()
            """
                URL: ${map["url"]}
                Content: ${map["content"]}
            """.trimIndent()
        }
    }

    private val functions = mapOf(
        "search" to ::callSearch,
    )

    private suspend fun ToolCall.Function.execute(interaction: ApplicationCommandInteraction): String {
        val functionToCall = functions[function.name] ?: error("Function not found: ${function.name}")
        val args = function.argumentsAsJson()
        val response = functionToCall(interaction, args)
        if (BotConfig.config.debug) {
            println("Function ${function.name} executed with args: $args")
            println("Response: $response")
        }
        return response
    }

    override fun register(builder: GlobalMultiApplicationCommandBuilder) {
        builder.input("ask", "Ask a question to the bot.") {
            string("input", "Input string to ask.") {
                required = true
            }
            string("model", "Specify the model to use.")
            string("index_name", "Index name")
            dmPermission = false
        }
    }
}

private fun MutableList<ChatMessage>.append(message: ChatMessage) {
    add(
        ChatMessage(
            role = message.role,
            content = message.content.orEmpty(),
            toolCalls = message.toolCalls,
            toolCallId = message.toolCallId,
        )
    )
}

private fun MutableList<ChatMessage>.append(toolCall: ToolCall.Function, functionResponse: String) {
    val message = ChatMessage(
        role = ChatRole.Tool,
        toolCallId = toolCall.id,
        name = toolCall.function.name,
        content = functionResponse
    )
    add(message)
}
