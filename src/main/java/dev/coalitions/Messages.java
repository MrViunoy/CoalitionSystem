package dev.coalitions;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

public final class Messages {

    private final Properties texts = new Properties();

    public Messages() {
        Properties buildProperties = load("/language.properties");
        String language = buildProperties.getProperty("language");
        if (!"ru".equals(language) && !"en".equals(language)) {
            throw new IllegalStateException("Unsupported or missing plugin language: " + language);
        }
        texts.putAll(load("/messages_" + language + ".properties"));
    }

    public String get(String key, Object... arguments) {
        String pattern = texts.getProperty(key);
        if (pattern == null) {
            throw new IllegalArgumentException("Missing translation key: " + key);
        }
        return arguments.length == 0 ? pattern : String.format(Locale.ROOT, pattern, arguments);
    }

    private Properties load(String resource) {
        InputStream stream = Messages.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Missing plugin translation resource: " + resource);
        }

        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load plugin translation resource: " + resource, exception);
        }
    }
}
