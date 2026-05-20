package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.screen.DarkroomScreenHandler;
import dev.hitom.photographica.screen.EnlargerScreenHandler;
import dev.hitom.photographica.screen.PrinterScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class ModScreenHandlers {
    private ModScreenHandlers() {}

    public static final ScreenHandlerType<DarkroomScreenHandler> DARKROOM =
            Registry.register(
                    Registries.SCREEN_HANDLER,
                    Identifier.of(Photographica.MOD_ID, "darkroom"),
                    new ScreenHandlerType<>(DarkroomScreenHandler::new, FeatureSet.empty())
            );

    public static final ScreenHandlerType<PrinterScreenHandler> PRINTER =
            Registry.register(
                    Registries.SCREEN_HANDLER,
                    Identifier.of(Photographica.MOD_ID, "printer"),
                    new ScreenHandlerType<>(PrinterScreenHandler::new, FeatureSet.empty())
            );

    public static final ScreenHandlerType<EnlargerScreenHandler> ENLARGER =
            Registry.register(
                    Registries.SCREEN_HANDLER,
                    Identifier.of(Photographica.MOD_ID, "enlarger"),
                    new ScreenHandlerType<>(EnlargerScreenHandler::new, FeatureSet.empty())
            );

    public static void register() {
        // Class init handles registration; this forces initialization.
    }
}
