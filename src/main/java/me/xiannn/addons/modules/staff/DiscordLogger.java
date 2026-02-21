package me.xiannn.addons.modules.staff;

import me.xiannn.addons.AddonLogger;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.awt.Color;
import java.io.File;
import java.time.Instant;
import java.util.Map;

/**
 * Handles Discord bot connection and embed logging.
 */
public class DiscordLogger {

    private final StaffModule module;
    private final AddonLogger log;
    private JDA jda;
    
    private File configFile;
    private YamlConfiguration config;

    public DiscordLogger(StaffModule module) {
        this.module = module;
        this.log = module.getLog();
    }

    public void connect() {
        configFile = new File(module.getPlugin().getModuleFolder(module), "discord.yml");
        if (!configFile.exists()) {
            module.getPlugin().saveResource("StaffModule/discord.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        if (!config.getBoolean("bot.enabled")) {
            log.info("Discord bot disabled in config.");
            return;
        }

        String token = config.getString("bot.token");
        if (token == null || token.equals("YOUR_BOT_TOKEN_HERE")) {
            log.warn("Discord bot enabled but token is invalid!");
            return;
        }

        try {
            // Minimal intents since we are mostly logging OUT
            jda = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES)
                    .setActivity(getActivity())
                    .build();
            jda.awaitReady();
            log.info("Discord bot connected as " + jda.getSelfUser().getAsTag());
        } catch (Exception e) {
            log.error("Failed to connect Discord bot!", e);
        }
    }

    public void disconnect() {
        if (jda != null) {
            jda.shutdown();
            log.info("Discord bot disconnected.");
        }
    }

    private Activity getActivity() {
        String type = config.getString("bot.activity.type", "WATCHING");
        String text = config.getString("bot.activity.text", "the server");
        try {
            return Activity.of(Activity.ActivityType.valueOf(type), text);
        } catch (Exception e) {
            return Activity.watching(text);
        }
    }

    /**
     * Sends a log embed to the configured channel category.
     *
     * @param category     Config key in 'channels' (e.g., "punishments", "gamemode")
     * @param templateKey  Config key in 'embeds' (e.g., "ban", "gamemode")
     * @param placeholders Map of placeholder -> value (e.g., "{player}" -> "Xiannn")
     */
    public void log(String category, String templateKey, Map<String, String> placeholders) {
        if (jda == null) return;

        // Get Channel ID
        String channelId = config.getString("channels." + category);
        if (channelId == null || channelId.equals("0") || channelId.isEmpty()) {
            return; // logging disabled for this category
        }

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            log.warn("Discord channel not found for category: " + category + " (ID: " + channelId + ")");
            return;
        }

        // Build Embed
        ConfigurationSection embedCfg = config.getConfigurationSection("embeds." + templateKey);
        if (embedCfg == null) {
            log.warn("Embed template not found: " + templateKey);
            return;
        }

        EmbedBuilder eb = new EmbedBuilder();
        
        // Author
        if (embedCfg.contains("author")) {
            eb.setAuthor(replace(embedCfg.getString("author.name"), placeholders));
        }

        // Color
        String hex = embedCfg.getString("color", "#FFFFFF");
        try {
            eb.setColor(Color.decode(hex));
        } catch (Exception ignored) {}

        // Description
        if (embedCfg.contains("description")) {
            eb.setDescription(replace(embedCfg.getString("description"), placeholders));
        }

        // Title
        if (embedCfg.contains("title")) {
            eb.setTitle(replace(embedCfg.getString("title"), placeholders));
        }

        // Fields
        if (embedCfg.contains("fields")) {
            for (Map<?, ?> map : embedCfg.getMapList("fields")) {
                Object nameObj = map.get("name");
                Object valObj = map.get("value");
                Object inlineObj = map.get("inline");

                String name = replace(nameObj != null ? nameObj.toString() : "", placeholders);
                String value = replace(valObj != null ? valObj.toString() : "", placeholders);
                
                // FIX: Safe boolean casting
                boolean inline = false;
                if (inlineObj instanceof Boolean) {
                    inline = (Boolean) inlineObj;
                }

                eb.addField(name, value, inline);
            }
        }

        // Footer
        if (embedCfg.contains("footer")) {
            eb.setFooter(replace(embedCfg.getString("footer.text"), placeholders));
        }

        // Timestamp
        eb.setTimestamp(Instant.now());

        // Send Async
        channel.sendMessageEmbeds(eb.build()).queue();
    }

    private String replace(String text, Map<String, String> placeholders) {
        if (text == null) return null;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }
}
