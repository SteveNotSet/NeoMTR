package cn.zbx1425.mtrsteamloco.gui;

import cn.zbx1425.mtrsteamloco.data.*;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateHoldingItem;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateRail;
import cn.zbx1425.mtrsteamloco.render.RailPicker;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.client.IDrawing;
import mtr.data.Rail;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.screen.WidgetBetterCheckbox;
import mtr.screen.WidgetBetterTextField;
import net.minecraft.client.Minecraft;
#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlacementEditorScreen extends SelectListScreen {

    private boolean isSelectingModel = false;

    private static Rail pickedRail = null;
    private static BlockPos pickedPosStart = BlockPos.ZERO;
    private static BlockPos pickedPosEnd = BlockPos.ZERO;

    private int selectedLayerIndex = 0;

    private final WidgetScrollList layerScrollList = new WidgetScrollList(0, 0, 100, 100);

    public PlacementEditorScreen() {
        super(Text.translatable("gui.mtr.placement_editor_title"));
        if (pickedRail == null) acquirePickInfoWhenUse();
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
            RailModelPlacement sel = getSelectedPlacement();
            if (sel != null) currentModelKey = sel.modelKey;
            String finalKey = currentModelKey;
            scrollList.visible = true;
            loadSelectPage(key -> !key.equals(finalKey));
            return;
        }

        scrollList.visible = false;
        loadEditorPage();
    }

    private List<RailModelPlacement> getPlacements() {
        if (pickedRail == null) return Collections.emptyList();
        return ((RailExtraSupplier) pickedRail).getModelPlacements();
    }

    private RailModelPlacement getSelectedPlacement() {
        List<RailModelPlacement> placements = getPlacements();
        if (selectedLayerIndex >= 0 && selectedLayerIndex < placements.size()) {
            return placements.get(selectedLayerIndex);
        }
        return null;
    }

    private void loadEditorPage() {
        List<RailModelPlacement> placements = getPlacements();

        int leftPanelWidth = Math.min(width / 3, 140);
        int rightPanelX = leftPanelWidth + SQUARE_SIZE;
        int rightPanelWidth = width - rightPanelX - SQUARE_SIZE;

        layerScrollList.children.clear();
        IDrawing.setPositionAndWidth(layerScrollList, 0, SQUARE_SIZE, leftPanelWidth);
        layerScrollList.setHeight(height - SQUARE_SIZE * 3);

        for (int i = 0; i < placements.size(); i++) {
            RailModelPlacement p = placements.get(i);
            String modelLabel = p.modelKey.isEmpty() ? "(default)" : p.modelKey;
            RailModelProperties props = RailModelRegistry.elements.get(p.modelKey);
            if (props != null && !props.name.getString().isEmpty()) {
                modelLabel = props.name.getString();
            }
            String modeLabel = switch (p.placementMode) {
                case STRETCH_INTERVAL -> "S";
                case FIXED_INTERVAL -> "F";
                case MANUAL -> "M";
            };
            String btnText = String.format("[%s] %s", modeLabel, modelLabel);

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
                        getPlacements().remove(layerIdx);
                        if (selectedLayerIndex >= getPlacements().size()) {
                            selectedLayerIndex = Math.max(0, getPlacements().size() - 1);
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
                Text.translatable("gui.mtr.placement_editor_add_layer"),
                sender -> {
                    getPlacements().add(new RailModelPlacement());
                    selectedLayerIndex = getPlacements().size() - 1;
                    sendUpdate();
                    Minecraft.getInstance().tell(this::loadPage);
                }
        )), 0, height - SQUARE_SIZE * 2, leftPanelWidth);

        addRenderableWidget(new WidgetLabel(0, 2, leftPanelWidth,
                Text.translatable("gui.mtr.placement_editor_title")));

        RailModelPlacement selected = getSelectedPlacement();
        if (selected == null) {
            addRenderableWidget(new WidgetLabel(rightPanelX, SQUARE_SIZE * 2, rightPanelWidth,
                    Text.translatable("gui.mtr.placement_editor_no_layers")));
            return;
        }

        int y = SQUARE_SIZE;

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
        )), rightPanelX, y, rightPanelWidth);
        y += SQUARE_SIZE + 2;

        int modeButtonWidth = rightPanelWidth / 3;
        Button btnStretch = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.placement_editor_stretch_interval"),
                sender -> { selected.placementMode = PlacementMode.STRETCH_INTERVAL; sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnStretch.active = selected.placementMode != PlacementMode.STRETCH_INTERVAL;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnStretch), rightPanelX, y, modeButtonWidth);

        Button btnFixed = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.placement_editor_fixed_interval"),
                sender -> { selected.placementMode = PlacementMode.FIXED_INTERVAL; sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnFixed.active = selected.placementMode != PlacementMode.FIXED_INTERVAL;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnFixed), rightPanelX + modeButtonWidth, y, modeButtonWidth);

        Button btnManual = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.placement_editor_manual"),
                sender -> {
                    if (selected.placementMode != PlacementMode.MANUAL) {
                        selected.manualPositions = computePositionsForCurrentMode(selected);
                    }
                    selected.placementMode = PlacementMode.MANUAL;
                    sendUpdate();
                    Minecraft.getInstance().tell(this::loadPage);
                }
        );
        btnManual.active = selected.placementMode != PlacementMode.MANUAL;
        IDrawing.setPositionAndWidth(addRenderableWidget(btnManual), rightPanelX + modeButtonWidth * 2, y, rightPanelWidth - modeButtonWidth * 2);
        y += SQUARE_SIZE + 4;

        switch (selected.placementMode) {
            case STRETCH_INTERVAL -> loadStretchPanel(selected, rightPanelX, y, rightPanelWidth);
            case FIXED_INTERVAL -> loadFixedPanel(selected, rightPanelX, y, rightPanelWidth);
            case MANUAL -> loadManualPanel(selected, rightPanelX, y, rightPanelWidth);
        }
    }

    private void loadStretchPanel(RailModelPlacement placement, int x, int y, int w) {
        addRenderableWidget(new WidgetBetterCheckbox(x, y, w, SQUARE_SIZE,
                Text.translatable("gui.mtr.placement_editor_reversed"),
                checked -> { placement.reversed = checked; sendUpdate(); }
        )).setChecked(placement.reversed);
        y += SQUARE_SIZE + 2;

        addRenderableWidget(new WidgetLabel(x, y + 6, w,
                Text.translatable("gui.mtr.placement_editor_interval")));
        y += SQUARE_SIZE - 4;

        WidgetBetterTextField intervalField = new WidgetBetterTextField("0", 8);
        intervalField.setValue(placement.intervalOverride > 0 ? String.format("%.2f", placement.intervalOverride) : "");
        intervalField.setResponder(text -> {
            try {
                placement.intervalOverride = text.isEmpty() ? 0 : Float.parseFloat(text);
                intervalField.setTextColor(0xE0E0E0);
                sendUpdate();
            } catch (NumberFormatException e) {
                intervalField.setTextColor(0xFF0000);
            }
        });
        IDrawing.setPositionAndWidth(addRenderableWidget(intervalField), x, y, w / 2);
    }

    private void loadFixedPanel(RailModelPlacement placement, int x, int y, int w) {
        addRenderableWidget(new WidgetLabel(x, y + 6, w,
                Text.translatable("gui.mtr.placement_editor_offset")));
        y += SQUARE_SIZE - 4;

        WidgetBetterTextField offsetField = new WidgetBetterTextField("0", 8);
        offsetField.setValue(String.format("%.3f", placement.offset));
        offsetField.setResponder(text -> {
            try {
                placement.offset = text.isEmpty() ? 0 : Float.parseFloat(text);
                offsetField.setTextColor(0xE0E0E0);
                sendUpdate();
            } catch (NumberFormatException e) {
                offsetField.setTextColor(0xFF0000);
            }
        });
        IDrawing.setPositionAndWidth(addRenderableWidget(offsetField), x, y, w / 2);
        y += SQUARE_SIZE + 2;

        addRenderableWidget(new WidgetBetterCheckbox(x, y, w, SQUARE_SIZE,
                Text.translatable("gui.mtr.placement_editor_offset_from_start"),
                checked -> { placement.offsetFromStart = checked; sendUpdate(); }
        )).setChecked(placement.offsetFromStart);
        y += SQUARE_SIZE + 2;

        addRenderableWidget(new WidgetBetterCheckbox(x, y, w, SQUARE_SIZE,
                Text.translatable("gui.mtr.placement_editor_reversed"),
                checked -> { placement.reversed = checked; sendUpdate(); }
        )).setChecked(placement.reversed);
        y += SQUARE_SIZE + 2;

        addRenderableWidget(new WidgetLabel(x, y + 6, w,
                Text.translatable("gui.mtr.placement_editor_interval")));
        y += SQUARE_SIZE - 4;

        WidgetBetterTextField intervalField = new WidgetBetterTextField("0", 8);
        intervalField.setValue(placement.intervalOverride > 0 ? String.format("%.2f", placement.intervalOverride) : "");
        intervalField.setResponder(text -> {
            try {
                placement.intervalOverride = text.isEmpty() ? 0 : Float.parseFloat(text);
                intervalField.setTextColor(0xE0E0E0);
                sendUpdate();
            } catch (NumberFormatException e) {
                intervalField.setTextColor(0xFF0000);
            }
        });
        IDrawing.setPositionAndWidth(addRenderableWidget(intervalField), x, y, w / 2);
        y += SQUARE_SIZE + 4;

        int btnW = w / 2 - 2;
        IDrawing.setPositionAndWidth(addRenderableWidget(UtilitiesClient.newButton(
                Text.translatable("gui.mtr.placement_editor_propagate"),
                sender -> sendPropagate(placement, false)
        )), x, y, btnW);

        IDrawing.setPositionAndWidth(addRenderableWidget(UtilitiesClient.newButton(
                Text.translatable("gui.mtr.placement_editor_undo_propagate"),
                sender -> sendPropagate(placement, true)
        )), x + btnW + 4, y, btnW);
    }

    private WidgetManualPositionBar currentBar;
    private WidgetBetterTextField positionInputField;

    private void loadManualPanel(RailModelPlacement placement, int x, int y, int w) {
        addRenderableWidget(new WidgetBetterCheckbox(x, y, w, SQUARE_SIZE,
                Text.translatable("gui.mtr.placement_editor_reversed"),
                checked -> { placement.reversed = checked; sendUpdate(); }
        )).setChecked(placement.reversed);
        y += SQUARE_SIZE + 2;

        int halfW = w / 2 - 2;
        IDrawing.setPositionAndWidth(addRenderableWidget(UtilitiesClient.newButton(
                Text.translatable("gui.mtr.placement_editor_swap_direction"),
                sender -> {
                    float railLen = pickedRail != null ? (float) pickedRail.getLength() : 100f;
                    List<Float> flipped = new ArrayList<>();
                    for (float pos : placement.manualPositions) {
                        flipped.add(quantizePosition(railLen - pos));
                    }
                    Collections.reverse(flipped);
                    placement.manualPositions = flipped;
                    sendUpdate();
                    Minecraft.getInstance().tell(this::loadPage);
                }
        )), x, y, halfW);
        y += SQUARE_SIZE + 4;

        float railLength = pickedRail != null ? (float) pickedRail.getLength() : 100f;
        currentBar = new WidgetManualPositionBar(x, y, w, positions -> {
            placement.manualPositions = new ArrayList<>(positions);
            sendUpdate();
        });
        currentBar.setRailLength(railLength);
        currentBar.setPositions(placement.manualPositions);
        currentBar.setOnSelectionChange(() -> updatePositionInputField(placement));
        addRenderableWidget(currentBar);
        y += currentBar.getHeight() + 4;

        addRenderableWidget(new WidgetLabel(x, y, w,
                Text.literal(String.format("Positions: %d  (L-click: add/select, R-click: remove)", placement.manualPositions.size()))));
        y += SQUARE_SIZE - 2;

        addRenderableWidget(new WidgetLabel(x, y + 6, halfW,
                Text.translatable("gui.mtr.placement_editor_selected_offset")));
        positionInputField = new WidgetBetterTextField("", 10);
        IDrawing.setPositionAndWidth(addRenderableWidget(positionInputField), x + halfW + 4, y, halfW);
        updatePositionInputField(placement);
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

    private void updatePositionInputField(RailModelPlacement placement) {
        if (positionInputField == null || currentBar == null) return;
        int sel = currentBar.getSelectedIndex();
        if (sel >= 0 && sel < placement.manualPositions.size()) {
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

    private List<Float> computePositionsForCurrentMode(RailModelPlacement p) {
        float railLength = pickedRail != null ? (float) pickedRail.getLength() : 100f;
        float interval = p.intervalOverride;
        if (interval <= 0) {
            RailModelProperties props = RailModelRegistry.elements.get(p.modelKey);
            if (props != null) interval = props.repeatInterval;
        }
        if (interval <= 0) interval = 1.0f;

        List<Float> result = new ArrayList<>();
        switch (p.placementMode) {
            case STRETCH_INTERVAL: {
                if (railLength < interval * 0.5f) {
                    result.add(quantizePosition(railLength / 2));
                    break;
                }
                int N = Math.max(2, Math.round(railLength / interval) + 1);
                float actualI = railLength / (N - 1);
                for (int k = 0; k < N; k++) {
                    result.add(quantizePosition(k * actualI));
                }
                break;
            }
            case FIXED_INTERVAL: {
                if (p.offsetFromStart) {
                    for (float t = p.offset; t < railLength - 0.001f; t += interval) {
                        result.add(quantizePosition(t));
                    }
                } else {
                    List<Float> tmp = new ArrayList<>();
                    for (float t = railLength - p.offset; t > 0.001f; t -= interval) {
                        tmp.add(quantizePosition(t));
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

    private void sendPropagate(RailModelPlacement placement, boolean undo) {
        final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        if (undo) {
            packet.writeByte(2);
        } else {
            packet.writeByte(0);
            packet.writeBlockPos(pickedPosStart);
            packet.writeBlockPos(pickedPosEnd);
            packet.writeVarInt(selectedLayerIndex);
            packet.writeUtf(placement.modelKey);
            float interval = placement.intervalOverride;
            if (interval <= 0) {
                RailModelProperties props = RailModelRegistry.elements.get(placement.modelKey);
                if (props != null) interval = props.repeatInterval;
            }
            packet.writeFloat(interval);
            packet.writeBoolean(placement.reversed);
            packet.writeFloat(placement.offset);
        }
        RegistryClient.sendToServer(IPacket.PACKET_PROPAGATE_PLACEMENT, packet);
    }

    @Override
    protected void onBtnClick(String btnKey) {
        RailModelPlacement sel = getSelectedPlacement();
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
        if (!toolItem.is(mtr.Items.PLACEMENT_TOOL.get())) return;
        CompoundTag tag = toolItem.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        writePlacementsToNbt(tag, getPlacements());
        toolItem.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        PacketUpdateHoldingItem.sendUpdateC2S();
    }

    public static void acquirePickInfoWhenUse() {
        pickedRail = RailPicker.pickedRail;
        pickedPosStart = RailPicker.pickedPosStart;
        pickedPosEnd = RailPicker.pickedPosEnd;
    }

    public static void applyPlacementTemplate(CompoundTag toolTag, boolean isBatchApply) {
        if (toolTag == null || pickedRail == null) return;
        RailExtraSupplier extra = (RailExtraSupplier) pickedRail;

        List<RailModelPlacement> template = readPlacementsFromNbt(toolTag);
        if (template.isEmpty()) {
            if (isBatchApply) {
                List<RailModelPlacement> current = extra.getModelPlacements();
                if (!current.isEmpty()) {
                    current.get(0).reversed = !current.get(0).reversed;
                }
            }
        } else {
            boolean same = arePlacementsEqual(extra.getModelPlacements(), template);
            if (isBatchApply && same) {
                List<RailModelPlacement> current = extra.getModelPlacements();
                if (!current.isEmpty()) {
                    current.get(0).reversed = !current.get(0).reversed;
                }
            } else {
                List<RailModelPlacement> copied = new ArrayList<>();
                for (RailModelPlacement p : template) {
                    copied.add(p.copy());
                }
                extra.setModelPlacements(copied);
            }
        }
        PacketUpdateRail.sendUpdateC2S(pickedRail, pickedPosStart, pickedPosEnd);
    }

    private static boolean arePlacementsEqual(List<RailModelPlacement> a, List<RailModelPlacement> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            RailModelPlacement pa = a.get(i);
            RailModelPlacement pb = b.get(i);
            if (!pa.modelKey.equals(pb.modelKey) || pa.placementMode != pb.placementMode
                    || pa.reversed != pb.reversed || pa.offset != pb.offset
                    || pa.offsetFromStart != pb.offsetFromStart
                    || pa.intervalOverride != pb.intervalOverride) {
                return false;
            }
            if (!pa.manualPositions.equals(pb.manualPositions)) return false;
        }
        return true;
    }

    static void writePlacementsToNbt(CompoundTag tag, List<RailModelPlacement> placements) {
        tag.putInt("PlacementCount", placements.size());
        for (int i = 0; i < placements.size(); i++) {
            RailModelPlacement p = placements.get(i);
            CompoundTag layerTag = new CompoundTag();
            layerTag.putString("ModelKey", p.modelKey);
            layerTag.putInt("Mode", p.placementMode.ordinal());
            layerTag.putFloat("Offset", p.offset);
            layerTag.putBoolean("OffsetFromStart", p.offsetFromStart);
            layerTag.putBoolean("Reversed", p.reversed);
            layerTag.putFloat("IntervalOverride", p.intervalOverride);
            ListTag posTag = new ListTag();
            for (float pos : p.manualPositions) {
                posTag.add(FloatTag.valueOf(pos));
            }
            layerTag.put("ManualPositions", posTag);
            tag.put("Placement_" + i, layerTag);
        }
    }

    static List<RailModelPlacement> readPlacementsFromNbt(CompoundTag tag) {
        int count = tag.getInt("PlacementCount");
        List<RailModelPlacement> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CompoundTag layerTag = tag.getCompound("Placement_" + i);
            if (layerTag.isEmpty()) continue;
            RailModelPlacement p = new RailModelPlacement();
            p.modelKey = layerTag.getString("ModelKey");
            p.placementMode = PlacementMode.fromIndex(layerTag.getInt("Mode"));
            p.offset = layerTag.getFloat("Offset");
            p.offsetFromStart = layerTag.getBoolean("OffsetFromStart");
            p.reversed = layerTag.getBoolean("Reversed");
            p.intervalOverride = layerTag.getFloat("IntervalOverride");
            ListTag posTag = layerTag.getList("ManualPositions", Tag.TAG_FLOAT);
            List<Float> positions = new ArrayList<>();
            for (int j = 0; j < posTag.size(); j++) {
                positions.add(posTag.getFloat(j));
            }
            p.manualPositions = positions;
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
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
