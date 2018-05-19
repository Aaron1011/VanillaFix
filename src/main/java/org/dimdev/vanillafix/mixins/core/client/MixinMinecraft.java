package org.dimdev.vanillafix.mixins.core.client;

import com.google.common.util.concurrent.ListenableFutureTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.crash.CrashReport;
import net.minecraft.profiler.ISnooperInfo;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.MinecraftError;
import net.minecraft.util.ReportedException;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.apache.logging.log4j.Logger;
import org.dimdev.vanillafix.CrashUtils;
import org.dimdev.vanillafix.GuiCrashScreen;
import org.dimdev.vanillafix.IPatchedMinecraft;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.FutureTask;

@Mixin(Minecraft.class)
@SuppressWarnings("RedundantThrows") // Shadow
public abstract class MixinMinecraft implements IThreadListener, ISnooperInfo, IPatchedMinecraft {

    @Shadow @Final private static Logger LOGGER;
    @Shadow volatile boolean running;
    @Shadow private boolean hasCrashed;
    @Shadow private CrashReport crashReporter;
    @Shadow private long debugCrashKeyPressTime;
    @Shadow public WorldClient world;
    @Shadow public static byte[] memoryReserve;
    @Shadow public RenderGlobal renderGlobal;
    @Shadow public GameSettings gameSettings;
    @Shadow public GuiIngame ingameGUI;
    @Shadow public EntityRenderer entityRenderer;
    @Shadow @Final private Queue<FutureTask<?>> scheduledTasks;
    @Shadow private boolean actionKeyF3;
    @Shadow @Nullable private IntegratedServer integratedServer;
    @Shadow private boolean integratedServerIsRunning;
    @Shadow @Final public Profiler mcProfiler;

    @Shadow private void init() throws LWJGLException, IOException {}
    @Shadow private void runGameLoop() throws IOException {}
    @Shadow public void displayGuiScreen(@Nullable GuiScreen guiScreenIn) {}
    @Shadow public CrashReport addGraphicsAndWorldToCrashReport(CrashReport theCrash) { return null; }
    @Shadow public void shutdownMinecraftApplet() {}
    @Shadow @Nullable public abstract NetHandlerPlayClient getConnection();
    @Shadow public static long getSystemTime() { return 0; }
    @Shadow public abstract void loadWorld(@Nullable WorldClient worldClientIn);

    private CrashReport currentReport = null;
    private boolean crashIntegratedServerNextTick;
    private int clientCrashCount = 0;
    private int serverCrashCount = 0;

    /**
     * @reason Allows the player to choose to return to the title screen after a crash, or get
     * a pasteable link to the crash report on paste.dimdev.org.
     */
    @Overwrite
    public void run() {
        running = true;

        try {
            init();
        } catch (Throwable throwable) {
            CrashReport report = CrashReport.makeCrashReport(throwable, "Initializing game");
            report.makeCategory("Initialization");
            displayCrashReport(addGraphicsAndWorldToCrashReport(report)); // TODO: GUI for this too
            return;
        }

        try {
            while (running) {
                if (!hasCrashed || crashReporter == null) {
                    try {
                        runGameLoop();
                    } catch (ReportedException e) {
                        clientCrashCount++;
                        addGraphicsAndWorldToCrashReport(e.getCrashReport());
                        addInfoToCrash(e.getCrashReport());
                        freeMemory();
                        LOGGER.fatal("Reported exception thrown!", e);
                        displayCrashScreen(e.getCrashReport());
                    } catch (Throwable e) {
                        clientCrashCount++;
                        CrashReport report = new CrashReport("Unexpected error", e);
                        addGraphicsAndWorldToCrashReport(report);
                        addInfoToCrash(report);
                        freeMemory();
                        LOGGER.fatal("Unreported exception thrown!", e);
                        displayCrashScreen(report);
                    }
                } else {
                    serverCrashCount++;
                    addInfoToCrash(crashReporter);
                    freeMemory();
                    displayCrashScreen(crashReporter);
                    hasCrashed = false;
                    crashReporter = null;
                }
            }
        } catch (MinecraftError ignored) {
        } finally {
            shutdownMinecraftApplet();
        }
    }

    public void addInfoToCrash(CrashReport report) {
        report.getCategory().addDetail("Client Crashes Since Restart", () -> String.valueOf(clientCrashCount));
        report.getCategory().addDetail("Integrated Server Crashes Since Restart", () -> String.valueOf(serverCrashCount));
    }

    public void displayCrashScreen(CrashReport report) {
        if (currentReport != null) {
            // There was already a crash being reported, the crash screen might have
            // crashed. Report it normally instead.
            LOGGER.error("An uncaught exception occured while displaying the crash screen, making normal report instead", report.getCrashCause());
            displayCrashReport(report);
            return;
        }
        currentReport = report;

        CrashUtils.outputReport(report);

        // Reset hasCrashed, debugCrashKeyPressTime, and crashIntegratedServerNextTick
        hasCrashed = false;
        debugCrashKeyPressTime = -1;
        crashIntegratedServerNextTick = false;

        // Display the crash screen
        displayGuiScreen(new GuiCrashScreen(report, false));

        // Vanilla does this when switching to main menu but not our custom crash screen
        // nor the out of memory screen (see https://bugs.mojang.com/browse/MC-128953)
        gameSettings.showDebugInfo = false;
        ingameGUI.getChatGUI().clearChatMessages(true);
    }

    @Overwrite
    public void displayCrashReport(CrashReport report) {
        CrashUtils.outputReport(report);
        FMLCommonHandler.instance().handleExit(report.getFile() != null ? -1 : -2);
    }

    /**
     * @reason Disconnect from the current world and free memory, using a memory reserve
     * to make sure that an OutOfMemory doesn't happen while doing this.
     * <p>
     * Bugs Fixed:
     * - https://bugs.mojang.com/browse/MC-128953
     * - Memory reserve not recreated after out-of memory
     */
    @Overwrite
    @SuppressWarnings("CallToSystemGC")
    public void freeMemory() {
        int originalMemoryReserveSize = -1;
        try {
            // Separate try in case another mod actually deletes the memoryReserve field
            if (memoryReserve != null) {
                originalMemoryReserveSize = memoryReserve.length;
                memoryReserve = new byte[0];
            }
        } catch (Throwable ignored) {}

        try {
            renderGlobal.deleteAllDisplayLists();
        } catch (Throwable ignored) {}

        try {
            System.gc();

            // Fix: Close the connection to avoid receiving packets from old server
            // when playing in another world (MC-128953)
            if (getConnection() != null) {
                getConnection().getNetworkManager().closeChannel(new TextComponentString("[VanillaFix] Client crashed"));
            }

            loadWorld(null);

            // TODO: Figure out why this isn't necessary for vanilla disconnect:
            scheduledTasks.clear();

            // Fix: Probably not necessary, but do this now rathe than next tick to
            // make it identical to a regular disconnect:
            if (entityRenderer.isShaderActive()) {
                entityRenderer.stopUseShader();
            }
        } catch (Throwable ignored) {}

        System.gc();

        // Fix: Re-create memory reserve so that future crashes work well too
        if (originalMemoryReserveSize != -1) {
            try {
                memoryReserve = new byte[originalMemoryReserveSize];
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void clearCurrentReport() {
        currentReport = null;
    }

    /**
     * @reason Replaces the vanilla F3 + C logic to immediately crash rather than requiring
     * that the buttons are pressed for 6 seconds and add more crash types:
     * F3 + C - Client crash
     * Alt + F3 + C - Integrated server crash
     * Shift + F3 + C - Scheduled client task exception
     * Alt + Shift + F3 + C - Scheduled server task exception
     * <p>
     * Note: Left Shift + F3 + C doesn't work on most keyboards, see http://keyboardchecker.com/
     * Use the right shift instead.
     */
    @Redirect(method = "runTickKeyboard", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;debugCrashKeyPressTime:J", ordinal = 0))
    private long checkForF3C(Minecraft mc) {
        // Fix: Check if keys are down before checking time pressed
        if (Keyboard.isKeyDown(46) && Keyboard.isKeyDown(61)) {
            debugCrashKeyPressTime = getSystemTime();
            actionKeyF3 = true;
        } else {
            debugCrashKeyPressTime = -1L;
        }

        if (debugCrashKeyPressTime > 0L) {
            if (getSystemTime() - debugCrashKeyPressTime >= 0) {
                if (GuiScreen.isShiftKeyDown()) {
                    if (GuiScreen.isAltKeyDown()) {
                        if (integratedServerIsRunning) integratedServer.addScheduledTask(() -> {
                            throw new ReportedException(new CrashReport("Manually triggered server-side scheduled task exception", new Throwable()));
                        });
                    } else {
                        scheduledTasks.add(ListenableFutureTask.create(() -> {
                            throw new ReportedException(new CrashReport("Manually triggered client-side scheduled task exception", new Throwable()));
                        }));
                    }
                } else {
                    if (GuiScreen.isAltKeyDown()) {
                        if (integratedServerIsRunning) crashIntegratedServerNextTick = true;
                    } else {
                        throw new ReportedException(new CrashReport("Manually triggered client-side debug crash", new Throwable()));
                    }
                }
            }
        }
        return -1;
    }

    /**
     * @reason Disables the vanilla F3 + C logic.
     */
    @Redirect(method = "runTickKeyboard", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Keyboard;isKeyDown(I)Z", ordinal = 0))
    private boolean getF3DownForF3C(int key) {
        return false;
    }

    @Override
    public boolean isCrashIntegratedServerNextTick() {
        return crashIntegratedServerNextTick;
    }

    /**
     * @reason Fix GUI logic being included as part of "root.tick.textures" (https://bugs.mojang.com/browse/MC-129556)
     */
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V", ordinal = 0))
    private void endStartSectionTextures(Profiler profiler, String name) {
        profiler.endStartSection("gui");
    }

    /**
     * @reason Part 2 of GUI logic fix.
     */
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V", ordinal = 0))
    private void textureManagerTick(TextureManager textureManager) {
        mcProfiler.endStartSection("textures");
        textureManager.tick();
        mcProfiler.endStartSection("gui");
    }

    /**
     * @reason Implement using Ctrl + 0-9 to select profiler sections 10-19.
     */
    @ModifyVariable(method = "updateDebugProfilerName", at = @At("HEAD"), ordinal = 0)
    private int getKeyCountForProfilerNameUpdate(int keyCount) {
        return GuiScreen.isCtrlKeyDown() ? keyCount + 10 : keyCount;
    }
}
