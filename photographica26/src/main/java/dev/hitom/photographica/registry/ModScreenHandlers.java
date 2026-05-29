package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.screen.DarkroomScreenHandler;
import dev.hitom.photographica.screen.EnlargerScreenHandler;
import dev.hitom.photographica.screen.PrinterScreenHandler;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;

public final class ModScreenHandlers {
    private ModScreenHandlers() {}

    public static final MenuType<DarkroomScreenHandler> DARKROOM =
            Registry.register(
                    BuiltInRegistries.MENU,
                    Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "darkroom"),
                    new MenuType<>(DarkroomScreenHandler::new, FeatureFlags.DEFAULT_FLAGS)
            );

    public static final MenuType<PrinterScreenHandler> PRINTER =
            Registry.register(
                    BuiltInRegistries.MENU,
                    Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "printer"),
                    new MenuType<>(PrinterScreenHandler::new, FeatureFlags.DEFAULT_FLAGS)
            );

    public static final MenuType<EnlargerScreenHandler> ENLARGER =
            Registry.register(
                    BuiltInRegistries.MENU,
                    Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "enlarger"),
                    new MenuType<>(EnlargerScreenHandler::new, FeatureFlags.DEFAULT_FLAGS)
            );

    public static void register() {
        // Class init handles registration; this forces initialization.
    }
}
