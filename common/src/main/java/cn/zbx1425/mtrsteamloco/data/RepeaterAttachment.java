package cn.zbx1425.mtrsteamloco.data;

import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.Map;

public class RepeaterAttachment {

    public String id = "";
    public String modelTypeKey = "";
    public float offsetX = 0;
    public float offsetY = 0;
    public float offsetZ = 0;
    public boolean reversed = false;
    public int firstModelIndex = 0;

    public RepeaterAttachment() {
    }

    public RepeaterAttachment(String modelTypeKey, boolean reversed) {
        this.modelTypeKey = modelTypeKey;
        this.reversed = reversed;
    }

    public RepeaterAttachment copy() {
        RepeaterAttachment c = new RepeaterAttachment();
        c.id = this.id;
        c.modelTypeKey = this.modelTypeKey;
        c.offsetX = this.offsetX;
        c.offsetY = this.offsetY;
        c.offsetZ = this.offsetZ;
        c.reversed = this.reversed;
        c.firstModelIndex = this.firstModelIndex;
        return c;
    }

    public boolean isDefault() {
        return id.isEmpty() && modelTypeKey.isEmpty()
                && offsetX == 0 && offsetY == 0 && offsetZ == 0
                && !reversed && firstModelIndex == 0;
    }

    public void toMessagePack(MessagePacker packer) throws IOException {
        int fieldCount = 6;
        if (!id.isEmpty()) fieldCount++;
        packer.packMapHeader(fieldCount);
        if (!id.isEmpty()) {
            packer.packString("id").packString(id);
        }
        packer.packString("mk").packString(modelTypeKey);
        packer.packString("ox").packFloat(offsetX);
        packer.packString("oy").packFloat(offsetY);
        packer.packString("oz").packFloat(offsetZ);
        packer.packString("rv").packBoolean(reversed);
        packer.packString("fmi").packInt(firstModelIndex);
    }

    public static RepeaterAttachment fromMessagePack(MapValue mapValue) {
        RepeaterAttachment att = new RepeaterAttachment();
        for (Map.Entry<Value, Value> entry : mapValue.map().entrySet()) {
            String key = entry.getKey().asStringValue().asString();
            Value val = entry.getValue();
            switch (key) {
                case "id": att.id = val.asStringValue().asString(); break;
                case "mk": att.modelTypeKey = val.asStringValue().asString(); break;
                case "ox": att.offsetX = val.asFloatValue().toFloat(); break;
                case "oy": att.offsetY = val.asFloatValue().toFloat(); break;
                case "oz": att.offsetZ = val.asFloatValue().toFloat(); break;
                case "rv": att.reversed = val.asBooleanValue().getBoolean(); break;
                case "fmi": att.firstModelIndex = val.asIntegerValue().asInt(); break;
            }
        }
        return att;
    }

    public void writePacket(FriendlyByteBuf packet) {
        packet.writeUtf(id);
        packet.writeUtf(modelTypeKey);
        packet.writeFloat(offsetX);
        packet.writeFloat(offsetY);
        packet.writeFloat(offsetZ);
        packet.writeBoolean(reversed);
        packet.writeVarInt(firstModelIndex);
    }

    public static RepeaterAttachment readPacket(FriendlyByteBuf packet) {
        RepeaterAttachment att = new RepeaterAttachment();
        att.id = packet.readUtf();
        att.modelTypeKey = packet.readUtf();
        att.offsetX = packet.readFloat();
        att.offsetY = packet.readFloat();
        att.offsetZ = packet.readFloat();
        att.reversed = packet.readBoolean();
        att.firstModelIndex = packet.readVarInt();
        return att;
    }
}
