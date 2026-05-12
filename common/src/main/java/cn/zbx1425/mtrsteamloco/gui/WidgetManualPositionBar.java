package cn.zbx1425.mtrsteamloco.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.AbstractWidget;
#if MC_VERSION >= "11700"
import net.minecraft.client.gui.narration.NarrationElementOutput;
#endif
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class WidgetManualPositionBar extends AbstractWidget {

    private float railLength = 100f;
    private final List<Float> positions = new ArrayList<>();
    private int selectedIndex = -1;
    private boolean isDragging = false;
    private final Consumer<List<Float>> onChange;
    private Runnable onSelectionChange;

    private static final int HANDLE_HALF_W = 3;
    private static final int BAR_HEIGHT = 12;
    private static final int HANDLE_HEIGHT = 18;
    private static final int MARGIN = 10;
    private static final float QUANTIZE_STEP = 0.001f;

    public WidgetManualPositionBar(int x, int y, int width, Consumer<List<Float>> onChange) {
        super(x, y, width, HANDLE_HEIGHT + 14, Component.empty());
        this.onChange = onChange;
    }

    public void setOnSelectionChange(Runnable callback) {
        this.onSelectionChange = callback;
    }

    public void setRailLength(float railLength) {
        this.railLength = Math.max(0.1f, railLength);
    }

    public void setPositions(List<Float> newPositions) {
        this.positions.clear();
        this.positions.addAll(newPositions);
        Collections.sort(this.positions);
        if (selectedIndex >= positions.size()) {
            selectedIndex = positions.isEmpty() ? -1 : positions.size() - 1;
        }
    }

    public List<Float> getPositions() {
        return Collections.unmodifiableList(positions);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public float getSelectedPosition() {
        if (selectedIndex >= 0 && selectedIndex < positions.size()) {
            return positions.get(selectedIndex);
        }
        return -1;
    }

    public void setPositionAt(int index, float value) {
        if (index >= 0 && index < positions.size()) {
            positions.set(index, quantize(Math.max(0, Math.min(railLength, value))));
            notifyChange();
        }
    }

    private static float quantize(float value) {
        return Math.round(value / QUANTIZE_STEP) * QUANTIZE_STEP;
    }

    private int barLeft() { return getX() + MARGIN; }
    private int barRight() { return getX() + width - MARGIN; }
    private int barWidth() { return barRight() - barLeft(); }
    private int barTop() { return getY(); }
    private int barCenterY() { return barTop() + BAR_HEIGHT / 2; }

    private int posToPixel(float pos) {
        return barLeft() + Math.round((pos / railLength) * barWidth());
    }

    private float pixelToPos(double px) {
        float pos = (float) ((px - barLeft()) / barWidth()) * railLength;
        return Math.max(0, Math.min(railLength, pos));
    }

    @Override
#if MC_VERSION >= "12000"
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
#elif MC_VERSION >= "11904"
    public void renderWidget(PoseStack guiGraphics, int mouseX, int mouseY, float delta) {
#else
    public void render(PoseStack guiGraphics, int mouseX, int mouseY, float delta) {
#endif
        if (!visible) return;
        Font font = Minecraft.getInstance().font;
        int bl = barLeft();
        int br = barRight();
        int bcy = barCenterY();

#if MC_VERSION >= "12000"
        guiGraphics.fill(bl, bcy - 1, br, bcy + 1, 0xFF808080);
        guiGraphics.fill(bl, bcy - 3, bl + 1, bcy + 3, 0xFFFFFFFF);
        guiGraphics.fill(br - 1, bcy - 3, br, bcy + 3, 0xFFFFFFFF);
#else
        fill(guiGraphics, bl, bcy - 1, br, bcy + 1, 0xFF808080);
        fill(guiGraphics, bl, bcy - 3, bl + 1, bcy + 3, 0xFFFFFFFF);
        fill(guiGraphics, br - 1, bcy - 3, br, bcy + 3, 0xFFFFFFFF);
#endif

        String startLabel = "0";
        String endLabel = String.format("%.1f", railLength);
#if MC_VERSION >= "12000"
        guiGraphics.drawString(font, startLabel, bl - font.width(startLabel) / 2, barTop() + BAR_HEIGHT + 2, 0xFFAAAAAA);
        guiGraphics.drawString(font, endLabel, br - font.width(endLabel) / 2, barTop() + BAR_HEIGHT + 2, 0xFFAAAAAA);
#else
        drawString(guiGraphics, font, startLabel, bl - font.width(startLabel) / 2, barTop() + BAR_HEIGHT + 2, 0xFFAAAAAA);
        drawString(guiGraphics, font, endLabel, br - font.width(endLabel) / 2, barTop() + BAR_HEIGHT + 2, 0xFFAAAAAA);
#endif

        for (int i = 0; i < positions.size(); i++) {
            int px = posToPixel(positions.get(i));
            int color = (i == selectedIndex) ? 0xFFFFFF00 : 0xFF00FF00;
#if MC_VERSION >= "12000"
            guiGraphics.fill(px - HANDLE_HALF_W, bcy - HANDLE_HEIGHT / 2, px + HANDLE_HALF_W, bcy + HANDLE_HEIGHT / 2, color);
#else
            fill(guiGraphics, px - HANDLE_HALF_W, bcy - HANDLE_HEIGHT / 2, px + HANDLE_HALF_W, bcy + HANDLE_HEIGHT / 2, color);
#endif
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active) return false;
        if (mouseX < barLeft() - HANDLE_HALF_W || mouseX > barRight() + HANDLE_HALF_W) return false;
        if (mouseY < barCenterY() - HANDLE_HEIGHT / 2 - 2 || mouseY > barCenterY() + HANDLE_HEIGHT / 2 + 2) return false;

        if (button == 0) {
            int closestIdx = findClosestHandle(mouseX);
            if (closestIdx >= 0) {
                selectedIndex = closestIdx;
                isDragging = true;
            } else {
                float newPos = quantize(pixelToPos(mouseX));
                positions.add(newPos);
                Collections.sort(positions);
                selectedIndex = positions.indexOf(newPos);
                isDragging = true;
                notifyChange();
            }
            notifySelectionChange();
            return true;
        } else if (button == 1) {
            int closestIdx = findClosestHandle(mouseX);
            if (closestIdx >= 0) {
                positions.remove(closestIdx);
                if (selectedIndex >= positions.size()) {
                    selectedIndex = positions.isEmpty() ? -1 : positions.size() - 1;
                }
                isDragging = false;
                notifyChange();
                notifySelectionChange();
                return true;
            }
        }
        return false;
    }

    private int findClosestHandle(double mouseX) {
        int closestIdx = -1;
        double closestDist = Double.MAX_VALUE;
        for (int i = 0; i < positions.size(); i++) {
            double dist = Math.abs(posToPixel(positions.get(i)) - mouseX);
            if (dist < closestDist && dist <= HANDLE_HALF_W + 4) {
                closestDist = dist;
                closestIdx = i;
            }
        }
        return closestIdx;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDragging && selectedIndex >= 0 && selectedIndex < positions.size() && button == 0) {
            float newPos = quantize(pixelToPos(mouseX));
            positions.set(selectedIndex, newPos);
            notifyChange();
            notifySelectionChange();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (isDragging && button == 0) {
            isDragging = false;
            float draggedValue = (selectedIndex >= 0 && selectedIndex < positions.size())
                    ? positions.get(selectedIndex) : -1;
            Collections.sort(positions);
            selectedIndex = draggedValue >= 0 ? positions.indexOf(draggedValue) : -1;
            notifyChange();
            notifySelectionChange();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void notifyChange() {
        onChange.accept(Collections.unmodifiableList(positions));
    }

    private void notifySelectionChange() {
        if (onSelectionChange != null) onSelectionChange.run();
    }

#if MC_VERSION >= "11903"
    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) { }
#elif MC_VERSION >= "11700"
    @Override
    public void updateNarration(NarrationElementOutput arg) { }
#endif

#if MC_VERSION < "11903"
    protected int getX() {
        return x;
    }

    protected int getY() {
        return y;
    }
#endif
}
