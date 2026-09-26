package org.odm.gtk4;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.gnome.glib.SList;
import org.gnome.gobject.GObject;
import org.gnome.gtk.GtkBuilder;
import org.javagi.base.TransferOwnership;
import org.javagi.gobject.InstanceCache;
import org.javagi.gobject.types.Types;

/**
 * Classpath-.ui loading helper. Every window builds through here so resource
 * handling is identical everywhere.
 */
public final class UiLoader {

    private UiLoader() {
    }

    static SList<GObject> objects(GtkBuilder builder) {
        // Java-GI 1.0.0-RC3 wraps these borrowed objects without refOnce(),
        // consuming the builder's reference when a wrapper is collected.
        // Transfer only the list nodes and acquire each object's Java reference
        // before wrapping it, as GtkBuilder.getObject(id) already does.
        var objects = builder.getObjects();
        objects.setOwnership(TransferOwnership.NONE);
        return new SList<>(objects.handle(), Types.OBJECT, address -> {
            InstanceCache.refOnce(address);
            return (GObject) InstanceCache.get(address, GObject::new);
        }, TransferOwnership.CONTAINER);
    }

    /**
     * Loads a .ui file from the module's classpath resources.
     *
     * @param classpathResource absolute resource path, e.g. "/ui/main-window.ui"
     * @return a builder with the UI definition loaded
     * @throws IllegalStateException if the resource is missing or unparseable
     */
    public static GtkBuilder load(String classpathResource) {
        String xml;
        try (InputStream in = UiLoader.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("Missing UI resource: " + classpathResource);
            }
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read UI resource: " + classpathResource, e);
        }
        try {
            I18n.initialize();
            GtkBuilder builder = GtkBuilder.fromString(xml, -1);
            for (var object : objects(builder)) {
                if (object instanceof org.gnome.gtk.Window window) {
                    ApplicationIcons.configure(window);
                } else if (object instanceof org.gnome.gtk.TreeView tree && tree.getHeadersVisible()) {
                    TreeViewColumnReordering.install(tree);
                }
            }
            return builder;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse UI resource: " + classpathResource, e);
        }
    }
}
