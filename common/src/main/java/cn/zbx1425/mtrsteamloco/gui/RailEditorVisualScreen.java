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
import mtr.client.ClientData;
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
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class RailEditorVisualScreen extends SelectListScreen {

    private enum ModelSelectTarget { NONE, BASE, INSTANCE }
    private ModelSelectTarget modelSelectTarget = ModelSelectTarget.NONE;

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
    private final WidgetScrollPanel rightScrollPanel = new WidgetScrollPanel(0, 0, 100, 100);

    private int savedBarSelectedIndex = -1;
    private int savedBarZoomIndex = 0;
    private float savedBarViewCenter = -1;

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

        if (modelSelectTarget != ModelSelectTarget.NONE) {
            String currentModelKey = "";
            RailModelRepeater sel = getSelectedRepeater();
            if (sel != null) {
                if (modelSelectTarget == ModelSelectTarget.BASE) {
                    currentModelKey = sel.modelKey;
                } else if (modelSelectTarget == ModelSelectTarget.INSTANCE && currentBar != null) {
                    int idx = currentBar.getSelectedIndex();
                    RailModelRepeater.InstanceModelOverride ov = sel.instanceOverrides.get(idx);
                    currentModelKey = ov != null ? ov.modelKeyOverride : "";
                }
            }
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
        if (currentBar != null) {
            savedBarSelectedIndex = currentBar.getSelectedIndex();
            savedBarZoomIndex = currentBar.getZoomIndex();
            savedBarViewCenter = currentBar.getViewCenter();
        }

        List<RailModelRepeater> repeaters = getRepeaters();

        int leftPanelWidth = Mth.clamp(width / 3, 140, 220);
        int rightPanelWidth = Math.min(width - (leftPanelWidth + SQUARE_SIZE) - SQUARE_SIZE, 380);
        int rightPanelX = leftPanelWidth + (width - leftPanelWidth - rightPanelWidth) / 2;

        // -- Left panel: layer list --
        layerScrollList.children.clear();
        IDrawing.setPositionAndWidth(layerScrollList, 0, SQUARE_SIZE, leftPanelWidth);
        layerScrollList.setHeight(height - SQUARE_SIZE * 3);

        for (int i = 0; i < repeaters.size(); i++) {
            RailModelRepeater p = repeaters.get(i);
            String modelLabel = p.modelKey.isEmpty() ? Text.translatable("gui.mtr.rail_editor_visual.default_model").getString() : p.modelKey;
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

        // -- Right panel --
        RailModelRepeater selected = getSelectedRepeater();
        if (selected == null) {
            addRenderableWidget(new WidgetLabel(rightPanelX, SQUARE_SIZE * 2, rightPanelWidth,
                    Text.translatable("gui.mtr.rail_editor_visual.no_layers")));
            return;
        }

        rightScrollPanel.children.clear();
        IDrawing.setPositionAndWidth(rightScrollPanel, rightPanelX, SQUARE_SIZE, rightPanelWidth);
        rightScrollPanel.setHeight(height - SQUARE_SIZE * 2);

        int w = rightPanelWidth - 10;
        int halfW = w / 2 - 2;
        int y = 0;

        // -- Model selector --
        String modelLabel = selected.modelKey.isEmpty() ? Text.translatable("gui.mtr.rail_editor_visual.default_model").getString() : selected.modelKey;
        RailModelProperties props = RailModelRegistry.elements.get(selected.modelKey);
        if (props != null && !props.name.getString().isEmpty()) {
            modelLabel = props.name.getString();
        }
        Button modelBtn = UtilitiesClient.newButton(
                Text.literal(modelLabel),
                sender -> {
                    modelSelectTarget = ModelSelectTarget.BASE;
                    Minecraft.getInstance().tell(this::loadPage);
                }
        );
        IDrawing.setPositionAndWidth(modelBtn, 0, y, w);
        rightScrollPanel.children.add(modelBtn);
        y += SQUARE_SIZE + 4;

        // -- Direction --
        rightScrollPanel.children.add(new WidgetLabel(0, y + 6, halfW,
                Text.translatable("gui.mtr.rail_editor_visual.direction")));

        Button btnNormal = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.facing_away"),
                sender -> { selected.reversed = false; sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnNormal.active = selected.reversed;
        IDrawing.setPositionAndWidth(btnNormal, halfW + 4, y, (w - halfW - 4) / 2 - 1);
        rightScrollPanel.children.add(btnNormal);

        Button btnReversed = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.facing_here"),
                sender -> { selected.reversed = true; sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnReversed.active = !selected.reversed;
        IDrawing.setPositionAndWidth(btnReversed, halfW + 4 + (w - halfW - 4) / 2 + 1, y, (w - halfW - 4) / 2 - 1);
        rightScrollPanel.children.add(btnReversed);
        y += SQUARE_SIZE + 2;

        // -- Interval override --
        rightScrollPanel.children.add(new WidgetLabel(0, y + 6, halfW,
                Text.translatable("gui.mtr.rail_editor_visual.interval")));

        WidgetBetterTextField intervalField = new WidgetBetterTextField("0", 8);
        IDrawing.setPositionAndWidth(intervalField, halfW + 4, y, halfW);
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
        intervalField.active = selected.repeaterMode != RepeaterMode.MANUAL;
        rightScrollPanel.children.add(intervalField);
        y += SQUARE_SIZE + 4;

        // -- Mode selector --
        int modeButtonWidth = w / 3;
        Button btnStretch = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.stretch_interval"),
                sender -> { selected.repeaterMode = RepeaterMode.STRETCH_INTERVAL; sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnStretch.active = selected.repeaterMode != RepeaterMode.STRETCH_INTERVAL;
        IDrawing.setPositionAndWidth(btnStretch, 0, y, modeButtonWidth);
        rightScrollPanel.children.add(btnStretch);

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
        IDrawing.setPositionAndWidth(btnFixed, modeButtonWidth, y, modeButtonWidth);
        rightScrollPanel.children.add(btnFixed);

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
        IDrawing.setPositionAndWidth(btnManual, modeButtonWidth * 2, y, w - modeButtonWidth * 2);
        rightScrollPanel.children.add(btnManual);
        y += SQUARE_SIZE + 4;

        // -- Mode-specific settings --
        if (selected.repeaterMode == RepeaterMode.FIXED_INTERVAL) {
            y = loadFixedPanel(selected, y, w);
        }

        // -- Position bar (all modes) --
        y = loadPositionBar(selected, y, w);

        // -- Instance override panel --
        loadInstanceOverridePanel(selected, y, w);

        addRenderableWidget(rightScrollPanel);
    }

    private int loadFixedPanel(RailModelRepeater repeater, int y, int w) {
        boolean fromThisNode = repeater.offsetFromStart == userIsAtCanonStart();
        int halfW = w / 2 - 2;

        rightScrollPanel.children.add(new WidgetLabel(0, y + 6, halfW,
                Text.translatable("gui.mtr.rail_editor_visual.offset_direction")));

        Button btnFromThis = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.from_this_node"),
                sender -> { repeater.offsetFromStart = userIsAtCanonStart(); sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnFromThis.active = !fromThisNode;
        IDrawing.setPositionAndWidth(btnFromThis, halfW + 4, y, (w - halfW - 4) / 2 - 1);
        rightScrollPanel.children.add(btnFromThis);

        Button btnFromOther = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.from_other_node"),
                sender -> { repeater.offsetFromStart = !userIsAtCanonStart(); sendUpdate(); Minecraft.getInstance().tell(this::loadPage); }
        );
        btnFromOther.active = fromThisNode;
        IDrawing.setPositionAndWidth(btnFromOther, halfW + 4 + (w - halfW - 4) / 2 + 1, y, (w - halfW - 4) / 2 - 1);
        rightScrollPanel.children.add(btnFromOther);
        y += SQUARE_SIZE + 2;

        rightScrollPanel.children.add(new WidgetLabel(0, y + 6, halfW,
                Text.translatable("gui.mtr.rail_editor_visual.offset")));

        WidgetBetterTextField offsetField = new WidgetBetterTextField("0", 8);
        IDrawing.setPositionAndWidth(offsetField, halfW + 4, y, halfW);
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
        rightScrollPanel.children.add(offsetField);
        y += SQUARE_SIZE + 2;

        int btnW = w / 3 - 2;
        Button btnPropagate = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.propagate"),
                sender -> sendPropagate(repeater, false)
        );
        IDrawing.setPositionAndWidth(btnPropagate, 0, y, btnW);
        rightScrollPanel.children.add(btnPropagate);

        Button btnUndo = UtilitiesClient.newButton(
                Text.translatable("gui.mtr.rail_editor_visual.undo_propagate"),
                sender -> sendPropagate(repeater, true)
        );
        IDrawing.setPositionAndWidth(btnUndo, btnW + 2, y, btnW);
        rightScrollPanel.children.add(btnUndo);

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
        IDrawing.setPositionAndWidth(btnContinue, btnW * 2 + 4, y, w - btnW * 2 - 4);
        rightScrollPanel.children.add(btnContinue);
        y += SQUARE_SIZE + 4;

        return y;
    }

    private WidgetManualPositionBar currentBar;
    private WidgetBetterTextField positionInputField;

    private int loadPositionBar(RailModelRepeater repeater, int y, int w) {
        float railLength = pickedRail != null ? (float) pickedRail.getLength() : 100f;
        boolean flipForDisplay = !userIsAtCanonStart();
        boolean isManual = repeater.repeaterMode == RepeaterMode.MANUAL;

        List<Float> computedPositions;
        if (isManual) {
            computedPositions = repeater.manualPositions;
        } else {
            computedPositions = computePositionsForCurrentMode(repeater);
        }

        List<Float> displayPositions;
        if (flipForDisplay) {
            displayPositions = new ArrayList<>(computedPositions.size());
            for (float pos : computedPositions) {
                displayPositions.add(railLength - pos);
            }
            Collections.reverse(displayPositions);
        } else {
            displayPositions = new ArrayList<>(computedPositions);
        }

        rightScrollPanel.children.add(new WidgetLabel(0, y + 6, w,
                Text.translatable("gui.mtr.rail_editor_visual.position_bar_info", displayPositions.size())));
        y += SQUARE_SIZE + 2;

        currentBar = new WidgetManualPositionBar(0, y, w, positions -> {
            if (!isManual) return;
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
        currentBar.setEditable(isManual);
        currentBar.setPositions(displayPositions);
        if (savedBarSelectedIndex >= 0 && savedBarSelectedIndex < displayPositions.size()) {
            currentBar.setSelectedIndex(savedBarSelectedIndex);
        }
        if (savedBarZoomIndex > 0) {
            currentBar.setZoomIndex(savedBarZoomIndex);
        }
        if (savedBarViewCenter >= 0) {
            currentBar.setViewCenter(savedBarViewCenter);
        }

        Set<Integer> ovIndices = new HashSet<>();
        Map<Integer, RailModelRepeater.InstanceModelOverride> displayOverrides = getDisplayOverrides(repeater, flipForDisplay, displayPositions.size());
        for (Map.Entry<Integer, RailModelRepeater.InstanceModelOverride> e : displayOverrides.entrySet()) {
            if (!e.getValue().isDefault()) ovIndices.add(e.getKey());
        }
        currentBar.setOverrideIndices(ovIndices);

        float playerProgress = computePlayerProgress();
        if (playerProgress >= 0) {
            float displayProgress = flipForDisplay ? (railLength - playerProgress) : playerProgress;
            currentBar.setPlayerProgress(displayProgress);
        }

        currentBar.setOnSelectionChange(() -> Minecraft.getInstance().tell(this::loadPage));
        rightScrollPanel.children.add(currentBar);
        y += currentBar.getHeight() + 4;

        if (isManual) {
            int halfW = w / 2 - 2;
            rightScrollPanel.children.add(new WidgetLabel(0, y + 6, halfW,
                    Text.translatable("gui.mtr.rail_editor_visual.selected_offset")));
            positionInputField = new WidgetBetterTextField("", 10);
            IDrawing.setPositionAndWidth(positionInputField, halfW + 4, y, halfW);
            rightScrollPanel.children.add(positionInputField);
            updatePositionInputField(repeater, true);
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
            y += SQUARE_SIZE + 4;
        } else {
            positionInputField = null;
        }

        return y;
    }

    private Map<Integer, RailModelRepeater.InstanceModelOverride> getDisplayOverrides(
            RailModelRepeater repeater, boolean flipForDisplay, int posCount) {
        if (!flipForDisplay) return repeater.instanceOverrides;
        Map<Integer, RailModelRepeater.InstanceModelOverride> result = new HashMap<>();
        for (Map.Entry<Integer, RailModelRepeater.InstanceModelOverride> e : repeater.instanceOverrides.entrySet()) {
            int canonIdx = e.getKey();
            int displayIdx = posCount - 1 - canonIdx;
            if (displayIdx >= 0 && displayIdx < posCount) {
                result.put(displayIdx, e.getValue());
            }
        }
        return result;
    }

    private int displayIndexToCanonIndex(int displayIdx, int posCount) {
        boolean flipForDisplay = !userIsAtCanonStart();
        return flipForDisplay ? (posCount - 1 - displayIdx) : displayIdx;
    }

    private void loadInstanceOverridePanel(RailModelRepeater repeater, int y, int w) {
        if (currentBar == null) return;
        int sel = currentBar.getSelectedIndex();
        if (sel < 0) return;

        List<Float> computedPositions;
        if (repeater.repeaterMode == RepeaterMode.MANUAL) {
            computedPositions = repeater.manualPositions;
        } else {
            computedPositions = computePositionsForCurrentMode(repeater);
        }
        int posCount = computedPositions.size();
        int canonIdx = displayIndexToCanonIndex(sel, posCount);
        if (canonIdx < 0 || canonIdx >= posCount) return;

        RailModelRepeater.InstanceModelOverride override = repeater.instanceOverrides.get(canonIdx);
        boolean hasOverride = override != null && !override.isDefault();

        int halfW = w / 2 - 2;

        // -- Instance model override --
        rightScrollPanel.children.add(new WidgetLabel(0, y + 6, w,
                Text.translatable("gui.mtr.rail_editor_visual.instance_override")));
        y += SQUARE_SIZE - 2;

        String ovModelLabel;
        if (override != null && !override.modelKeyOverride.isEmpty()) {
            RailModelProperties ovProps = RailModelRegistry.elements.get(override.modelKeyOverride);
            ovModelLabel = (ovProps != null && !ovProps.name.getString().isEmpty())
                    ? ovProps.name.getString() : override.modelKeyOverride;
        } else {
            ovModelLabel = Text.translatable("gui.mtr.rail_editor_visual.base_model").getString();
        }

        Button ovModelBtn = UtilitiesClient.newButton(
                Text.literal(ovModelLabel),
                sender -> {
                    modelSelectTarget = ModelSelectTarget.INSTANCE;
                    Minecraft.getInstance().tell(this::loadPage);
                }
        );
        IDrawing.setPositionAndWidth(ovModelBtn, 0, y, hasOverride ? w - SQUARE_SIZE - 2 : w);
        rightScrollPanel.children.add(ovModelBtn);

        if (hasOverride) {
            final int cIdx = canonIdx;
            Button deleteOvBtn = UtilitiesClient.newButton(
                    Text.literal("x"),
                    sender -> {
                        repeater.instanceOverrides.remove(cIdx);
                        sendUpdate();
                        Minecraft.getInstance().tell(this::loadPage);
                    }
            );
            IDrawing.setPositionAndWidth(deleteOvBtn, w - SQUARE_SIZE, y, SQUARE_SIZE);
            rightScrollPanel.children.add(deleteOvBtn);
        }
        y += SQUARE_SIZE + 2;

        // -- Instance direction --
        {
            final int cIdx0 = canonIdx;
            int halfW = w / 2 - 2;
            rightScrollPanel.children.add(new WidgetLabel(0, y + 6, halfW,
                    Text.translatable("gui.mtr.rail_editor_visual.instance_direction")));
            boolean isReversedOv = override != null && override.reversed;
            Button btnSameDir = UtilitiesClient.newButton(
                    Text.translatable("gui.mtr.rail_editor_visual.same_as_base"),
                    sender -> {
                        RailModelRepeater.InstanceModelOverride ov = repeater.instanceOverrides
                                .computeIfAbsent(cIdx0, k -> new RailModelRepeater.InstanceModelOverride());
                        ov.reversed = false;
                        sendUpdate();
                        Minecraft.getInstance().tell(this::loadPage);
                    }
            );
            btnSameDir.active = isReversedOv;
            IDrawing.setPositionAndWidth(btnSameDir, halfW + 4, y, (w - halfW - 4) / 2 - 1);
            rightScrollPanel.children.add(btnSameDir);

            Button btnFlipDir = UtilitiesClient.newButton(
                    Text.translatable("gui.mtr.rail_editor_visual.opposite_to_base"),
                    sender -> {
                        RailModelRepeater.InstanceModelOverride ov = repeater.instanceOverrides
                                .computeIfAbsent(cIdx0, k -> new RailModelRepeater.InstanceModelOverride());
                        ov.reversed = true;
                        sendUpdate();
                        Minecraft.getInstance().tell(this::loadPage);
                    }
            );
            btnFlipDir.active = !isReversedOv;
            IDrawing.setPositionAndWidth(btnFlipDir, halfW + 4 + (w - halfW - 4) / 2 + 1, y, (w - halfW - 4) / 2 - 1);
            rightScrollPanel.children.add(btnFlipDir);
            y += SQUARE_SIZE + 2;
        }

        // -- XYZ offset --
        int thirdW = w / 3 - 2;

        rightScrollPanel.children.add(new WidgetLabel(0, y + 6, thirdW,
                Text.translatable("gui.mtr.rail_editor_visual.offset_x")));
        rightScrollPanel.children.add(new WidgetLabel(thirdW + 2, y + 6, thirdW,
                Text.translatable("gui.mtr.rail_editor_visual.offset_y")));
        rightScrollPanel.children.add(new WidgetLabel(thirdW * 2 + 4, y + 6, thirdW,
                Text.translatable("gui.mtr.rail_editor_visual.offset_z")));
        y += SQUARE_SIZE - 4;

        final int cIdx = canonIdx;

        WidgetBetterTextField fieldX = new WidgetBetterTextField("0", 8);
        IDrawing.setPositionAndWidth(fieldX, 0, y, thirdW);
        fieldX.setValue(override != null ? String.format("%.3f", override.offsetX) : "0");
        fieldX.setResponder(text -> {
            try {
                float val = text.isEmpty() ? 0 : Float.parseFloat(text);
                RailModelRepeater.InstanceModelOverride ov = repeater.instanceOverrides
                        .computeIfAbsent(cIdx, k -> new RailModelRepeater.InstanceModelOverride());
                ov.offsetX = val;
                fieldX.setTextColor(0xE0E0E0);
                sendUpdate();
            } catch (NumberFormatException e) {
                fieldX.setTextColor(0xFF0000);
            }
        });
        rightScrollPanel.children.add(fieldX);

        WidgetBetterTextField fieldY = new WidgetBetterTextField("0", 8);
        IDrawing.setPositionAndWidth(fieldY, thirdW + 2, y, thirdW);
        fieldY.setValue(override != null ? String.format("%.3f", override.offsetY) : "0");
        fieldY.setResponder(text -> {
            try {
                float val = text.isEmpty() ? 0 : Float.parseFloat(text);
                RailModelRepeater.InstanceModelOverride ov = repeater.instanceOverrides
                        .computeIfAbsent(cIdx, k -> new RailModelRepeater.InstanceModelOverride());
                ov.offsetY = val;
                fieldY.setTextColor(0xE0E0E0);
                sendUpdate();
            } catch (NumberFormatException e) {
                fieldY.setTextColor(0xFF0000);
            }
        });
        rightScrollPanel.children.add(fieldY);

        WidgetBetterTextField fieldZ = new WidgetBetterTextField("0", 8);
        IDrawing.setPositionAndWidth(fieldZ, thirdW * 2 + 4, y, thirdW);
        fieldZ.setValue(override != null ? String.format("%.3f", override.offsetZ) : "0");
        fieldZ.setResponder(text -> {
            try {
                float val = text.isEmpty() ? 0 : Float.parseFloat(text);
                RailModelRepeater.InstanceModelOverride ov = repeater.instanceOverrides
                        .computeIfAbsent(cIdx, k -> new RailModelRepeater.InstanceModelOverride());
                ov.offsetZ = val;
                fieldZ.setTextColor(0xE0E0E0);
                sendUpdate();
            } catch (NumberFormatException e) {
                fieldZ.setTextColor(0xFF0000);
            }
        });
        rightScrollPanel.children.add(fieldZ);
    }

    private void updatePositionInputField(RailModelRepeater repeater, boolean editable) {
        if (positionInputField == null || currentBar == null) return;
        int sel = currentBar.getSelectedIndex();
        if (sel >= 0) {
            positionInputField.active = editable;
            positionInputField.setValue(String.format("%.3f", currentBar.getSelectedPosition()));
            positionInputField.setTextColor(0xE0E0E0);
        } else {
            positionInputField.active = false;
            positionInputField.setValue("");
        }
    }

    private float computePlayerProgress() {
        if (pickedRail == null || Minecraft.getInstance().player == null) return -1;
        Vec3 playerPos = Minecraft.getInstance().player.position();
        double railLength = pickedRail.getLength();

        double bestT = 0;
        double bestDistSq = Double.MAX_VALUE;
        double step = Math.max(0.5, railLength / 200);
        for (double t = 0; t <= railLength; t += step) {
            Vec3 pos = pickedRail.getPosition(t);
            double dSq = pos.distanceToSqr(playerPos);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestT = t;
            }
        }

        double lo = Math.max(0, bestT - step);
        double hi = Math.min(railLength, bestT + step);
        for (int i = 0; i < 16; i++) {
            double m1 = lo + (hi - lo) / 3;
            double m2 = hi - (hi - lo) / 3;
            double d1 = pickedRail.getPosition(m1).distanceToSqr(playerPos);
            double d2 = pickedRail.getPosition(m2).distanceToSqr(playerPos);
            if (d1 < d2) hi = m2;
            else lo = m1;
        }
        return (float) ((lo + hi) / 2);
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
        if (sel == null) return;

        if (modelSelectTarget == ModelSelectTarget.INSTANCE && currentBar != null) {
            int displayIdx = currentBar.getSelectedIndex();
            List<Float> computedPositions;
            if (sel.repeaterMode == RepeaterMode.MANUAL) {
                computedPositions = sel.manualPositions;
            } else {
                computedPositions = computePositionsForCurrentMode(sel);
            }
            int canonIdx = displayIndexToCanonIndex(displayIdx, computedPositions.size());
            if (canonIdx >= 0 && canonIdx < computedPositions.size()) {
                RailModelRepeater.InstanceModelOverride ov = sel.instanceOverrides
                        .computeIfAbsent(canonIdx, k -> new RailModelRepeater.InstanceModelOverride());
                ov.modelKeyOverride = btnKey;
            }
        } else {
            sel.modelKey = btnKey;
        }
        sendUpdate();
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

    public static boolean hasValidLastPick() {
        if (pickedRail == null) return false;
        Map<BlockPos, Rail> connections = ClientData.RAILS.get(pickedPosStart);
        if (connections == null) return false;
        return connections.containsKey(pickedPosEnd);
    }

    public static void openLastPickedScreen() {
        Minecraft.getInstance().tell(() -> Minecraft.getInstance().setScreen(new RailEditorVisualScreen()));
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
        if (modelSelectTarget != ModelSelectTarget.NONE) {
            renderSelectPage(guiGraphics);
        }
    }

    @Override
    public void onClose() {
        if (modelSelectTarget != ModelSelectTarget.NONE) {
            modelSelectTarget = ModelSelectTarget.NONE;
            Minecraft.getInstance().tell(this::loadPage);
        } else {
            RailModelRepeater sel = getSelectedRepeater();
            if (sel != null) {
                lastEditedModelKey = sel.modelKey;
                pruneOverrides(sel);
            }
            this.minecraft.setScreen(null);
        }
    }

    private void pruneOverrides(RailModelRepeater repeater) {
        List<Float> positions = computePositionsForCurrentMode(repeater);
        repeater.pruneOverrides(positions.size());
        if (pickedRail != null) sendUpdate();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
