package pl.botmod;

import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import pl.botmod.bot.BotManager;

import java.util.List;

/** Zamienia surowe bajty slotow z pakietow na prawdziwe ItemStack-i, uzywajac kodekow Minecrafta. */
public class McSlotDecoder implements BotManager.SlotDecoder {
    private RegistryByteBuf buf(byte[] data) {
        MinecraftClient mc = MinecraftClient.getInstance();
        DynamicRegistryManager rm;
        if (mc.world != null) {
            rm = mc.world.getRegistryManager();
        } else if (mc.getNetworkHandler() != null) {
            rm = mc.getNetworkHandler().getRegistryManager();
        } else {
            rm = DynamicRegistryManager.of(Registries.REGISTRIES);
        }
        return new RegistryByteBuf(Unpooled.wrappedBuffer(data), rm);
    }

    @Override
    public Object[] decodeContent(byte[] data) {
        List<ItemStack> list = ItemStack.OPTIONAL_LIST_PACKET_CODEC.decode(buf(data));
        return list.toArray();
    }

    @Override
    public Object decodeSlot(byte[] data) {
        return ItemStack.OPTIONAL_PACKET_CODEC.decode(buf(data));
    }
}
