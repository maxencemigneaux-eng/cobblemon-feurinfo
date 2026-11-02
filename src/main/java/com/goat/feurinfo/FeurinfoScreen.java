package com.goat.feurinfo;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.categories.DamageCategory;
import com.cobblemon.mod.common.api.pokemon.moves.Learnset;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
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
 *  - à gauche : le Pokémon en 3D (Glisser pour tourner, clic = cri), sa forme (< >) et un bouton shiny ;
 *  - à droite : ses attaques par onglet (Niveau, CT, Œufs, Tuteur, Évolution, Autres),
 *    survol d'une attaque = sa description.
 */
public final class FeurinfoScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int TOP = 30;
    private static final int ROW_H = 12;

    private enum Tab {
        LEVEL("level"), TM("tm"), EGG("egg"), TUTOR("tutor"), EVOLUTION("evolution"), OTHER("other");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private record Row(String level, MoveTemplate move) {
    }

    // --- État (conservé quand l'écran est reconstruit, ex. redimensionnement) ---
    private Species species;
    private final List<FormData> forms = new ArrayList<>();
    private int formIndex;
    private boolean shiny;
    private Tab tab = Tab.LEVEL;
    private final Map<Tab, List<Row>> rows = new EnumMap<>(Tab.class);
    private int scroll;
    private String searchText = "";
    private Component searchError;
    private float rotation = 35F;
    private boolean draggingModel;
    private long lastFrameMs;

    // --- Widgets ---
    private EditBox search;
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
        modelH = Math.max(60, panelH - 4 - 52);
        listTop = panelY + 36;
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

        int rowY = modelY + modelH + 4;
        if (forms.size() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> changeForm(-1))
                    .bounds(leftX + 4, rowY, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> changeForm(1))
                    .bounds(leftX + leftW - 24, rowY, 20, 20).build());
        }
        shinyButton = addRenderableWidget(Button.builder(shinyLabel(), b -> toggleShiny())
                .bounds(leftX + 4, rowY + 24, leftW - 8, 20).build());

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
    }

    private ModelWidget createModel() {
        Set<String> aspects = new HashSet<>(form().getAspects());
        if (shiny) {
            aspects.add("shiny");
        }
        RenderablePokemon renderable = new RenderablePokemon(species, aspects, ItemStack.EMPTY);
        // Cobblemon utilise une échelle de 2.7 et un décalage de -12 pour une zone de 100 px de haut
        float ratio = modelH / 100F;
        return new ModelWidget(modelX, modelY, modelW, modelH, renderable,
                2.7F * ratio, rotation, -12.0 * ratio, true, false, 13);
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
                ? Component.translatable("feurinfo.shiny.on").withStyle(ChatFormatting.GOLD)
                : Component.translatable("feurinfo.shiny.off");
    }

    // =======================================================================================
    // Actions
    // =======================================================================================

    private void changeForm(int delta) {
        formIndex = Math.floorMod(formIndex + delta, forms.size());
        Tab previous = tab;
        computeRows();
        if (rows.get(previous).isEmpty() || visibleTabs().size() != tabButtons.size() || !tabButtons.containsKey(tab)) {
            rebuildWidgets(); // la liste d'onglets a changé
        } else {
            replaceModel();
            updateTabButtons();
        }
    }

    private void toggleShiny() {
        shiny = !shiny;
        shinyButton.setMessage(shinyLabel());
        replaceModel();
    }

    private void selectTab(Tab t) {
        tab = t;
        scroll = 0;
        updateTabButtons();
    }

    private void updateTabButtons() {
        tabButtons.forEach((t, button) -> button.active = t != tab);
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
        return Math.max(0, rows.get(tab).size() - visibleRows());
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
        int rowY = modelY + modelH + 4;
        String formLabel = formName(form(), formIndex == 0);
        int maxFormW = leftW - 56;
        if (font.width(formLabel) > maxFormW) {
            formLabel = font.plainSubstrByWidth(formLabel, maxFormW - font.width("…")) + "…";
        }
        graphics.drawCenteredString(font, formLabel, leftX + leftW / 2, rowY + 6, 0xFFFFFFFF);

        drawMoves(graphics, mouseX, mouseY);
    }

    private void drawMoves(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Row> list = rows.get(tab);
        int x0 = rightX + 6;
        int x1 = rightX + rightW - 8;
        boolean showLevel = tab == Tab.LEVEL;
        boolean showCategory = rightW >= 330;

        // Colonnes, de droite à gauche
        int ppRight = x1;
        int accRight = ppRight - 24;
        int powRight = accRight - 34;
        int catX = powRight - 34 - 58;
        int typeX = (showCategory ? catX : powRight - 34) - 56;
        int nameX = x0 + (showLevel ? 26 : 0);

        int headerY = panelY + 25;
        int gray = 0xFF9AA4AE;
        if (showLevel) {
            graphics.drawString(font, Component.translatable("feurinfo.col.level"), x0, headerY, gray, false);
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
            graphics.drawCenteredString(font, Component.translatable("feurinfo.no_moves"),
                    rightX + rightW / 2, listTop + 12, 0xFFB0B8C0);
            return;
        }

        int visible = visibleRows();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        Row hovered = null;

        graphics.enableScissor(rightX + 1, listTop, rightX + rightW - 1, listBottom);
        for (int i = 0; i < visible && scroll + i < list.size(); i++) {
            Row row = list.get(scroll + i);
            MoveTemplate move = row.move();
            int y = listTop + i * ROW_H;
            boolean hover = mouseX >= x0 - 2 && mouseX < x1 + 2 && mouseY >= y && mouseY < y + ROW_H && mouseY < listBottom;
            if (hover) {
                hovered = row;
                graphics.fill(x0 - 2, y, x1 + 2, y + ROW_H, 0x40FFFFFF);
            } else if ((scroll + i) % 2 == 1) {
                graphics.fill(x0 - 2, y, x1 + 2, y + ROW_H, 0x14FFFFFF);
            }
            int textY = y + 2;
            if (showLevel) {
                graphics.drawString(font, row.level(), x0, textY, 0xFFFFD866, false);
            }
            String name = move.getDisplayName().getString();
            int maxNameW = typeX - nameX - 4;
            if (font.width(name) > maxNameW) {
                name = font.plainSubstrByWidth(name, Math.max(0, maxNameW - font.width("…"))) + "…";
            }
            graphics.drawString(font, name, nameX, textY, 0xFFFFFFFF, false);
            drawType(graphics, move.getElementalType(), typeX, y + 1, 52);
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

    private void drawType(GuiGraphics graphics, ElementalType type, int x, int y, int w) {
        int rgb = type.getHue() & 0xFFFFFF;
        graphics.fill(x, y, x + w, y + ROW_H - 2, 0xFF000000 | rgb);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;
        int textColor = luminance > 150 ? 0xFF202020 : 0xFFFFFFFF;
        Component name = type.getDisplayName();
        graphics.drawString(font, name, x + (w - font.width(name)) / 2, y + 1, textColor, false);
    }

    private static int categoryColor(DamageCategory category) {
        return switch (category.getName().toLowerCase()) {
            case "physical" -> 0xFFF08A4B;
            case "special" -> 0xFF7FA6FF;
            default -> 0xFFB8B8B8;
        };
    }
}
