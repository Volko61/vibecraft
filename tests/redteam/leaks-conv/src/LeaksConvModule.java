package vibe.leaks_conv;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.ConversationFactory;
import org.bukkit.conversations.Prompt;
import org.bukkit.conversations.StringPrompt;
import org.bukkit.entity.Player;

/**
 * REDTEAM-LEAKS: a chat quiz with the Bukkit Conversation API. The player's next chat line is handed by
 * the server to the module's Prompt (outside VibeCraft's guard). /lkconv [player] asks the question.
 */
public class LeaksConvModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        int g = ctx.generation();
        ctx.command("lkconv", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (p == null) return;
            new ConversationFactory(ctx.plugin()).withLocalEcho(false).withTimeout(120).withFirstPrompt(new StringPrompt() {
                @Override
                public String getPromptText(ConversationContext c) {
                    return "quiz v" + g + ": combien font 2+2 ?";
                }
                @Override
                public Prompt acceptInput(ConversationContext c, String input) {
                    c.getForWhom().sendRawMessage("quiz v" + g + " answer: " + input);
                    return Prompt.END_OF_CONVERSATION;
                }
            }).buildConversation(p).begin();
            sender.sendMessage("quiz v" + g + " started for " + p.getName());
        });
    }
}
