package org.odm.gtk4;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** One gettext domain for GtkBuilder and Java text, selected by the desktop locale. */
public final class I18n {
    public static final String DOMAIN = "odm";

    private I18n() { }

    /** Must run before GTK initialization or construction of any application window. */
    public static void initialize() {
        Catalog.ensureInitialized();
    }

    public static String tr(String message) {
        // gettext("") is the catalog header, never a user-facing empty label.
        if (message == null || message.isEmpty()) { return message; }
        return Catalog.translations.computeIfAbsent(message, Catalog::translate);
    }

    /** Distinguishes words with different meanings or grammatical forms. */
    public static String context(String context, String message) {
        if (message == null || message.isEmpty()) { return message; }
        return Catalog.translations.computeIfAbsent(context + '\u0004' + message, key -> {
            String translated = Catalog.translate(key);
            return translated.equals(key) ? message : translated;
        });
    }

    public static String format(String message, Object... arguments) {
        return String.format(Locale.getDefault(Locale.Category.FORMAT), tr(message), arguments);
    }

    /** The count selects the catalog's plural rule and is the first format argument. */
    public static String plural(String singular, String plural, long count, Object... arguments) {
        String translated;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) Catalog.NGETTEXT.invokeExact(
                    Catalog.DOMAIN_TEXT, arena.allocateFrom(singular), arena.allocateFrom(plural), count);
            translated = string(result);
        } catch (Throwable error) {
            throw new IllegalStateException("Could not read a plural translation", error);
        }
        Object[] values = new Object[arguments.length + 1];
        values[0] = count;
        System.arraycopy(arguments, 0, values, 1, arguments.length);
        return String.format(Locale.getDefault(Locale.Category.FORMAT), translated, values);
    }

    /** Mark a string for catalog extraction without translating a stored value. */
    public static String mark(String message) { return message; }

    private static String string(MemorySegment address) {
        return address.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static final class Catalog {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MethodHandle GETTEXT = function("dgettext", ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        private static final MethodHandle NGETTEXT = function("dngettext", ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
        private static final MemorySegment DOMAIN_TEXT = Arena.global().allocateFrom(DOMAIN);
        private static final ConcurrentHashMap<String, String> translations = new ConcurrentHashMap<>();

        static {
            try (Arena arena = Arena.ofConfined()) {
                MethodHandle setlocale = function("setlocale", ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                // Linux LC_ALL. GTK also initializes the native locale from the environment.
                MemorySegment locale = (MemorySegment) setlocale.invokeExact(6, arena.allocateFrom(""));
                if (locale.equals(MemorySegment.NULL)) {
                    // Minimal installations may lack the requested locale definition.
                    // Keep UTF-8 working; gettext falls back to the source text when
                    // the platform does not support translations in this locale.
                    MemorySegment fallback = (MemorySegment) setlocale.invokeExact(6,
                            arena.allocateFrom("C.UTF-8"));
                    if (fallback.equals(MemorySegment.NULL)) {
                        throw new IllegalStateException("No UTF-8 system locale is available");
                    }
                }
                // glibc 2.35 still honors LANGUAGE for C.UTF-8, unlike newer
                // glibc and our SystemLocale policy. Normalize it before GTK
                // initializes so native widgets and Java labels agree.
                String messageLocale = org.manager.util.SystemLocale.messageLocaleName(System.getenv());
                String baseLocale = messageLocale.split("[.@]", 2)[0];
                if (baseLocale.equals("C") || baseLocale.equals("POSIX")) {
                    MethodHandle setenv = function("setenv", ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
                    int status = (int) setenv.invokeExact(arena.allocateFrom("LANGUAGE"),
                            arena.allocateFrom("en"), 1);
                    if (status != 0) { throw new IllegalStateException("Could not select the C message locale"); }
                }
                Path directory = extractCatalogs();
                bind("bindtextdomain", arena.allocateFrom(directory.toString()));
                bind("bind_textdomain_codeset", arena.allocateFrom("UTF-8"));
                MethodHandle textdomain = function("textdomain", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                MemorySegment domain = (MemorySegment) textdomain.invokeExact(DOMAIN_TEXT);
                if (domain.equals(MemorySegment.NULL)) { throw new IllegalStateException("Could not select translation domain"); }
            } catch (Throwable error) {
                throw new ExceptionInInitializerError(error);
            }
        }

        private static void ensureInitialized() { }

        private static MethodHandle function(String name, java.lang.foreign.MemoryLayout result,
                java.lang.foreign.MemoryLayout... arguments) {
            return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(),
                    FunctionDescriptor.of(result, arguments));
        }

        private static void bind(String function, MemorySegment value) throws Throwable {
            MethodHandle handle = function(function, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            MemorySegment result = (MemorySegment) handle.invokeExact(DOMAIN_TEXT, value);
            if (result.equals(MemorySegment.NULL)) { throw new IllegalStateException("Could not bind translation catalog"); }
        }

        private static String translate(String message) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment result = (MemorySegment) GETTEXT.invokeExact(DOMAIN_TEXT, arena.allocateFrom(message));
                return string(result);
            } catch (Throwable error) {
                throw new IllegalStateException("Could not read translation", error);
            }
        }

        private static Path extractCatalogs() throws IOException {
            // Native gettext needs files. The same embedded catalog works from Maven,
            // a standalone shaded JAR and all package formats, without system installs.
            Path directory = Files.createTempDirectory("odm-locale-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) { /* The OS may have already removed temporary files. */ }
            }, "odm-locale-cleanup"));
            for (String language : new String[]{"en", "fr"}) {
                String resource = language + "/LC_MESSAGES/" + DOMAIN + ".mo";
                Path target = directory.resolve(resource);
                Files.createDirectories(target.getParent());
                try (var input = I18n.class.getResourceAsStream("/locale/" + resource)) {
                    if (input == null) { throw new IOException("Missing embedded translation catalog: " + resource); }
                    Files.copy(input, target);
                }
            }
            return directory;
        }
    }
}
