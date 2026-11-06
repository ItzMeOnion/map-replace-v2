package dev.maptooltip.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Create or edit one "map ID -> image" replacement. Images come from the clipboard. */
public class AddReplacementScreen extends Screen {
    private static final int PANEL_W = 480;
    private static final int PANEL_H = 300;
    private static final int PREVIEW_CELLS = 32;
    private static final int PREVIEW_PX = 4;

    private final Screen parent;
    private final Replacement editing;

    private TextFieldWidget mapIdField;
    private TextFieldWidget nameField;

    /** The image to show and save. Null until something is pasted (or loaded when editing). */
    private byte[] previewColors;
    /** True once a new image has been pasted, so Save uses it instead of the stored one. */
    private boolean imageChanged = false;
    private String sourceLabel;

    private String status = "";
    private int statusColor = Ui.TEXT_DIM;

    public AddReplacementScreen(Screen parent, Replacement editing) {
        super(Text.literal(editing == null ? "New Replacement" : "Edit Replacement"));
        this.parent = parent;
        this.editing = editing;
    }

    private int panelW() { return Math.min(width - 24, PANEL_W); }
    private int left() { return (width - panelW()) / 2; }
    private int top() { return Math.max(10, height / 2 - PANEL_H / 2); }

    @Override
    protected void init() {
        int left = left(), top = top();

        mapIdField = new TextFieldWidget(textRenderer, left + 16, top + 66, 230, 20, Text.literal("Map ID"));
        mapIdField.setMaxLength(10);
        mapIdField.setTextPredicate(s -> s.matches("\\d{0,10}"));
        mapIdField.setPlaceholder(Text.literal("e.g. 1452").formatted(Formatting.DARK_GRAY));
        addDrawableChild(mapIdField);

        addDrawableChild(ButtonWidget.builder(heldButtonLabel(), b -> useHeldMap())
                .dimensions(left + 16, top + 90, 230, 20).build());

        nameField = new TextFieldWidget(textRenderer, left + 16, top + 128, 230, 20, Text.literal("Name"));
        nameField.setMaxLength(32);
        nameField.setPlaceholder(Text.literal("Optional label").formatted(Formatting.DARK_GRAY));
        addDrawableChild(nameField);

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Paste image from clipboard").formatted(Formatting.BOLD),
                        b -> pasteFromClipboard())
                .dimensions(left + 16, top + 166, 230, 20).build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal(editing == null ? "Save replacement" : "Save changes").formatted(Formatting.BOLD),
                        b -> save())
                .dimensions(left + 16, top + 220, 218, 24).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
                .dimensions(left + 246, top + 220, 218, 24).build());

        if (editing != null) {
            mapIdField.setText(String.valueOf(editing.mapId()));
            nameField.setText(editing.name());
            previewColors = ReplacementManager.get().imageFor(editing.id());
            sourceLabel = editing.sourcePath();
        } else {
            Integer held = heldMapId();
            if (held != null) {
                mapIdField.setText(String.valueOf(held));
                setStatus("Filled in the map you're holding (#" + held + ")", Ui.GREEN);
            }
        }
    }

    // ---- held map -------------------------------------------------------------

    private Text heldButtonLabel() {
        Integer held = heldMapId();
        return Text.literal(held == null ? "Use held map (none)" : "Use held map (#" + held + ")");
    }

    /** The map ID of the filled map in your hands, or null if you're not holding one. */
    private Integer heldMapId() {
        if (client == null || client.player == null) return null;
        for (ItemStack stack : List.of(client.player.getMainHandStack(), client.player.getOffHandStack())) {
            if (stack.isOf(Items.FILLED_MAP)) {
                MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
                if (id != null) return id.id();
            }
        }
        return null;
    }

    private void useHeldMap() {
        Integer held = heldMapId();
        if (held == null) {
            setStatus("Hold a filled map in your hand first", Ui.RED);
            return;
        }
        mapIdField.setText(String.valueOf(held));
        setStatus("Using the map you're holding (#" + held + ")", Ui.GREEN);
    }

    // ---- clipboard ------------------------------------------------------------

    /** Reads an image from the clipboard. Works with copied pictures and copied image files. */
    private void pasteFromClipboard() {
        try {
            Transferable contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (contents == null) {
                setStatus("Clipboard is empty. Copy an image first.", Ui.RED);
                return;
            }

            BufferedImage image = null;
            String label = "Clipboard image";

            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Object data = contents.getTransferData(DataFlavor.imageFlavor);
                if (data instanceof Image img) {
                    image = toBuffered(img);
                }
            }
            if (image == null && contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                Object data = contents.getTransferData(DataFlavor.javaFileListFlavor);
                if (data instanceof List<?> files && !files.isEmpty() && files.get(0) instanceof File file) {
                    image = ImageIO.read(file);
                    label = file.getName();
                }
            }

            if (image == null) {
                setStatus("No image on the clipboard. Copy a picture or an image file, then paste.", Ui.RED);
                return;
            }

            previewColors = ImageConverter.convert(image);
            imageChanged = true;
            sourceLabel = label;
            setStatus("Pasted! This is how it will look on the map. Press Save.", Ui.GREEN);
        } catch (Exception e) {
            setStatus("Couldn't read the clipboard: " + e.getMessage(), Ui.RED);
        }
    }

    private static BufferedImage toBuffered(Image image) throws IOException {
        if (image instanceof BufferedImage buffered) return buffered;

        // ImageIcon waits until the image has finished loading before we draw it.
        Image loaded = new ImageIcon(image).getImage();
        int w = loaded.getWidth(null), h = loaded.getHeight(null);
        if (w <= 0 || h <= 0) throw new IOException("the clipboard image is empty");

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(loaded, 0, 0, null);
        g.dispose();
        return out;
    }

    // ---- save -----------------------------------------------------------------

    private void save() {
        String idText = mapIdField.getText().trim();
        if (idText.isEmpty()) {
            setStatus("Enter a map ID, or hold the map and press Use held map", Ui.RED);
            return;
        }
        int mapId;
        try {
            mapId = Integer.parseInt(idText);
        } catch (NumberFormatException e) {
            setStatus("Map ID must be a whole number", Ui.RED);
            return;
        }
        if (mapId < 0) {
            setStatus("Map ID can't be negative", Ui.RED);
            return;
        }

        String typedName = nameField.getText().trim();
        String name = typedName.isEmpty() ? "Map #" + mapId : typedName;

        byte[] colors = previewColors;
        String source = sourceLabel;
        if (!imageChanged && editing != null) {
            colors = ReplacementManager.get().imageFor(editing.id());
            source = editing.sourcePath();
        }
        if (colors == null) {
            setStatus("Paste an image from your clipboard first", Ui.RED);
            return;
        }

        ReplacementManager mgr = ReplacementManager.get();
        String id = editing != null ? editing.id() : ReplacementManager.newId();
        boolean enabled = editing == null || editing.enabled();
        try {
            mgr.upsert(new Replacement(id, mapId, name, source == null ? "Clipboard image" : source, enabled), colors);
        } catch (IOException e) {
            setStatus("Could not save: " + e.getMessage(), Ui.RED);
            return;
        }
        client.setScreen(parent);
    }

    private void setStatus(String text, int color) {
        this.status = text;
        this.statusColor = color;
    }

    // ---- rendering ------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Ui.background(ctx, width, height);

        int left = left(), top = top(), pw = panelW();
        Ui.panel(ctx, left, top, pw, PANEL_H);

        ctx.drawTextWithShadow(textRenderer,
                Text.literal(editing == null ? "NEW REPLACEMENT" : "EDIT REPLACEMENT").formatted(Formatting.BOLD),
                left + 16, top + 12, Ui.GOLD);
        ctx.drawTextWithShadow(textRenderer, "Pick a map ID and paste any picture", left + 16, top + 26, Ui.TEXT_DIM);

        ctx.drawTextWithShadow(textRenderer, "MAP ID", left + 16, top + 54, Ui.GOLD);
        ctx.drawTextWithShadow(textRenderer, "NAME", left + 16, top + 116, Ui.GOLD);
        ctx.drawTextWithShadow(textRenderer, "IMAGE", left + 16, top + 154, Ui.GOLD);

        String source = "Source: " + (sourceLabel == null ? "no image yet" : sourceLabel);
        ctx.drawTextWithShadow(textRenderer, Ui.fit(textRenderer, source, 230), left + 16, top + 194, Ui.TEXT_DIM);

        // preview frame
        int px = left + 326, py = top + 52, size = PREVIEW_CELLS * PREVIEW_PX;
        ctx.fill(px - 3, py - 3, px + size + 3, py + size + 3, Ui.GOLD_DIM);
        if (previewColors != null) {
            Ui.mosaic(ctx, previewColors, px, py, PREVIEW_CELLS, PREVIEW_PX);
        } else {
            Ui.checker(ctx, px, py, size, size, 8);
            ctx.drawCenteredTextWithShadow(textRenderer, "Nothing pasted yet", px + size / 2, py + size / 2 - 4, Ui.TEXT_DIM);
        }
        ctx.drawCenteredTextWithShadow(textRenderer, "PREVIEW  (128 x 128)", px + size / 2, top + 38, Ui.GOLD);

        ctx.drawCenteredTextWithShadow(textRenderer,
                "Copy a picture (Copy image, or a .png file), then press Paste.",
                left + pw / 2, top + 254, Ui.TEXT_DIM);
        ctx.drawCenteredTextWithShadow(textRenderer,
                "Holding a map? Press Use held map to fill in its ID.",
                left + pw / 2, top + 266, Ui.TEXT_DIM);
        ctx.drawCenteredTextWithShadow(textRenderer, status, left + pw / 2, top + 280, statusColor);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
