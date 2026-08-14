package net.epicpunishments.common.message;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageCatalogTest {
    @Test
    void parsesMiniMessageOnceAndInsertsPlaceholderValuesAsLiteralText() {
        MessageCatalog catalog = MessageCatalog.parse(messages("<gold>Version {version}</gold>"));

        var rendered = catalog.message("command.version", Map.of("version", "<red>not formatting</red>"));

        assertThat(PlainTextComponentSerializer.plainText().serialize(rendered))
                .isEqualTo("Version <red>not formatting</red>");
    }

    @Test
    void rejectsMissingKeysMalformedTemplatesAndMissingPlaceholders() {
        assertThatThrownBy(() -> MessageCatalog.parse(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing message keys");

        assertThatThrownBy(() -> MessageCatalog.parse(messages("<red>unclosed")))
                .isInstanceOf(IllegalArgumentException.class);

        MessageCatalog catalog = MessageCatalog.parse(messages("Version {version}"));
        assertThatThrownBy(() -> catalog.message("command.version"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    private Map<String, String> messages(String versionTemplate) {
        var messages = new LinkedHashMap<String, String>();
        for (String key : MessageCatalog.REQUIRED_KEYS) {
            messages.put(key, "Message");
        }
        messages.put("command.version", versionTemplate);
        return messages;
    }
}
