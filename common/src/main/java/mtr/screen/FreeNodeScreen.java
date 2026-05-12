package mtr.screen;

import cn.zbx1425.mtrsteamloco.render.RailPicker;
import com.mojang.math.Axis;
import mtr.block.BlockFreeNode;
import mtr.block.BlockNode;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.*;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

public class FreeNodeScreen extends ScreenMapper implements IGui, IPacket {

	private final BlockPos pos;
	private final BlockPos pickedNeighborPos;
	private final boolean hasConnections;
	private TransportMode transportMode;
	private float currentAngle;
	private boolean isUndetermined;

	private WidgetBetterTextField textFieldAngle;
	private AngleSlider slider;
	private Button resetButton;
	private Button transportButton;
	private Button deriveButton;
	private final Button[] directionButtons = new Button[16];
	private final Button[] stepButtons = new Button[4];

	private boolean updatingFromCode;

	private static final int COMPASS_RADIUS = 85;
	private static final int DIR_RING_RADIUS = 50;
	private static final int STEP_RING_RADIUS = 80;
	private static final int LINE_LENGTH = 90;
	private static final int DIR_BTN_SIZE = 18;
	private static final int STEP_BTN_SIZE = 18;
	private static final int TEXT_FIELD_WIDTH = 50;
	private static final int SLIDER_WIDTH = 130;
	private static final int BOTTOM_BTN_WIDTH = 200;
	private static final int LINE_THICKNESS = 2;
	private static final int[] STEP_DELTAS = {-5, -1, 1, 5};
	private static final String[] STEP_LABELS = {"-5", "-1", "+1", "+5"};
	private static final float[] STEP_ANGULAR_OFFSETS = {-30, -13, 13, 30};

	private static final int LINE_COLOR = 0xFFFF4444;
	private static final int RING_COLOR = 0xFF999999;

	public FreeNodeScreen(BlockPos pos) {
		super(Text.literal(""));
		this.pos = pos;
		this.transportMode = TransportMode.TRAIN;
		this.currentAngle = 0;
		this.isUndetermined = true;

		final Level world = Minecraft.getInstance().level;
		Map<BlockPos, Rail> neighborMap = null;
		if (world != null) {
			final BlockEntity entity = world.getBlockEntity(pos);
			if (entity instanceof BlockFreeNode.TileEntityFreeNode) {
				final BlockFreeNode.TileEntityFreeNode tile = (BlockFreeNode.TileEntityFreeNode) entity;
				transportMode = tile.getTransportMode();
				if (tile.isUndetermined()) {
					isUndetermined = true;
					currentAngle = 0;
				} else {
					isUndetermined = false;
					currentAngle = tile.getAngleDegrees();
				}
			}
			neighborMap = ClientData.RAILS.get(pos);
		}

		hasConnections = neighborMap != null && !neighborMap.isEmpty();

		if (hasConnections
				&& RailPicker.pickedPosStart != null && RailPicker.pickedPosStart.equals(pos)
				&& RailPicker.pickedPosEnd != null && neighborMap.containsKey(RailPicker.pickedPosEnd)) {
			pickedNeighborPos = RailPicker.pickedPosEnd;
		} else {
			pickedNeighborPos = null;
		}
	}

	@Override
	protected void init() {
		super.init();

		final int compassCx = width / 2 - 80;
		final int compassCy = height / 2 - 20;
		final int rightX = width / 2 + 25;
		int rightY = compassCy - COMPASS_RADIUS + 40;

		textFieldAngle = new WidgetBetterTextField("0.00", 10);
		textFieldAngle.setResponder(this::onTextFieldChanged);
		IDrawing.setPositionAndWidth(textFieldAngle, compassCx - TEXT_FIELD_WIDTH / 2, compassCy - SQUARE_SIZE / 2, TEXT_FIELD_WIDTH);
		addDrawableChild(textFieldAngle);

		for (int i = 0; i < 16; i++) {
			final float angle = -180 + i * 22.5f;
			final double rad = Math.toRadians(angle);
			final int bx = compassCx + (int) (DIR_RING_RADIUS * Math.cos(rad)) - DIR_BTN_SIZE / 2;
			final int by = compassCy + (int) (DIR_RING_RADIUS * Math.sin(rad)) - DIR_BTN_SIZE / 2;
			final Button btn = UtilitiesClient.newButton(DIR_BTN_SIZE, Text.literal(""), b -> setAngleInternal(angle, false));
			IDrawing.setPositionAndWidth(btn, bx, by, DIR_BTN_SIZE);
			directionButtons[i] = btn;
			addDrawableChild(btn);
		}

		for (int i = 0; i < 4; i++) {
			final int delta = STEP_DELTAS[i];
			final Button btn = UtilitiesClient.newButton(STEP_BTN_SIZE, Text.literal(STEP_LABELS[i]), b -> setAngleInternal(currentAngle + delta, false));
			IDrawing.setPositionAndWidth(btn, 0, 0, STEP_BTN_SIZE);
			stepButtons[i] = btn;
			addDrawableChild(btn);
		}
		repositionStepButtons(compassCx, compassCy);

		resetButton = UtilitiesClient.newButton(Text.translatable("gui.mtr.free_node_reset_undetermined"), b -> {
			isUndetermined = true;
			currentAngle = 0;
			syncAllWidgets();
		});
		IDrawing.setPositionAndWidth(resetButton, rightX, rightY, SLIDER_WIDTH);
		resetButton.active = !hasConnections;
		addDrawableChild(resetButton);
		rightY += SQUARE_SIZE + 6;

		slider = new AngleSlider(rightX, rightY + LINE_HEIGHT + 2, SLIDER_WIDTH, SQUARE_SIZE, isUndetermined ? 0 : currentAngle);
		addDrawableChild(slider);
		rightY += LINE_HEIGHT + 2 + SQUARE_SIZE + 8;

		if (pickedNeighborPos != null) {
			deriveButton = UtilitiesClient.newButton(Text.translatable("gui.mtr.free_node_derive_angle"), b -> deriveAngleFromNeighbor());
			IDrawing.setPositionAndWidth(deriveButton, rightX, rightY + LINE_HEIGHT + 4, SLIDER_WIDTH);
			addDrawableChild(deriveButton);
		}

		final int bottomY = compassCy + COMPASS_RADIUS + 14;
		transportButton = UtilitiesClient.newButton(Text.translatable("gui.mtr.free_node_transport_mode", transportMode), b -> {
			final TransportMode[] modes = TransportMode.values();
			int idx = 0;
			while (idx < modes.length && modes[idx] != transportMode) {
				idx++;
			}
			transportMode = modes[(idx + 1) % modes.length];
			b.setMessage(Text.translatable("gui.mtr.free_node_transport_mode", transportMode));
		});
		IDrawing.setPositionAndWidth(transportButton, width / 2 - BOTTOM_BTN_WIDTH / 2, bottomY, BOTTOM_BTN_WIDTH);
		transportButton.active = !hasConnections;
		addDrawableChild(transportButton);

		syncAllWidgets();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		final int compassCx = width / 2 - 80;
		final int compassCy = height / 2 - 20;
		final int rightX = width / 2 + 25;
		int rightY = compassCy - COMPASS_RADIUS + 40;

		repositionStepButtons(compassCx, compassCy);

		super.render(guiGraphics, mouseX, mouseY, delta);

		rightY += SQUARE_SIZE + 6;
		guiGraphics.drawString(font, Text.translatable("gui.mtr.free_node_angle"), rightX, rightY, ARGB_WHITE);
		rightY += LINE_HEIGHT + 2 + SQUARE_SIZE + 8;

		if (isUndetermined) {
			guiGraphics.drawString(font, Text.translatable("gui.mtr.free_node_undetermined"),
					rightX, compassCy - COMPASS_RADIUS + 10 + SQUARE_SIZE + 6 + LINE_HEIGHT + 2 + SQUARE_SIZE + 4, ARGB_LIGHT_GRAY);
		}

		if (pickedNeighborPos != null) {
			final int dx = pickedNeighborPos.getX() - pos.getX();
			final int dy = pickedNeighborPos.getY() - pos.getY();
			final int dz = pickedNeighborPos.getZ() - pos.getZ();
			final String label = String.format("→ (%+d, %+d, %+d)", dx, dy, dz);
			guiGraphics.drawString(font, label, rightX, rightY, ARGB_WHITE);
		}
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		super.renderBackground(guiGraphics, mouseX, mouseY, delta);

		final int compassCx = width / 2 - 80;
		final int compassCy = height / 2 - 20;

		drawCompassRing(guiGraphics, compassCx, compassCy, COMPASS_RADIUS, RING_COLOR);

		for (float i = 0; i < 360; i += 22.5f) {
			drawTick(guiGraphics, compassCx, compassCy, i, COMPASS_RADIUS - 4, COMPASS_RADIUS, 1, RING_COLOR);
		}

		if (!isUndetermined) {
			drawLine(guiGraphics, compassCx, compassCy, currentAngle, 12, LINE_LENGTH, LINE_THICKNESS, LINE_COLOR);
			drawLine(guiGraphics, compassCx, compassCy, currentAngle + 180, 12, LINE_LENGTH, LINE_THICKNESS, LINE_COLOR);
		}
	}

	@Override
	public void onClose() {
		final float angle = isUndetermined ? 0 : currentAngle;
		PacketTrainDataGuiClient.sendFreeNodeC2S(pos, isUndetermined, angle, transportMode);
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void deriveAngleFromNeighbor() {
		if (pickedNeighborPos == null) {
			return;
		}
		final Level world = Minecraft.getInstance().level;
		if (world == null) {
			return;
		}

		final float neighborRawAngle = readRawAngleClient(world, pickedNeighborPos);
		if (Float.isNaN(neighborRawAngle)) {
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(
						Text.translatable("gui.mtr.free_node_neighbor_undetermined"), true);
			}
			return;
		}

		final Double derivedDeg = RailCalculator.calculateMaxRadiusAngle(
				pickedNeighborPos.getX(), pickedNeighborPos.getZ(),
				pos.getX(), pos.getZ(),
				Math.toRadians(neighborRawAngle)
		);

		if (derivedDeg == null) {
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(
						Text.translatable("gui.mtr.free_node_derive_failed"), true);
			}
			return;
		}

		setAngleInternal(derivedDeg.floatValue(), false);
	}

	private static float readRawAngleClient(Level world, BlockPos nodePos) {
		final BlockState state = world.getBlockState(nodePos);
		if (state.getBlock() instanceof BlockFreeNode) {
			final BlockEntity entity = world.getBlockEntity(nodePos);
			if (entity instanceof BlockFreeNode.TileEntityFreeNode) {
				return ((BlockFreeNode.TileEntityFreeNode) entity).getAngleDegrees();
			}
			return Float.NaN;
		}
		if (state.getBlock() instanceof BlockNode) {
			return BlockNode.getAngle(state);
		}
		return Float.NaN;
	}

	private void setAngleInternal(float degrees, boolean fromTextField) {
		if (updatingFromCode) {
			return;
		}
		updatingFromCode = true;
		currentAngle = RailAngle.quantizeAngle(degrees);
		isUndetermined = false;
		if (!fromTextField) {
			textFieldAngle.setValue(formatAngle(currentAngle));
		}
		slider.setAngle(currentAngle);
		slider.active = true;
		for (final Button btn : stepButtons) {
			btn.active = true;
		}
		updatingFromCode = false;
	}

	private void syncAllWidgets() {
		updatingFromCode = true;
		if (isUndetermined) {
			textFieldAngle.setValue("");
			textFieldAngle.setEditable(false);
			slider.setAngle(0);
			slider.active = false;
			for (final Button btn : stepButtons) {
				btn.active = false;
			}
		} else {
			textFieldAngle.setValue(formatAngle(currentAngle));
			textFieldAngle.setEditable(true);
			slider.setAngle(currentAngle);
			slider.active = true;
			for (final Button btn : stepButtons) {
				btn.active = true;
			}
		}
		updatingFromCode = false;
	}

	private void onTextFieldChanged(String text) {
		if (updatingFromCode) {
			return;
		}
		try {
			final float parsed = Float.parseFloat(text.trim());
			if (Float.isFinite(parsed)) {
				setAngleInternal(parsed, true);
			}
		} catch (final NumberFormatException ignored) {
		}
	}

	private void repositionStepButtons(int cx, int cy) {
		final float baseAngle = isUndetermined ? 0 : currentAngle;
		for (int i = 0; i < 4; i++) {
			final double rad = Math.toRadians(baseAngle + STEP_ANGULAR_OFFSETS[i]);
			final int bx = cx + (int) (STEP_RING_RADIUS * Math.cos(rad)) - STEP_BTN_SIZE / 2;
			final int by = cy + (int) (STEP_RING_RADIUS * Math.sin(rad)) - STEP_BTN_SIZE / 2;
			UtilitiesClient.setWidgetX(stepButtons[i], bx);
			UtilitiesClient.setWidgetY(stepButtons[i], by);
		}
	}

	private static void drawCompassRing(GuiGraphics guiGraphics, int cx, int cy, int radius, int color) {
		final int segments = 64;
		for (int i = 0; i < segments; i++) {
			final float a1 = 360F * i / segments;
			final float a2 = 360F * (i + 1) / segments;
			final double r1 = Math.toRadians(a1);
			final double r2 = Math.toRadians(a2);
			final float x1 = cx + (float) (radius * Math.cos(r1));
			final float y1 = cy + (float) (radius * Math.sin(r1));
			final float x2 = cx + (float) (radius * Math.cos(r2));
			final float y2 = cy + (float) (radius * Math.sin(r2));
			drawSegment(guiGraphics, x1, y1, x2, y2, 1, color);
		}
	}

	private static void drawTick(GuiGraphics guiGraphics, int cx, int cy, float angleDeg, int rInner, int rOuter, int thickness, int color) {
		final double rad = Math.toRadians(angleDeg);
		final float x1 = cx + (float) (rInner * Math.cos(rad));
		final float y1 = cy + (float) (rInner * Math.sin(rad));
		final float x2 = cx + (float) (rOuter * Math.cos(rad));
		final float y2 = cy + (float) (rOuter * Math.sin(rad));
		drawSegment(guiGraphics, x1, y1, x2, y2, thickness, color);
	}

	private static void drawLine(GuiGraphics guiGraphics, int cx, int cy, float angleDeg, int rStart, int rEnd, int thickness, int color) {
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(cx, cy, 0);
		guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(angleDeg));
		guiGraphics.fill(rStart, -thickness / 2, rEnd, -thickness / 2 + thickness, color);
		guiGraphics.pose().popPose();
	}

	private static void drawSegment(GuiGraphics guiGraphics, float x1, float y1, float x2, float y2, int thickness, int color) {
		final float dx = x2 - x1;
		final float dy = y2 - y1;
		final float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.001F) {
			return;
		}
		final float angleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(x1, y1, 0);
		guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(angleDeg));
		guiGraphics.fill(0, -thickness / 2, (int) Math.ceil(len), -thickness / 2 + thickness, color);
		guiGraphics.pose().popPose();
	}

	private static String formatAngle(float angle) {
		if (angle == (int) angle) {
			return String.valueOf((int) angle);
		}
		final String s = String.format("%.2f", angle);
		if (s.contains(".")) {
			String trimmed = s;
			while (trimmed.endsWith("0")) {
				trimmed = trimmed.substring(0, trimmed.length() - 1);
			}
			if (trimmed.endsWith(".")) {
				trimmed = trimmed.substring(0, trimmed.length() - 1);
			}
			return trimmed;
		}
		return s;
	}

	private class AngleSlider extends AbstractSliderButton {

		AngleSlider(int x, int y, int w, int h, float initialAngle) {
			super(x, y, w, h, Text.literal(formatAngle(initialAngle) + "\u00B0"), angleToSlider(initialAngle));
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.literal(formatAngle(sliderToAngle()) + "\u00B0"));
		}

		@Override
		protected void applyValue() {
			setAngleInternal(sliderToAngle(), false);
		}

		void setAngle(float degrees) {
			value = angleToSlider(degrees);
			updateMessage();
		}

		private float sliderToAngle() {
			return (float) (value * 360.0 - 180.0);
		}

		private static double angleToSlider(float degrees) {
			return (degrees + 180.0) / 360.0;
		}
	}
}
