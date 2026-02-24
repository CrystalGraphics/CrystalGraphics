package io.github.somehussar.crystalgraphics.mc;

import cpw.mods.fml.client.CustomModLoadingErrorDisplayException;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.github.somehussar.crystalgraphics.CrystalGraphicsVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiErrorScreen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Mouse;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side Forge loading error thrown when one or more mods require a
 * higher OpenGL version than the player's GPU/driver provides.
 *
 * <p>This exception extends Forge's {@link CustomModLoadingErrorDisplayException}
 * so that Forge displays a native error GUI instead of a raw crash report.
 * The GUI lists the detected OpenGL version, every incompatible mod, and
 * the version each mod requires, giving the player clear next steps
 * (update drivers or remove incompatible mods).</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Thrown by {@link CrystalGraphicsVersion#processAllRequirements()}
 * during CrystalGraphics' {@code @Mod.EventHandler onInit}. At that point all
 * dependent mods have already registered their requirements in pre-init.</p>
 *
 * <h3>Server Safety</h3>
 * <p>This class is annotated {@link SideOnly}({@link Side#CLIENT}) and is
 * never loaded on a dedicated server. The call path in
 * {@link CrystalGraphicsVersion} guards against server-side execution before
 * any reference to this class.</p>
 *
 * @see CrystalGraphicsVersion#processAllRequirements()
 * @see CrystalGraphicsVersion.ModOpenGlRequirement
 */
@SideOnly(Side.CLIENT)
public final class OpenGLVersionMismatchException extends CustomModLoadingErrorDisplayException {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** Maximum character length for a source file name before truncation. */
    private static final int MAX_SOURCE_NAME_LENGTH = 40;

    private static final int BUTTON_OPEN_MODS = 0;
    private static final int BUTTON_EXIT = 1;

    private final String glVersionString;
    private final int detectedMajor;
    private final int detectedMinor;
    private final List<CrystalGraphicsVersion.ModOpenGlRequirement> failures;

    /** Buttons managed directly (not via buttonList) to bypass GuiErrorScreen.actionPerformed. */
    private transient GuiButton openModsFolderButton;
    private transient GuiButton exitButton;
    /** Tracks whether the mouse was pressed on the previous frame, for edge detection. */
    private transient boolean mouseWasDown;

    /**
     * Constructs a new mismatch exception with full diagnostic data.
     *
     * @param glVersionString the raw string returned by {@code GL11.glGetString(GL11.GL_VERSION)},
     *                        shown in the GUI for driver-level diagnostics
     * @param detectedMajor   the parsed major OpenGL version (e.g. 2 for OpenGL 2.1)
     * @param detectedMinor   the parsed minor OpenGL version (e.g. 1 for OpenGL 2.1)
     * @param failures        the list of mods whose requirements exceed the detected version;
     *                        must not be null or empty
     */
    public OpenGLVersionMismatchException(String glVersionString,
                                          int detectedMajor,
                                          int detectedMinor,
                                          List<CrystalGraphicsVersion.ModOpenGlRequirement> failures) {
        super(buildSummaryMessage(detectedMajor, detectedMinor, failures), null);
        this.glVersionString = glVersionString;
        this.detectedMajor = detectedMajor;
        this.detectedMinor = detectedMinor;
        this.failures = Collections.unmodifiableList(
                new ArrayList<CrystalGraphicsVersion.ModOpenGlRequirement>(failures));
    }

    /**
     * Builds a human-readable one-line summary for the exception message.
     * Used as the {@code getMessage()} text logged by Forge.
     */
    private static String buildSummaryMessage(int detectedMajor,
                                              int detectedMinor,
                                              List<CrystalGraphicsVersion.ModOpenGlRequirement> failures) {
        if (failures.isEmpty()) {
            return "OpenGL requirement mismatch";
        }
        if (failures.size() == 1) {
            CrystalGraphicsVersion.ModOpenGlRequirement one = failures.get(0);
            return one.modName() + " requires OpenGL " + one.requiredMajor() + "." + one.requiredMinor()
                    + " but only " + detectedMajor + "." + detectedMinor + " is available";
        }
        return failures.size() + " mods require a higher OpenGL version than " + detectedMajor + "." + detectedMinor;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates two buttons: "Open mods folder" to open the Minecraft mods
     * directory in the system file explorer, and "Exit" to cleanly shut down
     * the game. Buttons are stored as fields rather than added to the error
     * screen's {@code buttonList} because {@link GuiErrorScreen} does not
     * expose {@code actionPerformed} to the exception; buttons are drawn and
     * click-checked manually in {@link #drawScreen}.</p>
     */
    @Override
    public void initGui(GuiErrorScreen errorScreen, FontRenderer fontRenderer) {
        int xCenter = errorScreen.width / 2;
        int buttonY = errorScreen.height - 38;
        openModsFolderButton = new GuiButton(BUTTON_OPEN_MODS, xCenter - 160 - 2, buttonY, 160, 20, "Open mods folder");
        exitButton = new GuiButton(BUTTON_EXIT, xCenter + 2, buttonY, 160, 20, "Exit");
        mouseWasDown = false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Renders the error screen with:</p>
     * <ol>
     *   <li>A red "OpenGL Version Mismatch" title</li>
     *   <li>The detected OpenGL version (with raw driver string)</li>
     *   <li>A list of incompatible mods, their required versions, and source file/jar</li>
     *   <li>An overflow indicator if the list exceeds available space</li>
     *   <li>A footer with remediation advice</li>
     *   <li>Two buttons: "Open mods folder" and "Exit"</li>
     * </ol>
     */
    @Override
    public void drawScreen(GuiErrorScreen errorScreen, FontRenderer fontRenderer,
                           int mouseRelX, int mouseRelY, float tickTime) {
        int xCenter = errorScreen.width / 2;
        int y = 30;

        drawCentered(fontRenderer, xCenter, y, 0xFF5555, "OpenGL Version Mismatch");
        y += 14;

        drawCentered(fontRenderer, xCenter, y, 0xFFFFFF,
                "Detected OpenGL: " + detectedMajor + "." + detectedMinor
                        + (glVersionString != null ? " (" + glVersionString + ")" : ""));
        y += 12;

        drawCentered(fontRenderer, xCenter, y, 0xFFFFFF,
                "Incompatible mods (remove or update these):");
        y += 14;

        // Reserve space for footer text (12px), gap (8px), and buttons (20px + 8px padding)
        int availableRows = Math.max(1, (errorScreen.height - y - 75) / 10);
        int consumedRows = 0;
        int visibleEntries = 0;
        for (int i = 0; i < failures.size(); i++) {
            CrystalGraphicsVersion.ModOpenGlRequirement failure = failures.get(i);
            int rowsNeeded = (failure.sourceFileName() != null && !failure.sourceFileName().isEmpty()) ? 2 : 1;
            if (consumedRows + rowsNeeded > availableRows) {
                break;
            }

            y = drawFailureEntry(fontRenderer, xCenter, y, failure);
            consumedRows += rowsNeeded;
            visibleEntries++;
        }

        if (visibleEntries < failures.size()) {
            drawCentered(fontRenderer, xCenter, y, 0xAAAAAA,
                    "... and " + (failures.size() - visibleEntries) + " more");
            y += 10;
        }

        y += 8;
        drawCentered(fontRenderer, xCenter, y, 0xAAAAAA,
                "Update your graphics driver or remove incompatible mods.");

        // Draw buttons (managed outside buttonList since GuiCustomModLoadingErrorScreen
        // does not call super.drawScreen and GuiErrorScreen.actionPerformed cannot be overridden)
        Minecraft mc = Minecraft.getMinecraft();
        if (openModsFolderButton != null) {
            openModsFolderButton.drawButton(mc, mouseRelX, mouseRelY);
        }
        if (exitButton != null) {
            exitButton.drawButton(mc, mouseRelX, mouseRelY);
        }

        // Edge-detect mouse clicks on our buttons
        handleButtonClicks(mc, mouseRelX, mouseRelY);
    }

    /**
     * Draws a centered, shadow-text string at the given y-coordinate.
     *
     * @param fr    the font renderer
     * @param x     horizontal center position
     * @param y     vertical position
     * @param color ARGB color (alpha 0 is treated as fully opaque by MC)
     * @param text  the string to render
     */
    private static void drawCentered(FontRenderer fr, int x, int y, int color, String text) {
        fr.drawStringWithShadow(text, x - fr.getStringWidth(text) / 2, y, color);
    }

    /**
     * Draws one incompatible mod entry.
     *
     * <p>First line: {@code - ModName requires OpenGL X.Y}. Optional second line:
     * indented source jar/file as {@code [some-mod.jar]}.</p>
     *
     * @param fontRenderer current font renderer
     * @param xCenter center x for the first line
     * @param yStart starting y position
     * @param failure requirement that failed
     * @return next y position after the rendered entry
     */
    private static int drawFailureEntry(FontRenderer fontRenderer,
                                        int xCenter,
                                        int yStart,
                                        CrystalGraphicsVersion.ModOpenGlRequirement failure) {
        String mainLine = "- " + failure.modName() + " requires OpenGL "
                + failure.requiredMajor() + "." + failure.requiredMinor();
        drawCentered(fontRenderer, xCenter, yStart, 0xFFAA00, mainLine);

        int y = yStart + 10;
        String source = buildSourceLine(failure.sourceFileName());
        if (source != null) {
            int mainLeft = xCenter - fontRenderer.getStringWidth(mainLine) / 2;
            int sourceX = mainLeft + 0;
            fontRenderer.drawStringWithShadow(source, sourceX, y, 0xAAAAAA);
            y += 10;
        }
        return y;
    }

    /**
     * Builds the optional source jar/file line for a failure entry.
     */
    private static String buildSourceLine(String sourceFileName) {
        if (sourceFileName == null || sourceFileName.isEmpty()) {
            return null;
        }

        String source = sourceFileName;
        if (source.length() > MAX_SOURCE_NAME_LENGTH) {
            source = source.substring(0, MAX_SOURCE_NAME_LENGTH - 3) + "...";
        }
        return "[" + source + "]";
    }

    /**
     * Polls LWJGL mouse state to detect rising-edge clicks on our buttons.
     *
     * <p>Because {@link cpw.mods.fml.client.GuiCustomModLoadingErrorScreen} does not
     * forward button events to the exception, we detect clicks here by comparing
     * the current left-mouse-button state against the previous frame.</p>
     *
     * @param mc       the Minecraft instance
     * @param mouseX   current mouse X in GUI coordinates
     * @param mouseY   current mouse Y in GUI coordinates
     */
    private void handleButtonClicks(Minecraft mc, int mouseX, int mouseY) {
        boolean mouseDown = Mouse.isButtonDown(0);
        if (mouseDown && !mouseWasDown) {
            // Rising edge — check if either button was hit
            if (openModsFolderButton != null && openModsFolderButton.mousePressed(mc, mouseX, mouseY)) {
                openModsFolderButton.func_146113_a(mc.getSoundHandler());
                openModsFolder(mc);
            } else if (exitButton != null && exitButton.mousePressed(mc, mouseX, mouseY)) {
                exitButton.func_146113_a(mc.getSoundHandler());
                FMLCommonHandler.instance().exitJava(1, false);
            }
        }
        mouseWasDown = mouseDown;
    }

    /**
     * Opens the Minecraft {@code mods/} directory in the system file explorer.
     *
     * <p>Falls back to logging if the directory cannot be opened (e.g. headless
     * or unsupported platform).</p>
     *
     * @param mc the Minecraft instance, used to resolve {@code mcDataDir}
     */
    private static void openModsFolder(Minecraft mc) {
        try {
            File modsDir = new File(mc.mcDataDir, "mods");
            if (!modsDir.exists()) {
                modsDir.mkdirs();
            }
            Desktop.getDesktop().open(modsDir);
        } catch (Exception e) {
            LOGGER.warn("Could not open mods folder", e);
        }
    }
}
