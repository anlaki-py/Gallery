package org.fossify.gallery

import org.junit.Assert.assertNotNull
import org.junit.Test

/** These entry points are loaded by name, so compilation alone cannot detect missing KSP output. */
class GeneratedComponentsTest {
    @Test
    fun roomDatabaseImplementationIsPackaged() {
        assertNotNull(Class.forName("org.fossify.gallery.databases.GalleryDatabase_Impl", false, javaClass.classLoader))
    }

    @Test
    fun glideModuleImplementationIsPackaged() {
        assertNotNull(Class.forName("com.bumptech.glide.GeneratedAppGlideModuleImpl", false, javaClass.classLoader))
    }
}
