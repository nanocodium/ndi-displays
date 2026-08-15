package dev.nano.ndidisplays.client;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One or more gamepad inputs assigned to a drone action.
 * {@code button:0} is A / Cross, {@code axis:4} is LT, several can be joined with {@code +}.
 */
public final class PadBinding {

    public enum Kind {
        BUTTON, AXIS, UNBOUND
    }

    public record Part(Kind kind, int index) {
        public String serialize() {
            return switch (kind) {
                case BUTTON -> "button:" + index;
                case AXIS -> "axis:" + index;
                case UNBOUND -> "unbound";
            };
        }

        public Component label() {
            return switch (kind) {
                case UNBOUND -> Component.translatable("gui.ndidisplays.pad.unbound");
                case BUTTON -> Component.translatable("gui.ndidisplays.pad.button." + index,
                        Component.literal(String.valueOf(index)));
                case AXIS -> Component.translatable("gui.ndidisplays.pad.axis." + index,
                        Component.literal(String.valueOf(index)));
            };
        }
    }

    private final List<Part> parts;

    private PadBinding(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public static PadBinding unbound() {
        return new PadBinding(List.of());
    }

    public static PadBinding of(Part part) {
        return part.kind == Kind.UNBOUND ? unbound() : new PadBinding(List.of(part));
    }

    public static PadBinding parse(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("unbound")) {
            return unbound();
        }
        List<Part> parts = new ArrayList<>();
        for (String token : raw.split("\\+")) {
            Part part = parsePart(token.trim());
            if (part.kind != Kind.UNBOUND) {
                parts.add(part);
            }
        }
        return new PadBinding(parts);
    }

    private static Part parsePart(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("button:")) {
            return new Part(Kind.BUTTON, parseIndex(lower.substring(7)));
        }
        if (lower.startsWith("axis:")) {
            return new Part(Kind.AXIS, parseIndex(lower.substring(5)));
        }
        return new Part(Kind.UNBOUND, 0);
    }

    private static int parseIndex(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public boolean isUnbound() {
        return parts.isEmpty();
    }

    public List<Part> parts() {
        return parts;
    }

    public String serialize() {
        if (parts.isEmpty()) {
            return "unbound";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append('+');
            }
            out.append(parts.get(i).serialize());
        }
        return out.toString();
    }

    public Component label() {
        if (parts.isEmpty()) {
            return Component.translatable("gui.ndidisplays.pad.unbound");
        }
        Component first = parts.get(0).label();
        if (parts.size() == 1) {
            return first;
        }
        return Component.literal(first.getString() + " + " + parts.get(1).label().getString()
                + (parts.size() > 2 ? "…" : ""));
    }
}
