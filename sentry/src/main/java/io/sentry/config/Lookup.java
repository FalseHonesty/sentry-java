package io.sentry.config;

import io.sentry.dsn.Dsn;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Handle lookup of configuration keys by trying JNDI, System Environment, and Java System Properties.
 */
public final class Lookup {
    /**
     * The filename of the Sentry configuration file.
     */
    private static final String CONFIG_FILE_NAME = "sentry.properties";
    /**
     * Properties loaded from the Sentry configuration file, or null if no file was
     * found or it failed to parse.
     */
    private static Properties configProps;
    /**
     * Whether or not to check the JNDI system. Exists so that JNDI checks can be disabled
     * the first time the class is not found.
     */
    private static boolean checkJndi = true;

    static {
        String filePath = getConfigFilePath();
        try {
            InputStream input = getInputStream(filePath);

            if (input != null) {
                configProps = new Properties();
                configProps.load(input);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Hidden constructor for static utility class.
     */
    private Lookup() {

    }

    private static String getConfigFilePath() {
        String filePath = System.getProperty("sentry.properties.file");

        if (filePath == null) {
            filePath = System.getenv("SENTRY_PROPERTIES_FILE");
        }

        if (filePath == null) {
            filePath = CONFIG_FILE_NAME;
        }

        return filePath;
    }

    private static InputStream getInputStream(String filePath) throws FileNotFoundException {
        File file = new File(filePath);
        if (file.isFile() && file.canRead()) {
            return new FileInputStream(file);
        }

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader.getResourceAsStream(filePath);
    }

    /**
     * Attempt to lookup a configuration key, without checking any DSN options.
     *
     * @param key name of configuration key, e.g. "dsn"
     * @return value of configuration key, if found, otherwise null
     */
    public static String lookup(String key) {
        return lookup(key, null);
    }

    /**
     * Attempt to lookup a configuration key using the following order:
     *
     * 1. JNDI, if available
     * 2. Java System Properties
     * 3. System Environment Variables
     * 4. DSN options, if a non-null DSN is provided
     * 5. Sentry properties file found in resources
     *
     * @param key name of configuration key, e.g. "dsn"
     * @param dsn an optional DSN to retrieve options from
     * @return value of configuration key, if found, otherwise null
     */
    public static String lookup(String key, Dsn dsn) {
        String value = null;

        if (checkJndi) {
            // Try to obtain from JNDI
            try {
                // Check that JNDI is available (not available on Android) by loading InitialContext
                Class.forName("javax.naming.InitialContext", false, Dsn.class.getClassLoader());
                value = JndiLookup.jndiLookup(key);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                checkJndi = false;
            }
        }

        // Try to obtain from a Java System Property
        if (value == null) {
            value = System.getProperty("sentry." + key.toLowerCase());
        }

        // Try to obtain from a System Environment Variable
        if (value == null) {
            value = System.getenv("SENTRY_" + key.replace(".", "_").toUpperCase());
        }

        // Try to obtain from the provided DSN, if set
        if (value == null && dsn != null) {
            value = dsn.getOptions().get(key);
        }

        // Try to obtain from config file
        if (value == null && configProps != null) {
            value = configProps.getProperty(key);
        }

        if (value != null) {
            return value.trim();
        } else {
            return null;
        }
    }

}
