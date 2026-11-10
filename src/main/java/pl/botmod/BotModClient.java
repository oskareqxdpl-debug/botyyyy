package pl.botmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class BotModClient implements ClientModInitializer {
    public static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        BotListScreen.loadConfig();
        pl.botmod.bot.BotManager.setSlotDecoder(new McSlotDecoder());
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.botmod.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.botmod"));

        // klawisz K w grze
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new BotListScreen(null));
                }
            }
        });

        // przycisk "Boty" w menu glownym i w menu pauzy
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof TitleScreen || screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(
                        ButtonWidget.builder(Text.literal("Boty"), b -> client.setScreen(new BotListScreen(screen)))
                                .dimensions(4, 4, 50, 20).build());
            }
        });
    }
}
