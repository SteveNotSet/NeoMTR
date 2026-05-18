package cn.zbx1425.mtrsteamloco.data;

import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RailModelInstanceOverride {

    public List<RepeaterAttachment> attachments;

    public RailModelInstanceOverride() {
        this.attachments = null;
    }

    public boolean isEmpty() {
        return attachments == null || attachments.isEmpty();
    }

    public RailModelInstanceOverride copy() {
        RailModelInstanceOverride c = new RailModelInstanceOverride();
        if (this.attachments != null) {
            c.attachments = new ArrayList<>(this.attachments.size());
            for (RepeaterAttachment att : this.attachments) {
                c.attachments.add(att.copy());
            }
        }
        return c;
    }

    public void toMessagePack(MessagePacker packer) throws IOException {
        int fieldCount = 0;
        if (attachments != null) fieldCount++;
        packer.packMapHeader(fieldCount);
        if (attachments != null) {
            packer.packString("att").packArrayHeader(attachments.size());
            for (RepeaterAttachment att : attachments) {
                att.toMessagePack(packer);
            }
        }
    }

    public static RailModelInstanceOverride fromMessagePack(MapValue mapValue) {
        RailModelInstanceOverride ov = new RailModelInstanceOverride();
        for (Map.Entry<Value, Value> entry : mapValue.map().entrySet()) {
            String key = entry.getKey().asStringValue().asString();
            Value val = entry.getValue();
            if (key.equals("att")) {
                ArrayValue arr = val.asArrayValue();
                ov.attachments = new ArrayList<>(arr.size());
                for (Value v : arr) {
                    ov.attachments.add(RepeaterAttachment.fromMessagePack(v.asMapValue()));
                }
            }
        }
        return ov;
    }

    public void writePacket(FriendlyByteBuf packet) {
        if (attachments != null) {
            packet.writeBoolean(true);
            packet.writeVarInt(attachments.size());
            for (RepeaterAttachment att : attachments) {
                att.writePacket(packet);
            }
        } else {
            packet.writeBoolean(false);
        }
    }

    public static RailModelInstanceOverride readPacket(FriendlyByteBuf packet) {
        RailModelInstanceOverride ov = new RailModelInstanceOverride();
        if (packet.readBoolean()) {
            int count = packet.readVarInt();
            ov.attachments = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ov.attachments.add(RepeaterAttachment.readPacket(packet));
            }
        }
        return ov;
    }
}
