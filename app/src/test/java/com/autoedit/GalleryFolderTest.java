package com.autoedit;

import static org.junit.Assert.*;

import com.autoedit.frames.GallerySaver;

import org.junit.Test;

/**
 * Spec §24: extracted frames must land in Pictures/AutoEdit/Frames and be
 * visible in Gallery. The MediaStore RELATIVE_PATH is built from
 * {@code Folder.label} and the pre-Android-10 path from {@code Folder.subPath()},
 * so both are checked here - a mismatch between them would silently scatter
 * frames across two different folders.
 */
public class GalleryFolderTest {

    @Test public void framesGoToTheSpecFolder() {
        assertEquals("Pictures/AutoEdit/Frames", GallerySaver.Folder.FRAMES.label);
    }

    /** RELATIVE_PATH is label + "/", so it must already be a full relative path. */
    @Test public void relativePathIsDerivedFromTheLabel() {
        for (GallerySaver.Folder f : GallerySaver.Folder.values()) {
            String rel = f.label + "/";
            assertTrue(f + " relative path must end with a slash: " + rel, rel.endsWith("/"));
            assertTrue(f + " relative path must not contain '//': " + rel, !rel.contains("//"));
        }
    }

    /**
     * The pre-Q File branch and the MediaStore branch must describe the SAME
     * folder. Expressed as a suffix so it does not depend on the value of
     * Environment.DIRECTORY_*, which is null on the JVM.
     */
    @Test public void subPathMatchesTheLabelForEveryFolder() {
        for (GallerySaver.Folder f : GallerySaver.Folder.values()) {
            assertTrue(f + ": label '" + f.label + "' does not end with sub '" + f.subPath() + "'",
                    f.label.endsWith("/" + f.subPath()));
            assertFalse(f + ": sub path must not start with a slash", f.subPath().startsWith("/"));
        }
    }

    /** The frame folder really must be nested one level deeper than PICTURES. */
    @Test public void framesFolderIsNestedUnderAutoEdit() {
        assertEquals("AutoEdit/Frames", GallerySaver.Folder.FRAMES.subPath());
        assertEquals("AutoEdit", GallerySaver.Folder.PICTURES.subPath());
        assertTrue(GallerySaver.Folder.FRAMES.label.endsWith(GallerySaver.Folder.FRAMES.subPath()));
    }

    /** The three destinations must be distinct, or the dialog is meaningless. */
    @Test public void foldersAreDistinct() {
        assertNotEquals(GallerySaver.Folder.PICTURES.label, GallerySaver.Folder.FRAMES.label);
        assertNotEquals(GallerySaver.Folder.PICTURES.label, GallerySaver.Folder.DCIM.label);
        assertNotEquals(GallerySaver.Folder.FRAMES.label, GallerySaver.Folder.DCIM.label);
    }
}
