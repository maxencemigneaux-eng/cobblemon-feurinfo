package com.goat.feurinfo;

import com.cobblemon.mod.common.api.abilities.AbilityTemplate;
import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.categories.DamageCategory;
import com.cobblemon.mod.common.api.pokemon.moves.Learnset;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Écran /feurinfo :
 *  - à gauche : le Pokémon en 3D (Glisser pour tourner, clic = cri), sa forme (< >), un bouton shiny,
 *    puis ses talents (survol = description) et ses stats de base ;
 *  - à droite : ses attaques par onglet (Niveau, CT, Œufs, Tuteur, Évolution, Autres),
 *    survol d'une attaque = sa description.
 */
public final class FeurinfoScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int TOP = 30;
    private static final int ROW_H = 16;
    /** Icônes rondes des types de Cobblemon : 18 icônes de 36 x 36 px côte à côte. */
    private static final ResourceLocation TYPE_ICONS = ResourceLocation.fromNamespaceAndPath("cobblemon", "textures/gui/types.png");
    private static final int TYPE_ICON_SRC = 36;
    private static final int TYPE_ICON_SHEET_W = 648;
    private static final int TYPE_ICON = 14;
    private static final int TYPE_COL_W = 24;

    private enum Tab {
        LEVEL("level"), TM("tm"), EGG("egg"), TUTOR("tutor"), EVOLUTION("evolution"), OTHER("other");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private record Row(String level, MoveTemplate move) {
    }

    private record AbilityLine(AbilityTemplate template, boolean hidden) {
    }

    /** Ordre d'affichage des stats de base. */
    private static final Stats[] STATS = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
    private static final String[] STAT_KEYS = {"hp", "atk", "def", "spa", "spd", "spe"};
    private static final int INFO_ROW = 10;

    // --- État (conservé quand l'écran est reconstruit, ex. redimensionnement) ---
    private Species species;
    private final List<FormData> forms = new ArrayList<>();
    private int formIndex;
    private boolean shiny;
    private Tab tab = Tab.LEVEL;
    private final Map<Tab, List<Row>> rows = new EnumMap<>(Tab.class);
    private final List<AbilityLine> abilities = new ArrayList<>();
    private int scroll;
    private String moveFilterText = "";
    private final List<Row> filteredRows = new ArrayList<>();
    private String searchText = "";
    private Component searchError;
    private float rotation = 35F;
    private boolean draggingModel;
    private long lastFrameMs;

    // --- Widgets ---
    private EditBox search;
    private EditBox moveFilter;
    private ModelWidget model;
    private Button shinyButton;
    private final Map<Tab, Button> tabButtons = new EnumMap<>(Tab.class);

    // --- Mise en page ---
    private int leftX;
    private int leftW;
    private int rightX;
    private int rightW;
    private int panelY;
    private int panelH;
    private int modelX;
    private int modelY;
    private int modelW;
    private int modelH;
    private int filterY;
    private int formRowY;
    private int infoY;
    private int listTop;
    private int listBottom;

    public FeurinfoScreen(@Nullable Species species) {
        super(Component.translatable("feurinfo.title"));
        for (Tab t : Tab.values()) {
            rows.put(t, new ArrayList<>());
        }
        if (species != null) {
            setSpecies(species);
        }
    }

    // =======================================================================================
    // Données
    // =======================================================================================

    private void setSpecies(Species newSpecies) {
        species = newSpecies;
        forms.clear();
        FormData standard = newSpecies.getStandardForm();
        forms.add(standard);
        for (FormData form : newSpecies.getForms()) {
            if (form != standard && !form.getName().equalsIgnoreCase(standard.getName())) {
                forms.add(form);
            }
        }
        formIndex = 0;
        shiny = false;
        tab = Tab.LEVEL;
        rotation = 35F;
        model = null;
        computeRows();
    }

    private FormData form() {
        return forms.get(formIndex);
    }

    private void computeRows() {
        for (Tab t : Tab.values()) {
            rows.get(t).clear();
        }
        scroll = 0;
        if (species == null) {
            return;
        }
        computeAbilities();
        Learnset learnset = form().getMoves(); // les formes sans attaques propres reprennent celles de l'espèce

        String evolutionLabel = I18n.get("feurinfo.level.evolution");
        Map<Integer, List<MoveTemplate>> byLevel = new TreeMap<>(learnset.getLevelUpMoves());
        for (Map.Entry<Integer, List<MoveTemplate>> entry : byLevel.entrySet()) {
            int level = entry.getKey();
            for (MoveTemplate move : entry.getValue()) {
                rows.get(Tab.LEVEL).add(new Row(level <= 0 ? evolutionLabel : String.valueOf(level), move));
            }
        }
        addSorted(Tab.TM, learnset.getTmMoves());
        addSorted(Tab.EGG, learnset.getEggMoves());
        addSorted(Tab.TUTOR, learnset.getTutorMoves());
        addSorted(Tab.EVOLUTION, learnset.getEvolutionMoves());
        List<MoveTemplate> other = new ArrayList<>();
        other.addAll(learnset.getLegacyMoves());
        other.addAll(learnset.getSpecialMoves());
        other.addAll(learnset.getFormChangeMoves());
        addSorted(Tab.OTHER, other);

        if (rows.get(tab).isEmpty()) {
            for (Tab t : Tab.values()) {
                if (!rows.get(t).isEmpty()) {
                    tab = t;
                    break;
                }
            }
        }
        applyFilter();
    }

    /** Talents de la forme : les normaux d'abord, puis le talent caché. */
    private void computeAbilities() {
        abilities.clear();
        List<AbilityLine> hidden = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PotentialAbility potential : form().getAbilities()) {
            AbilityTemplate template = potential.getTemplate();
            if (!seen.add(template.getName())) {
                continue;
            }
            boolean isHidden = potential instanceof HiddenAbility;
            (isHidden ? hidden : abilities).add(new AbilityLine(template, isHidden));
        }
        abilities.addAll(hidden);
    }

    private static String abilityName(AbilityTemplate template) {
        String key = template.getDisplayName();
        return I18n.exists(key) ? I18n.get(key) : template.getName();
    }

    /** Hauteur du bloc talents + stats (sous le modèle). */
    private int infoHeight() {
        int abilityLines = Math.max(3, abilities.size());
        return INFO_ROW + abilityLines * INFO_ROW + 6 + INFO_ROW + 6 * INFO_ROW + INFO_ROW + 2;
    }

    // --- Recherche d'attaque dans la liste du Pokémon ---

    private boolean filtering() {
        return !SpeciesLookup.normalize(moveFilterText).isEmpty();
    }

    /** Quand on cherche, on regarde dans TOUTES les catégories (Niveau, CT, Œufs...). */
    private void applyFilter() {
        filteredRows.clear();
        String query = SpeciesLookup.normalize(moveFilterText);
        if (query.isEmpty()) {
            return;
        }
        for (Tab t : Tab.values()) {
            for (Row row : rows.get(t)) {
                MoveTemplate move = row.move();
                String local = SpeciesLookup.normalize(move.getDisplayName().getString());
                String english = SpeciesLookup.normalize(move.getName());
                String type = SpeciesLookup.normalize(move.getElementalType().getDisplayName().getString());
                if (local.contains(query) || english.contains(query) || type.equals(query)) {
                    filteredRows.add(new Row(methodLabel(t, row), move));
                }
            }
        }
    }

    private static String methodLabel(Tab t, Row row) {
        return switch (t) {
            case LEVEL -> row.level().chars().allMatch(Character::isDigit)
                    ? I18n.get("feurinfo.method.level", row.level())
                    : row.level();
            case TM -> I18n.get("feurinfo.method.tm");
            case EGG -> I18n.get("feurinfo.method.egg");
            case TUTOR -> I18n.get("feurinfo.method.tutor");
            case EVOLUTION -> I18n.get("feurinfo.method.evolution");
            case OTHER -> I18n.get("feurinfo.method.other");
        };
    }

    private List<Row> currentRows() {
        return filtering() ? filteredRows : rows.get(tab);
    }

    private void onMoveFilterChanged(String text) {
        moveFilterText = text;
        scroll = 0;
        applyFilter();
        updateTabButtons();
    }

    private void addSorted(Tab target, Collection<MoveTemplate> moves) {
        List<MoveTemplate> list = new ArrayList<>(new LinkedHashSet<>(moves));
        list.sort(Comparator.comparing(move -> move.getDisplayName().getString()));
        for (MoveTemplate move : list) {
            rows.get(target).add(new Row("", move));
        }
    }

    private List<Tab> visibleTabs() {
        List<Tab> visible = new ArrayList<>();
        for (Tab t : Tab.values()) {
            if (!rows.get(t).isEmpty()) {
                visible.add(t);
            }
        }
        if (visible.isEmpty()) {
            visible.add(Tab.LEVEL);
        }
        return visible;
    }

    /** Nom de forme traduit par Cobblemon ("Forme d'Alola", "Méga-Dracaufeu X"...). */
    private static String formName(FormData form, boolean standard) {
        String key = "cobblemon.ui.pokedex.info.form." + form.showdownId();
        if (I18n.exists(key) && !I18n.get(key).isBlank()) {
            return I18n.get(key);
        }
        if (standard && I18n.exists("cobblemon.ui.pokedex.info.form.normal")) {
            return I18n.get("cobblemon.ui.pokedex.info.form.normal");
        }
        return form.getName();
    }

    // =======================================================================================
    // Construction de l'écran
    // =======================================================================================

    @Override
    protected void init() {
        tabButtons.clear();

        panelY = TOP;
        panelH = height - TOP - MARGIN;
        leftX = MARGIN;
        leftW = Mth.clamp(width * 34 / 100, 130, 210);
        rightX = leftX + leftW + 6;
        rightW = width - MARGIN - rightX;
        modelX = leftX + 4;
        modelY = panelY + 4;
        modelW = leftW - 8;
        // Sous le modèle : la ligne forme / shiny (24 px), puis les talents et les stats de base
        modelH = Math.max(64, panelH - 4 - 24 - 4 - (species == null ? 0 : infoHeight()) - 4);
        formRowY = modelY + modelH + 4;
        infoY = formRowY + 24 + 2;
        filterY = panelY + 24;
        listTop = panelY + 58;
        listBottom = panelY + panelH - 4;

        int searchW = Math.min(170, width / 3);
        search = new EditBox(font, width - MARGIN - searchW, 8, searchW, 16, Component.translatable("feurinfo.search.hint"));
        search.setHint(Component.translatable("feurinfo.search.hint").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(40);
        search.setValue(searchText);
        search.setResponder(this::onSearchChanged);
        addRenderableWidget(search);

        if (species == null) {
            setInitialFocus(search);
            return;
        }

        model = createModel();
        addRenderableWidget(model);

        int rowY = formRowY;
        if (forms.size() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> changeForm(-1))
                    .bounds(leftX + 4, rowY, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> changeForm(1))
                    .bounds(leftX + leftW - 48, rowY, 20, 20).build());
        }
        // Petit bouton shiny à droite de la ligne des formes
        shinyButton = addRenderableWidget(Button.builder(shinyLabel(), b -> toggleShiny())
                .bounds(leftX + leftW - 24, rowY, 20, 20).tooltip(shinyTooltip()).build());

        List<Tab> visible = visibleTabs();
        int gap = 2;
        int tabW = Math.max(30, (rightW - 8 - gap * (visible.size() - 1)) / visible.size());
        int tabX = rightX + 4;
        for (Tab t : visible) {
            Button button = Button.builder(Component.translatable("feurinfo.tab." + t.key), b -> selectTab(t))
                    .bounds(tabX, panelY + 4, tabW, 16).build();
            button.active = t != tab;
            tabButtons.put(t, addRenderableWidget(button));
            tabX += tabW + gap;
        }

        // Barre de recherche d'attaque (cherche dans toutes les catégories du Pokémon)
        moveFilter = new EditBox(font, rightX + 6, filterY, rightW - 12 - 72, 16, Component.translatable("feurinfo.filter.hint"));
        moveFilter.setHint(Component.translatable("feurinfo.filter.hint").withStyle(ChatFormatting.DARK_GRAY));
        moveFilter.setMaxLength(40);
        moveFilter.setValue(moveFilterText);
        moveFilter.setResponder(this::onMoveFilterChanged);
        addRenderableWidget(moveFilter);
        updateTabButtons();
    }

    private ModelWidget createModel() {
        Set<String> aspects = new HashSet<>(form().getAspects());
        if (shiny) {
            aspects.add("shiny");
        }
        RenderablePokemon renderable = new RenderablePokemon(species, aspects, ItemStack.EMPTY);
        // Cobblemon utilise une échelle de 2.7 et un décalage de -12 pour une zone de 118 x 100 px.
        // On garde ces proportions (le plus petit des deux rapports, pour ne pas couper le Pokémon)
        // et on centre verticalement dans la zone.
        float ratio = Math.min(modelH / 100F, modelW / 118F);
        double offsetY = -12.0 * ratio + (modelH - 100.0 * ratio) / 2.0;
        return new ModelWidget(modelX, modelY, modelW, modelH, renderable,
                2.7F * ratio, rotation, offsetY, true, false, 13);
    }

    private void replaceModel() {
        if (model != null) {
            removeWidget(model);
        }
        model = createModel();
        addRenderableWidget(model);
    }

    private Component shinyLabel() {
        return shiny
                ? Component.literal("★").withStyle(ChatFormatting.GOLD)
                : Component.literal("☆").withStyle(ChatFormatting.GRAY);
    }

    private Tooltip shinyTooltip() {
        return Tooltip.create(Component.translatable(shiny ? "feurinfo.shiny.on" : "feurinfo.shiny.off"));
    }

    // =======================================================================================
    // Actions
    // =======================================================================================

    private void changeForm(int delta) {
        formIndex = Math.floorMod(formIndex + delta, forms.size());
        computeRows();
        rebuildWidgets(); // onglets, talents et stats peuvent changer d'une forme à l'autre
    }

    private void toggleShiny() {
        shiny = !shiny;
        shinyButton.setMessage(shinyLabel());
        shinyButton.setTooltip(shinyTooltip());
        replaceModel();
    }

    private void selectTab(Tab t) {
        tab = t;
        scroll = 0;
        if (moveFilter != null && filtering()) {
            moveFilter.setValue(""); // cliquer un onglet quitte la recherche
        }
        updateTabButtons();
    }

    private void updateTabButtons() {
        boolean searching = filtering();
        tabButtons.forEach((t, button) -> button.active = searching || t != tab);
    }

    private void onSearchChanged(String text) {
        searchText = text;
        searchError = null;
        search.setSuggestion(null);
        String typed = SpeciesLookup.normalize(text);
        if (typed.isEmpty()) {
            return;
        }
        for (Species s : SpeciesLookup.all()) {
            String name = SpeciesLookup.displayName(s);
            if (name.toLowerCase().startsWith(text.toLowerCase()) && name.length() > text.length()) {
                search.setSuggestion(name.substring(text.length()));
                return;
            }
        }
    }

    private void runSearch() {
        Species found = SpeciesLookup.find(search.getValue());
        if (found == null) {
            searchError = Component.translatable("feurinfo.not_found", search.getValue());
            return;
        }
        searchText = "";
        searchError = null;
        setSpecies(found);
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (search != null && search.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                runSearch();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                Species found = SpeciesLookup.find(search.getValue());
                if (found != null) {
                    search.setValue(SpeciesLookup.displayName(found));
                }
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && model != null && isInModel(mouseX, mouseY)) {
            draggingModel = true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingModel) {
            rotation += (float) dragX * 2.5F;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingModel = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (species != null && mouseX >= rightX && mouseX < rightX + rightW && mouseY >= listTop && mouseY < listBottom) {
            scroll -= (int) Math.signum(scrollY) * 3;
            scroll = Mth.clamp(scroll, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isInModel(double mouseX, double mouseY) {
        return mouseX >= modelX && mouseX < modelX + modelW && mouseY >= modelY && mouseY < modelY + modelH;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, currentRows().size() - visibleRows());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =======================================================================================
    // Affichage
    // =======================================================================================

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        drawPanel(graphics, leftX, panelY, leftW, panelH);
        drawPanel(graphics, rightX, panelY, rightW, panelH);
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xC0101418);
        graphics.renderOutline(x, y, w, h, 0xFF3A4450);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrameMs == 0 ? 0F : Math.min(0.1F, (now - lastFrameMs) / 1000F);
        lastFrameMs = now;
        if (model != null) {
            if (!draggingModel) {
                rotation += dt * 20F; // rotation automatique lente
            }
            model.getRotationVector().y = rotation;
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        Component header = species == null
                ? title.copy().withStyle(ChatFormatting.BOLD)
                : Component.literal(String.format("#%04d  ", species.getNationalPokedexNumber())).withStyle(ChatFormatting.GRAY)
                        .append(species.getTranslatedName().copy().withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        graphics.drawString(font, header, MARGIN, 12, 0xFFFFFFFF, true);
        if (species != null) {
            // Types de la forme, juste après le nom
            int typeX = MARGIN + font.width(header) + 6;
            for (ElementalType type : form().getTypes()) {
                drawTypeIcon(graphics, type, typeX, 9, 14);
                typeX += 16;
            }
        }
        if (searchError != null) {
            graphics.drawString(font, searchError, search.getX() - font.width(searchError) - 6, 12, 0xFFFF6060, true);
        }

        if (species == null) {
            Component empty = Component.translatable("feurinfo.empty");
            graphics.drawCenteredString(font, empty, rightX + rightW / 2, panelY + panelH / 2 - 4, 0xFFB0B8C0);
            return;
        }

        graphics.drawCenteredString(font, Component.translatable("feurinfo.drag_hint").withStyle(ChatFormatting.ITALIC),
                leftX + leftW / 2, modelY + modelH - 10, 0xFF6A7480);
        int rowY = formRowY;
        String formLabel = formName(form(), formIndex == 0);
        int formLeft = leftX + (forms.size() > 1 ? 26 : 4);
        int formRight = leftX + leftW - (forms.size() > 1 ? 50 : 26);
        int maxFormW = formRight - formLeft;
        if (font.width(formLabel) > maxFormW) {
            formLabel = font.plainSubstrByWidth(formLabel, Math.max(0, maxFormW - font.width("…"))) + "…";
        }
        graphics.drawCenteredString(font, formLabel, (formLeft + formRight) / 2, rowY + 6, 0xFFFFFFFF);

        drawInfo(graphics, mouseX, mouseY);
        drawMoves(graphics, mouseX, mouseY);
    }

    /** Talents (survol = description) et stats de base, sous le modèle 3D. */
    private void drawInfo(GuiGraphics graphics, int mouseX, int mouseY) {
        int x0 = leftX + 6;
        int x1 = leftX + leftW - 6;
        int gray = 0xFF9AA4AE;
        int abilitiesY = infoY + INFO_ROW;
        int statsTitleY = infoY + INFO_ROW + Math.max(3, abilities.size()) * INFO_ROW + 6;
        int statsY = statsTitleY + INFO_ROW;
        Map<Stat, Integer> base = form().getBaseStats();

        int labelW = 0;
        for (String key : STAT_KEYS) {
            labelW = Math.max(labelW, font.width(I18n.get("feurinfo.stat." + key)));
        }
        int valueRight = x0 + labelW + 4 + font.width("255");
        int barX = valueRight + 4;
        int barW = Math.max(10, x1 - barX);

        // 1er passage : traits, survol, barres
        graphics.fill(x0 - 2, infoY - 2, x1 + 2, infoY - 1, 0xFF3A4450);
        graphics.fill(x0 - 2, statsTitleY - 4, x1 + 2, statsTitleY - 3, 0xFF3A4450);
        AbilityLine hoveredAbility = null;
        for (int i = 0; i < abilities.size(); i++) {
            int ry = abilitiesY + i * INFO_ROW;
            if (mouseX >= x0 - 2 && mouseX < x1 + 2 && mouseY >= ry - 1 && mouseY < ry + INFO_ROW - 1) {
                hoveredAbility = abilities.get(i);
                graphics.fill(x0 - 2, ry - 1, x1 + 2, ry + INFO_ROW - 1, 0x40FFFFFF);
            }
        }
        int total = 0;
        for (int i = 0; i < STATS.length; i++) {
            int value = base.getOrDefault(STATS[i], 0);
            total += value;
            int ry = statsY + i * INFO_ROW;
            graphics.fill(barX, ry + 1, barX + barW, ry + 7, 0xFF262C33);
            int filled = Math.round(barW * Math.min(255, value) / 255F);
            graphics.fill(barX, ry + 1, barX + filled, ry + 7, statColor(value));
        }
        graphics.flush();

        // 2e passage : textes
        graphics.drawString(font, Component.translatable("feurinfo.abilities"), x0, infoY, gray, false);
        for (int i = 0; i < abilities.size(); i++) {
            AbilityLine line = abilities.get(i);
            int ry = abilitiesY + i * INFO_ROW;
            String suffix = line.hidden() ? " " + I18n.get("feurinfo.ability.hidden") : "";
            String name = abilityName(line.template());
            int maxW = x1 - x0 - 2 - font.width("• ") - font.width(suffix);
            if (font.width(name) > maxW) {
                name = font.plainSubstrByWidth(name, Math.max(0, maxW - font.width("…"))) + "…";
            }
            String text = "• " + name;
            graphics.drawString(font, text, x0 + 2, ry, line.hidden() ? 0xFFC9B6FF : 0xFFFFFFFF, false);
            if (!suffix.isEmpty()) {
                graphics.drawString(font, suffix, x0 + 2 + font.width(text), ry, 0xFF8A949E, false);
            }
        }
        if (abilities.isEmpty()) {
            graphics.drawString(font, "—", x0 + 2, abilitiesY, gray, false);
        }

        graphics.drawString(font, Component.translatable("feurinfo.stats"), x0, statsTitleY, gray, false);
        for (int i = 0; i < STATS.length; i++) {
            int value = base.getOrDefault(STATS[i], 0);
            int ry = statsY + i * INFO_ROW;
            graphics.drawString(font, I18n.get("feurinfo.stat." + STAT_KEYS[i]), x0, ry, gray, false);
            drawRight(graphics, Component.literal(String.valueOf(value)), valueRight, ry, 0xFFFFFFFF);
        }
        int totalY = statsY + STATS.length * INFO_ROW;
        graphics.drawString(font, Component.translatable("feurinfo.stat.total"), x0, totalY, gray, false);
        drawRight(graphics, Component.literal(String.valueOf(total)), valueRight, totalY, 0xFFFFD866);

        if (hoveredAbility != null) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(Component.literal(abilityName(hoveredAbility.template())).withStyle(ChatFormatting.BOLD).getVisualOrderText());
            if (hoveredAbility.hidden()) {
                lines.add(Component.translatable("feurinfo.ability.hidden_long").withStyle(ChatFormatting.LIGHT_PURPLE).getVisualOrderText());
            }
            String descKey = hoveredAbility.template().getDescription();
            if (I18n.exists(descKey)) {
                lines.addAll(font.split(Component.literal(I18n.get(descKey)).withStyle(ChatFormatting.GRAY), 220));
            }
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    /** Couleurs des barres de stats, comme sur Showdown. */
    private static int statColor(int value) {
        if (value < 30) return 0xFFF34444;
        if (value < 60) return 0xFFFF7F0F;
        if (value < 90) return 0xFFFFDD57;
        if (value < 120) return 0xFFA0E515;
        if (value < 150) return 0xFF23CD5E;
        return 0xFF00C2B8;
    }

    private void drawMoves(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Row> list = currentRows();
        boolean searching = filtering();
        int x0 = rightX + 6;
        int x1 = rightX + rightW - 8;
        boolean showLevel = searching || tab == Tab.LEVEL;
        boolean showCategory = rightW >= 330;

        // Colonnes, de droite à gauche
        int ppRight = x1;
        int accRight = ppRight - 24;
        int powRight = accRight - 34;
        int catX = powRight - 34 - 58;
        int typeX = (showCategory ? catX : powRight - 34) - TYPE_COL_W - 2;
        int nameX = x0 + (showLevel ? (searching ? 48 : 26) : 0);

        // Nombre d'attaques, à droite de la barre de recherche
        Component count = Component.translatable("feurinfo.moves_count", list.size());
        drawRight(graphics, count, x1, filterY + 4, searching ? 0xFFFFD866 : 0xFF9AA4AE);

        int headerY = panelY + 46;
        int gray = 0xFF9AA4AE;
        if (showLevel) {
            graphics.drawString(font, Component.translatable(searching ? "feurinfo.col.method" : "feurinfo.col.level"), x0, headerY, gray, false);
        }
        graphics.drawString(font, Component.translatable("feurinfo.col.name"), nameX, headerY, gray, false);
        graphics.drawString(font, Component.translatable("feurinfo.col.type"), typeX, headerY, gray, false);
        if (showCategory) {
            graphics.drawString(font, Component.translatable("feurinfo.col.category"), catX, headerY, gray, false);
        }
        drawRight(graphics, Component.translatable("feurinfo.col.power"), powRight, headerY, gray);
        drawRight(graphics, Component.translatable("feurinfo.col.accuracy"), accRight, headerY, gray);
        drawRight(graphics, Component.translatable("feurinfo.col.pp"), ppRight, headerY, gray);
        graphics.fill(x0 - 2, listTop - 2, x1 + 2, listTop - 1, 0xFF3A4450);

        if (list.isEmpty()) {
            Component empty = searching
                    ? Component.translatable("feurinfo.no_match", moveFilterText.trim())
                    : Component.translatable("feurinfo.no_moves");
            graphics.drawCenteredString(font, empty, rightX + rightW / 2, listTop + 12, 0xFFB0B8C0);
            return;
        }

        int visible = visibleRows();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        int shown = Math.min(visible, list.size() - scroll);
        Row hovered = null;

        graphics.enableScissor(rightX + 1, listTop, rightX + rightW - 1, listBottom);

        // 1er passage : les fonds (lignes + cases de type)
        for (int i = 0; i < shown; i++) {
            Row row = list.get(scroll + i);
            int y = listTop + i * ROW_H;
            boolean hover = mouseX >= x0 - 2 && mouseX < x1 + 2 && mouseY >= y && mouseY < y + ROW_H && mouseY < listBottom;
            if (hover) {
                hovered = row;
                graphics.fill(x0 - 2, y, x1 + 2, y + ROW_H, 0x40FFFFFF);
            } else if ((scroll + i) % 2 == 1) {
                graphics.fill(x0 - 2, y, x1 + 2, y + ROW_H, 0x14FFFFFF);
            }
        }
        // On dessine les fonds maintenant, pour que les icônes et le texte passent par-dessus
        graphics.flush();

        // Icônes de type (celles de Cobblemon)
        int iconX = typeX + (TYPE_COL_W - TYPE_ICON) / 2;
        for (int i = 0; i < shown; i++) {
            int y = listTop + i * ROW_H;
            drawTypeIcon(graphics, list.get(scroll + i).move().getElementalType(), iconX, y + (ROW_H - TYPE_ICON) / 2, TYPE_ICON);
        }

        // 2e passage : les textes
        for (int i = 0; i < shown; i++) {
            Row row = list.get(scroll + i);
            MoveTemplate move = row.move();
            int y = listTop + i * ROW_H;
            int textY = y + (ROW_H - 8) / 2;
            if (showLevel) {
                graphics.drawString(font, row.level(), x0, textY, 0xFFFFD866, false);
            }
            String name = move.getDisplayName().getString();
            int maxNameW = typeX - nameX - 4;
            if (font.width(name) > maxNameW) {
                name = font.plainSubstrByWidth(name, Math.max(0, maxNameW - font.width("…"))) + "…";
            }
            graphics.drawString(font, name, nameX, textY, 0xFFFFFFFF, false);
            if (showCategory) {
                DamageCategory category = move.getDamageCategory();
                graphics.drawString(font, category.getDisplayName(), catX, textY, categoryColor(category), false);
            }
            double power = move.getPower();
            double accuracy = move.getAccuracy();
            drawRight(graphics, Component.literal(power > 0 ? String.valueOf((int) power) : "—"), powRight, textY, 0xFFE0E0E0);
            drawRight(graphics, Component.literal(accuracy > 0 && accuracy <= 100 ? (int) accuracy + "%" : "—"), accRight, textY, 0xFFE0E0E0);
            drawRight(graphics, Component.literal(String.valueOf(move.getPp())), ppRight, textY, 0xFFE0E0E0);
        }
        graphics.disableScissor();

        // Barre de défilement
        if (list.size() > visible) {
            int trackH = listBottom - listTop;
            int barH = Math.max(10, trackH * visible / list.size());
            int barY = listTop + (trackH - barH) * scroll / Math.max(1, maxScroll());
            graphics.fill(x1 + 4, listTop, x1 + 6, listBottom, 0x30FFFFFF);
            graphics.fill(x1 + 4, barY, x1 + 6, barY + barH, 0xC0FFFFFF);
        }

        if (hovered != null) {
            MoveTemplate move = hovered.move();
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(move.getDisplayName().copy().withStyle(ChatFormatting.BOLD).getVisualOrderText());
            int typeColor = move.getElementalType().getHue() & 0xFFFFFF;
            lines.add(Component.translatable("feurinfo.tooltip.type", move.getElementalType().getDisplayName())
                    .withStyle(style -> style.withColor(typeColor)).getVisualOrderText());
            if (!showCategory) {
                int color = categoryColor(move.getDamageCategory()) & 0xFFFFFF;
                lines.add(move.getDamageCategory().getDisplayName().copy()
                        .withStyle(style -> style.withColor(color)).getVisualOrderText());
            }
            lines.addAll(font.split(move.getDescription().copy().withStyle(ChatFormatting.GRAY), 220));
            if (move.getPriority() != 0) {
                String priority = (move.getPriority() > 0 ? "+" : "") + move.getPriority();
                lines.add(Component.translatable("feurinfo.tooltip.priority", priority).withStyle(ChatFormatting.DARK_AQUA).getVisualOrderText());
            }
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    private void drawRight(GuiGraphics graphics, Component text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }

    /** Icône ronde du type, découpée dans la planche de Cobblemon (index = textureXMultiplier). */
    private static void drawTypeIcon(GuiGraphics graphics, ElementalType type, int x, int y, int size) {
        int u = type.getTextureXMultiplier() * TYPE_ICON_SRC;
        RenderSystem.enableBlend();
        graphics.blit(TYPE_ICONS, x, y, size, size, u, 0, TYPE_ICON_SRC, TYPE_ICON_SRC, TYPE_ICON_SHEET_W, TYPE_ICON_SRC);
        RenderSystem.disableBlend();
    }

    private static int categoryColor(DamageCategory category) {
        return switch (category.getName().toLowerCase()) {
            case "physical" -> 0xFFF08A4B;
            case "special" -> 0xFF7FA6FF;
            default -> 0xFFB8B8B8;
        };
    }
}
