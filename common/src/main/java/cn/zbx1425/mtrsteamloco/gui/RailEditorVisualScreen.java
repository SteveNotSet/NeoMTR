package cn.zbx1425.mtrsteamloco.gui;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.data.*;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateHoldingItem;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateRail;
import cn.zbx1425.mtrsteamloco.render.RailPicker;
import cn.zbx1425.mtrsteamloco.render.rail.RailRenderDispatcher;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.client.IDrawing;
import mtr.data.Rail;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.screen.WidgetBetterTextField;
import net.minecraft.client.Minecraft;
#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RailEditorVisualScreen extends SelectListScreen {

    private boolean isSelectingModel = false;

    private static Rail pickedRail = null;
    private static BlockPos pickedPosStart = BlockPos.ZERO;
    private static BlockPos pickedPosEnd = BlockPos.ZERO;

    private static String lastEditedModelKey = "";

    private static BlockPos lastTerminalNode = null;
    private static float lastExitOffset = 0;
    private static String lastPropagateModelKey = "";
    private static float lastPropagateInterval = 0;
    private static boolean lastPropagateReversed = false;

    private int selectedLayerIndex = 0;

    private final WidgetScrollList layerScrollList = new WidgetScrollList(0, 0, 100, 100);

    public RailEditorVisualScreen() {
        super(Text.translatable("gui.mtr.rail_editor_visual.title"));
        if (pickedRail == null) acquirePickInfoWhenUse();
        autoSelectLayer();
    }

    @Override
    protected void init() {
        super.init();
        loadPage();
    }

    @Override
    protected void loadPage() {
        clearWidgets();

        if (isSelectingModel) {
            String currentModelKey = "";
            RailModelRepeater sel = getSelectedRepeater();
            if (sel != null) currentModelKey = sel.modelKey;
            String finalKey = currentModelKey;
            scrollList.visible = true;
            loadSelectPage(key -> !key.equals(finalKey));
            return;
        }

        scrollList.visible = false;
        loadEditorPage();
    }

    private List<RailModelRepeater> getRepeaters() {
        if (pickedRail == null) return new ArrayList<>();
        return ((RailExtraSupplier) pickedRail).getRepeaters();
    }

    private void autoSelectLayer() {
        List<RailModelRepeater> repeaters = getRepeaters();
        if (repeaters.isEmpty()) return;
        if (lastEditedModelKey.isEmpty()) {
            selectedLayerIndex = repeaters.size() - 1;
            return;
        }
        int lastMatch = -1;
        for (int i = 0; i < repeaters.size(); i++) {
            if (repeaters.get(i).modelKey.equals(lastEditedModelKey)) {
                lastMatch = i;
            }
        }
        selectedLayerIndex = lastMatch >= 0 ? lastMatch : repeaters.size() - 1;
    }

    private boolean userIsAtCanonStart() {
        return pickedPosStart.asLong() <= pickedPosEnd.asLong();
    }

    private RailModelRepeater getSelectedRepeater() {
        List<RailModelRepeater> repeaters = getRepeaters();
        if (selectedLayerIndex >= 0 && selectedLayerIndex < repeaters.size()) {
            return repeaters.get(selectedLayerIndex);
        }
        return null;
    }

    private void loadEditorPage() {
        List<RailModelRepeater> repeaters = getRepeaters();

        int leftPanelWidth = Mth.clamp(width / 3, 140, 220);
        int rightPanelWidth = Math.min(width - (leftPanelWidth + SQUARE_SIZE) - SQUARE_SIZE, 380);
        int rightPanelX = leftPanelWidth + (width - leftPanelWidth - rightPanelWidth) / 2;

        layerScrollList.children.clear();
        IDrawing.setPositionAndWidth(layerScrollList, 0, SQUARE_SIZE, leftPanelWidth);
        layerScrollList.setHeight(height - SQUARE_SIZE * 3);

        for (int i = 0; i < repeaters.size(); i++) {
            RailModelRepeater p = repeaters.get(i);
            String modelLabel = p.modelKey.isEmpty() ? "(default)" : p.modelKey;
            RailModelProperties props = RailModelRegistry.elements.get(p.modelKey);
            if (props != null && !props.name.getString().isEmpty()) {
                modelLabel = props.name.getString();
            }
            String modeLabel = switch (p.repeaterMode) {
                case STRETCH_INTERVAL -> "S";
                case FIXED_INTERVAL -> "F";
                case MANUAL -> "M";
            };
            String btnText = String.format("%s [%s]", modelLabel, modeLabel);

            final int layerIdx = i;
            int btnWidth = leftPanelWidth - SQUARE_SIZE;

            Button layerBtn = UtilitiesClient.newButton(
                    Text.literal(btnText),
                    sender -> {
                        selectedLayerIndex = layerIdx;
                        Minecraft.getInstance().tell(this::loadPage);
                    }
            );
            layerBtn.active = (i != selectedLayerIndex);
            IDrawing.setPositionAndWidth(layerBtn, 0, i * SQUARE_SIZE, btnWidth);
            layerScrollList.children.add(layerBtn);

            Button deleteBtn = UtilitiesClient.newButton(
                    Text.literal("x"),
                    sender -> {
                        getRepeaters().remove(layerIdx);
                        if (selectedLayerIndex >= getRepeaters().size()) {
                            selectedLayerIndex = Math.max(0, getRepeaters().size() - 1);
                        }
                        sendUpdate();
                        Minecraft.getInstance().tell(this::loadPage);
                    }
            );
            IDrawing.setPositionAndWidth(deleteBtn, btnWidth, i * SQUARE_SIZE, SQUARE_SIZE);
            layerScrollList.children.add(deleteBtn);
        }

        addRenderableWidget(layerScrollList);

        IDrawing.setPositionAndWidth(addRenderableWidget(UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.add_layer"),
                sender -> {
                    getRepeaters().add(new RailModelRepeater());
                    selectedLayerIndex = getRepeaters().size() - 1;
                    sendUpdate();
                    Minecraft.getInstance().tell(this::loadPage);
                }
        )), 0, height - SQUARE_SIZE * 2, leftPanelWidth);

        RailModelRepeater selected = getSelectedRepeater();
        if (selected == null) {
            addRenderableWidget(new WidgetLabel(rightPanelX, SQUARE_SIZE * 2, rightPanelWidth,
                    Text.translatable("gui.mtr.rail_editor_visual.no_layers")));
            return;
        }

        int x = rightPanelX;
        int w = rightPanelWidth;
        int halfW = w / 2 - 2;
        int y = SQUARE_SIZE;

        // -- Model selector --
        String modelLabel = selected.modelKey.isEmpty() ? "(default)" : selected.modelKey;
        RailModelProperties props = RailModelRegistry.elements.get(selected.modelKey);
        if (props != null && !props.name.getString().isEmpty()) {
            modelLabel = props.name.getString();
        }
        IDrawing.setPositionAndWidth(addRenderableWidget(UtilitiesClient.newButton(
                Text.literal(modelLabel),
                sender -> {
                    isSelectingModel = true;
                    Minecraft.getInstance().tell(this::loadPage);
                }
        )), x, y, w);
        y += SQUARE_SIZE + 4;

        // -- Common: Direction (reversed, relative to isSecondaryDir) --
        addRenderableWidget(new WidgetLabel(x, y + 6, w,
                Text.translatable("gui.mtr.rail_editor_visual.direction")));
        y += SQUARE_SIZE - 4;

        Button btnNormal = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.facing_away"),
                sender -> { selected.reversed = false; sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnNormal.active = selected.reversed;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnNormal), x, y, halfW);

        Button btnReversed = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.facing_here"),
                sender -> { selected.reversed = true; sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnReversed.active = !selected.reversed;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnReversed), x + halfW + 4, y, halfW);
        y += SQUARE_SIZE + 4;

        // -- Common: Interval override --
        addRenderableWidget(new WidgetLabel(x, y + 6, w,
                Text.translatable("gui.mtr.rail_editor_visual.interval")));
        y += SQUARE_SIZE - 4;

        WidgetBetterTextField intervalField = new WidgetBetterTextField("0", 8);
        intervalField.setValue(selected.intervalOverride > 0 ? String.format("%.2f", selected.intervalOverride) : "");
        intervalField.setResponder(text -> {
            try {
                selected.intervalOverride = text.isEmpty() ? 0 : Float.parseFloat(text);
                intervalField.setTextColor(0xE0E0E0);
                sendUpdate();
            } catch (NumberFormatException e) {
                intervalField.setTextColor(0xFF0000);
            }
        });
        IDrawing.setPositionAndWidth(addRenderableWidget(intervalField), x, y, halfW);
        intervalField.active = selected.repeaterMode != RepeaterMode.MANUAL;
        y += SQUARE_SIZE + 4;

        y += 6;

        // -- Mode selector --
        int modeButtonWidth = w / 3;
        Button btnStretch = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.stretch_interval"),
                sender -> { selected.repeaterMode = RepeaterMode.STRETCH_INTERVAL; sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnStretch.active = selected.repeaterMode != RepeaterMode.STRETCH_INTERVAL;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnStretch), x, y, modeButtonWidth);

        Button btnFixed = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.fixed_interval"),
                sender -> {
                    if (selected.repeaterMode != RepeaterMode.FIXED_INTERVAL) {
                        selected.offsetFromStart = userIsAtCanonStart();
                    }
                    selected.repeaterMode = RepeaterMode.FIXED_INTERVAL;
                    sendUpdate();
                    Minecraft.getInstance().tell(this::loadPage);
                }
        );
        btnFixed.active = selected.repeaterMode != RepeaterMode.FIXED_INTERVAL;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnFixed), x + modeButtonWidth, y, modeButtonWidth);

        Button btnManual = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.manual"),
                sender -> {
                    if (selected.repeaterMode != RepeaterMode.MANUAL) {
                        selected.manualPositions = computePositionsForCurrentMode(selected);
                    }
                    selected.repeaterMode = RepeaterMode.MANUAL;
                    sendUpdate();
                    Minecraft.getInstance().tell(this::loadPage);
                }
        );
        btnManual.active = selected.repeaterMode != RepeaterMode.MANUAL;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnManual), x + modeButtonWidth * 2, y, w - modeButtonWidth * 2);
        y += SQUARE_SIZE + 4;

        // -- Mode-specific panel --
        switch (selected.repeaterMode) {
            case STRETCH_INTERVAL -> {} // all options are in common section
            case FIXED_INTERVAL -> loadFixedPanel(selected, x, y, w);
            case MANUAL -> loadManualPanel(selected, x, y, w);
        }
    }

    private void loadFixedPanel(RailModelRepeater repeater, int x, int y, int w) {
        boolean fromThisNode = repeater.offsetFromStart == userIsAtCanonStart();
        int halfW = w / 2 - 2;

        addRenderableWidget(new WidgetLabel(x, y + 6, w,
                Text.translatable("gui.mtr.rail_editor_visual.offset_direction")));
        y += SQUARE_SIZE - 4;

        Button btnFromThis = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.from_this_node"),
                sender -> { repeater.offsetFromStart = userIsAtCanonStart(); sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnFromThis.active = !fromThisNode;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnFromThis), x, y, halfW);

        Button btnFromOther = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.from_other_node"),
                sender -> { repeater.offsetFromStart = !userIsAtCanonStart(); sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnFromOther.active = fromThisNode;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnFromOther), x + halfW + 4, y, halfW);
        y += SQUARE_SIZE + 2;

        addRenderableWidget(new WidgetLabel(x, y + 6, w,
                Text.translatable("gui.mtr.rail_editor_visual.offset")));
        y += SQUARE_SIZE - 4;

        WidgetBetterTextField offsetField = new WidgetBetterTextField("0", 8);
        offsetField.setValue(String.format("%.3f", repeater.offset));
        offsetField.setResponder(text -> {
            try {
                repeater.offset = text.isEmpty() ? 0 : Float.parseFloat(text);
                offsetField.setTextColor(0xE0E0E0);
                sendUpdate();
            } catch (NumberFormatException e) {
                offsetField.setTextColor(0xFF0000);
            }
        });
        IDrawing.setPositionAndWidth(addRenderableWidget(offsetField), x, y, halfW);
        y += SQUARE_SIZE + 4;

        int btnW = w / 3 - 2;
        IDrawing.setPositionAndWidth(addRenderableWidget(UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.propagate"),
                sender -> sendPropagate(repeater, false)
        )), x, y, btnW);

        IDrawing.setPositionAndWidth(addRenderableWidget(UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.undo_propagate"),
                sender -> sendPropagate(repeater, true)
        )), x + btnW + 2, y, btnW);

        boolean canContinue = lastTerminalNode != null
                && lastTerminalNode.equals(pickedPosStart)
                && repeater.modelKey.equals(lastPropagateModelKey);
        Button btnContinue = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.continue_propagate"),
                sender -> {
                    repeater.offset = lastExitOffset;
                    repeater.offsetFromStart = userIsAtCanonStart();
                    repeater.intervalOverride = lastPropagateInterval;
                    sendUpdate();
                    Minecraft.getInstance().tell(() -> sendPropagate(repeater, false));
                }
        );
        btnContinue.active = canContinue;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnContinue), x + btnW * 2 + 4, y, btnW);
    }

    private WidgetManualPositionBar currentBar;
    private WidgetBetterTextField positionInputField;

    private void loadManualPanel(RailModelRepeater repeater, int x, int y, int w) {
        float railLength = pickedRail != null ? (float) pickedRail.getLength() : 100f;
        boolean flipForDisplay = !userIsAtCanonStart();

        addRenderableWidget(new WidgetLabel(x, y + 6, w,
                Text.literal(String.format("%d pts | Scroll to zoom", repeater.manualPositions.size()))));
        y += SQUARE_SIZE + 4;

        currentBar = new WidgetManualPositionBar(x, y, w, positions -> {
            if (flipForDisplay) {
                List<Float> canonPositions = new ArrayList<>(positions.size());
                for (float pos : positions) {
                    canonPositions.add(quantizePosition(railLength - pos));
                }
                Collections.reverse(canonPositions);
                repeater.manualPositions = canonPositions;
            } else {
                repeater.manualPositions = new ArrayList<>(positions);
            }
            sendUpdate();
        });
        currentBar.setRailLength(railLength);

        List<Float> displayPositions;
        if (flipForDisplay) {
            displayPositions = new ArrayList<>(repeater.manualPositions.size());
            for (float pos : repeater.manualPositions) {
                displayPositions.add(railLength - pos);
            }
            Collections.reverse(displayPositions);
        } else {
            displayPositions = new ArrayList<>(repeater.manualPositions);
        }
        currentBar.setPositions(displayPositions);
        currentBar.setOnSelectionChange(() -> updatePositionInputField(repeater));
        addRenderableWidget(currentBar);
        y += currentBar.getHeight() + 4;

        int halfW = w / 2 - 2;
        addRenderableWidget(new WidgetLabel(x, y + 6, halfW,
                Text.translatable("gui.mtr.rail_editor_visual.selected_offset")));
        positionInputField = new WidgetBetterTextField("", 10);
        IDrawing.setPositionAndWidth(addRenderableWidget(positionInputField), x + halfW + 4, y, halfW);
        updatePositionInputField(repeater);
        positionInputField.setResponder(text -> {
            if (currentBar == null) return;
            int sel = currentBar.getSelectedIndex();
            if (sel < 0) return;
            try {
                float val = Float.parseFloat(text);
                val = quantizePosition(val);
                currentBar.setPositionAt(sel, val);
                positionInputField.setTextColor(0xE0E0E0);
            } catch (NumberFormatException e) {
                positionInputField.setTextColor(0xFF0000);
            }
        });
    }

    private void updatePositionInputField(RailModelRepeater repeater) {
        if (positionInputField == null || currentBar == null) return;
        int sel = currentBar.getSelectedIndex();
        if (sel >= 0 && sel < repeater.manualPositions.size()) {
            positionInputField.active = true;
            positionInputField.setValue(String.format("%.3f", currentBar.getSelectedPosition()));
            positionInputField.setTextColor(0xE0E0E0);
        } else {
            positionInputField.active = false;
            positionInputField.setValue("");
        }
    }

    private static float quantizePosition(float value) {
        return Math.round(value * 1000f) / 1000f;
    }

    private float resolveInterval(RailModelRepeater p) {
        if (p.intervalOverride > 0) return p.intervalOverride;
        String resolvedKey = pickedRail != null
                ? RailRenderDispatcher.getModelKeyForRender(pickedRail, p.modelKey)
                : p.modelKey;
        RailModelProperties props = RailModelRegistry.elements.get(resolvedKey);
        if (props != null && props.repeatInterval > 0) return props.repeatInterval;
        props = RailModelRegistry.elements.get(p.modelKey);
        if (props != null && props.repeatInterval > 0) return props.repeatInterval;
        return 1.0f;
    }

    private List<Float> computePositionsForCurrentMode(RailModelRepeater p) {
        double L = pickedRail != null ? pickedRail.getLength() : 100.0;
        double I = resolveInterval(p);

        List<Float> result = new ArrayList<>();
        switch (p.repeaterMode) {
            case STRETCH_INTERVAL: {
                if (L < I * 0.5) {
                    result.add(quantizePosition((float) (L / 2)));
                    break;
                }
                int N = Math.max(2, Math.round((float) (L / I)) + 1);
                double actualI = L / (N - 1);
                for (int k = 0; k < N; k++) {
                    result.add(quantizePosition((float) (k * actualI)));
                }
                break;
            }
            case FIXED_INTERVAL: {
                if (p.offsetFromStart) {
                    for (double t = p.offset; t < L - 0.001; t += I) {
                        result.add(quantizePosition((float) t));
                    }
                } else {
                    List<Float> tmp = new ArrayList<>();
                    for (double t = L - p.offset; t > 0.001; t -= I) {
                        tmp.add(quantizePosition((float) t));
                    }
                    Collections.reverse(tmp);
                    result.addAll(tmp);
                }
                break;
            }
            default:
                result.addAll(p.manualPositions);
                break;
        }
        return result;
    }

    private void sendPropagate(RailModelRepeater repeater, boolean undo) {
        final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        if (undo) {
            packet.writeByte(2);
        } else {
            packet.writeByte(0);
            packet.writeBlockPos(pickedPosStart);
            packet.writeBlockPos(pickedPosEnd);
            packet.writeVarInt(selectedLayerIndex);
            packet.writeUtf(repeater.modelKey);
            packet.writeFloat(resolveInterval(repeater));
            packet.writeBoolean(repeater.reversed);
            packet.writeFloat(repeater.offset);
        }
        RegistryClient.sendToServer(IPacket.PACKET_PROPAGATE_REPEATER_OFFSET, packet);
        onClose();
    }

    @Override
    protected void onBtnClick(String btnKey) {
        RailModelRepeater sel = getSelectedRepeater();
        if (sel != null) {
            sel.modelKey = btnKey;
            sendUpdate();
        }
    }

    @Override
    protected List<Pair<String, String>> getRegistryEntries() {
        return RailModelRegistry.elements.entrySet().stream()
                .filter(e -> !e.getValue().name.getString().isEmpty())
                .map(e -> new Pair<>(e.getKey(), e.getValue().name.getString()))
                .toList();
    }

    private void sendUpdate() {
        if (pickedRail == null) return;
        PacketUpdateRail.sendUpdateC2S(pickedRail, pickedPosStart, pickedPosEnd);
        saveToToolNbt();
    }

    private void saveToToolNbt() {
        if (Minecraft.getInstance().player == null) return;
        ItemStack toolItem = Minecraft.getInstance().player.getMainHandItem();
        if (!toolItem.is(Main.RAIL_EDITOR_VISUAL.get())) return;
        CompoundTag tag = toolItem.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        writeRepeatersToNbt(tag, getRepeaters());
        toolItem.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        PacketUpdateHoldingItem.sendUpdateC2S();
    }

    public static void receivePropagationResult(FriendlyByteBuf packet) {
        lastTerminalNode = packet.readBlockPos();
        lastExitOffset = packet.readFloat();
        lastPropagateModelKey = packet.readUtf();
        lastPropagateInterval = packet.readFloat();
        lastPropagateReversed = packet.readBoolean();
    }

    public static void acquirePickInfoWhenUse() {
        pickedRail = RailPicker.pickedRail;
        pickedPosStart = RailPicker.pickedPosStart;
        pickedPosEnd = RailPicker.pickedPosEnd;
    }

    public static void batchApplyBrushTemplate(CompoundTag toolTag) {
        if (toolTag == null || pickedRail == null) return;
        RailExtraSupplier extra = (RailExtraSupplier) pickedRail;

        List<RailModelRepeater> template = readRepeatersFromNbt(toolTag);
        if (template.isEmpty()) {
            extra.setIsSecondaryDir(!extra.getIsSecondaryDir());
        } else {
            List<RailModelRepeater> current = extra.getRepeaters();
            boolean allMatch = templateMatchesCurrent(current, template);
            if (allMatch) {
                extra.setIsSecondaryDir(!extra.getIsSecondaryDir());
            } else {
                for (RailModelRepeater tp : template) {
                    RailModelRepeater existing = findByModelKey(current, tp.modelKey);
                    if (existing != null) {
                        if (tp.repeaterMode == RepeaterMode.MANUAL) continue;
                        existing.repeaterMode = tp.repeaterMode;
                        existing.reversed = tp.reversed;
                        existing.intervalOverride = tp.intervalOverride;
                    } else {
                        RailModelRepeater newP = new RailModelRepeater();
                        newP.modelKey = tp.modelKey;
                        newP.reversed = tp.reversed;
                        newP.intervalOverride = tp.intervalOverride;
                        newP.repeaterMode = tp.repeaterMode == RepeaterMode.MANUAL
                                ? RepeaterMode.STRETCH_INTERVAL : tp.repeaterMode;
                        current.add(newP);
                    }
                }
            }
        }
        PacketUpdateRail.sendUpdateC2S(pickedRail, pickedPosStart, pickedPosEnd);
    }

    private static RailModelRepeater findByModelKey(List<RailModelRepeater> list, String modelKey) {
        for (RailModelRepeater p : list) {
            if (p.modelKey.equals(modelKey)) return p;
        }
        return null;
    }

    private static boolean templateMatchesCurrent(List<RailModelRepeater> current, List<RailModelRepeater> template) {
        for (RailModelRepeater tp : template) {
            RailModelRepeater cp = findByModelKey(current, tp.modelKey);
            if (cp == null) return false;
            if (cp.repeaterMode != tp.repeaterMode
                    || cp.intervalOverride != tp.intervalOverride) return false;
        }
        return true;
    }

    static void writeRepeatersToNbt(CompoundTag tag, List<RailModelRepeater> repeaters) {
        tag.putInt("RepeaterCount", repeaters.size());
        for (int i = 0; i < repeaters.size(); i++) {
            RailModelRepeater p = repeaters.get(i);
            CompoundTag layerTag = new CompoundTag();
            layerTag.putString("ModelKey", p.modelKey);
            layerTag.putInt("Mode", p.repeaterMode.ordinal());
            layerTag.putBoolean("Reversed", p.reversed);
            layerTag.putFloat("IntervalOverride", p.intervalOverride);
            tag.put("Repeater_" + i, layerTag);
        }
    }

    static List<RailModelRepeater> readRepeatersFromNbt(CompoundTag tag) {
        int count = tag.getInt("RepeaterCount");
        List<RailModelRepeater> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CompoundTag layerTag = tag.getCompound("Repeater_" + i);
            if (layerTag.isEmpty()) continue;
            RailModelRepeater p = new RailModelRepeater();
            p.modelKey = layerTag.getString("ModelKey");
            p.repeaterMode = RepeaterMode.fromIndex(layerTag.getInt("Mode"));
            p.reversed = layerTag.getBoolean("Reversed");
            p.intervalOverride = layerTag.getFloat("IntervalOverride");
            result.add(p);
        }
        return result;
    }

    @Override
#if MC_VERSION >= "12000"
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
#else
    public void render(PoseStack guiGraphics, int mouseX, int mouseY, float partialTick) {
#endif
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (isSelectingModel) {
            renderSelectPage(guiGraphics);
        }
    }

    @Override
    public void onClose() {
        if (isSelectingModel) {
            isSelectingModel = false;
            Minecraft.getInstance().tell(this::loadPage);
        } else {
            RailModelRepeater sel = getSelectedRepeater();
            if (sel != null) lastEditedModelKey = sel.modelKey;
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
