package net.epicpunishments.common.message;

import net.kyori.adventure.text.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class MessageTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_.-]*)}");

    private final Component component;
    private final Set<String> placeholders;

    MessageTemplate(Component component, String source) {
        this.component = Objects.requireNonNull(component, "component");
        var foundPlaceholders = new LinkedHashSet<String>();
        PLACEHOLDER.matcher(source).results()
                .map(result -> result.group(1))
                .forEach(foundPlaceholders::add);
        this.placeholders = Set.copyOf(foundPlaceholders);
    }

    public Component render(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        if (!values.keySet().containsAll(placeholders)) {
            var missing = new LinkedHashSet<>(placeholders);
            missing.removeAll(values.keySet());
            throw new IllegalArgumentException("Missing message placeholders: " + String.join(", ", missing));
        }

        Component rendered = component;
        for (var placeholder : placeholders) {
            String value = Objects.requireNonNull(values.get(placeholder), "placeholder value");
            rendered = rendered.replaceText(builder -> builder
                    .matchLiteral('{' + placeholder + '}')
                    .replacement(Component.text(value)));
        }
        return rendered;
    }
}
