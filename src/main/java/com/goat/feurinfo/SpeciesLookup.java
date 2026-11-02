package com.goat.feurinfo;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Recherche d'espèce par nom français, nom anglais ou numéro de Pokédex.
 * Les espèces viennent du registre de Cobblemon (synchronisé par le serveur à la connexion),
 * donc exactement la même version que celle à laquelle tu joues.
 */
public final class SpeciesLookup {
    private SpeciesLookup() {
    }

    /** Toutes les espèces, triées par numéro de Pokédex. */
    public static List<Species> all() {
        List<Species> list = new ArrayList<>(PokemonSpecies.getSpecies());
        list.sort(Comparator.comparingInt(Species::getNationalPokedexNumber));
        return list;
    }

    /** Nom affiché dans la langue du jeu (ex. "Dracaufeu"). */
    public static String displayName(Species species) {
        return species.getTranslatedName().getString();
    }

    /** "Dracaufeu", "dracaufeu", "Charizard", "6" ou "006" → Dracaufeu. Null si rien ne correspond. */
    public static Species find(String query) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return null;
        }
        List<Species> all = all();
        if (q.chars().allMatch(Character::isDigit)) {
            int number = Integer.parseInt(q);
            for (Species species : all) {
                if (species.getNationalPokedexNumber() == number) {
                    return species;
                }
            }
            return null;
        }
        Species prefixMatch = null;
        for (Species species : all) {
            String local = normalize(displayName(species));
            String english = normalize(species.getName());
            if (q.equals(local) || q.equals(english)) {
                return species;
            }
            if (prefixMatch == null && (local.startsWith(q) || english.startsWith(q))) {
                prefixMatch = species;
            }
        }
        return prefixMatch;
    }

    /** Minuscules, sans accents ni espaces/ponctuation : "M. Mime" → "mmime", "Évoli" → "evoli". */
    public static String normalize(String text) {
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
