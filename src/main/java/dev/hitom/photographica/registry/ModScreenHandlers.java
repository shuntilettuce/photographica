package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.screen.DarkroomScreenHandler;
import dev.hitom.photographica.screen.EnlargerScreenHandler;
import dev.hitom.photographica.screen.FaxMachineScreenHandler;
import dev.hitom.photographica.screen.FaxOpenData;
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

    public static final ScreenHandlerType<FaxMachineScreenHandler> FAX_MACHINE =
            Registry.register(
                    Registries.SCREEN_HANDLER,
                    Identifier.of(Photographica.MOD_ID, "fax_machine"),
                    new ExtendedScreenHandlerType<>(FaxMachineScreenHandler::new, FaxOpenData.PACKET_CODEC)
            );

    /** The camera body's own lens/media/battery/flash slots. A plain type, not an extended one:
     *  the handler resolves the camera from the player's main hand on both sides, so there is
     *  nothing to send at open time. */
    public static final ScreenHandlerType<dev.hitom.photographica.screen.CameraGearScreenHandler> CAMERA_GEAR =
            Registry.register(
                    Registries.SCREEN_HANDLER,
                    Identifier.of(Photographica.MOD_ID, "camera_gear"),
                    new ScreenHandlerType<>(dev.hitom.photographica.screen.CameraGearScreenHandler::new, FeatureSet.empty())
            );

    /** The album's own photo-grid slots. Same plain-type shape as {@link #CAMERA_GEAR} — the
     *  handler resolves the album from the player's main hand, nothing to send at open time. */
    public static final ScreenHandlerType<dev.hitom.photographica.screen.AlbumScreenHandler> ALBUM =
            Registry.register(
                    Registries.SCREEN_HANDLER,
                    Identifier.of(Photographica.MOD_ID, "album"),
                    new ScreenHandlerType<>(dev.hitom.photographica.screen.AlbumScreenHandler::new, FeatureSet.empty())
            );

    public static void register() {
        // Class init handles registration; this forces initialization.
    }
}
