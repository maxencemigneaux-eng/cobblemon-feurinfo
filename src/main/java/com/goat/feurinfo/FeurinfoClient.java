package com.goat.feurinfo;

import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * /feurinfo            → ouvre l'interface avec la recherche
 * /feurinfo dracaufeu  → ouvre directement la fiche (noms FR/EN ou numéro, avec autocomplétion)
 *
 * Commande 100 % côté client : elle marche sur n'importe quel serveur, même sans le mod.
 */
public final class FeurinfoClient implements ClientModInitializer {
    public static final String MOD_ID = "feurinfo";

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("feurinfo")
                .executes(context -> {
                    open(context.getSource().getClient(), null);
                    return 1;
                })
                .then(ClientCommandManager.argument("pokemon", StringArgumentType.greedyString())
                        .suggests(FeurinfoClient::suggestSpecies)
                        .executes(context -> {
                            String query = StringArgumentType.getString(context, "pokemon");
                            Species species = SpeciesLookup.find(query);
                            if (species == null) {
                                context.getSource().sendError(Component.translatable("feurinfo.not_found", query));
                                return 0;
                            }
                            open(context.getSource().getClient(), species);
                            return 1;
                        })));
    }

    private static CompletableFuture<Suggestions> suggestSpecies(CommandContext<FabricClientCommandSource> context,
                                                                 SuggestionsBuilder builder) {
        String typed = SpeciesLookup.normalize(builder.getRemaining());
        for (Species species : SpeciesLookup.all()) {
            String name = SpeciesLookup.displayName(species);
            if (SpeciesLookup.normalize(name).startsWith(typed)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    /** On attend la fin de la commande (le chat se ferme) avant d'ouvrir l'écran. */
    private static void open(Minecraft client, Species species) {
        client.tell(() -> client.setScreen(new FeurinfoScreen(species)));
    }
}
